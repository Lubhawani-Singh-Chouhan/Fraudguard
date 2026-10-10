package com.fraudguard.consumer.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.consumer.config.FraudGuardProperties;
import com.fraudguard.consumer.model.FraudAlertRecord;
import com.fraudguard.consumer.model.TransactionEvent;
import com.fraudguard.consumer.model.TransactionRecord;
import com.fraudguard.consumer.model.UserFeatures;
import com.fraudguard.consumer.repo.FraudAlertRepository;
import com.fraudguard.consumer.repo.TransactionRepository;
import com.fraudguard.consumer.service.FeatureStoreService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {"transactions", "fraud-alerts", "transactions.DLT"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class ConsumerPipelineTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private FraudAlertRepository alertRepository;

    @Autowired
    private FraudGuardProperties props;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private ConcurrentKafkaListenerContainerFactory<?, ?> kafkaListenerContainerFactory;

    @MockBean
    private FeatureStoreService featureStore;

    @BeforeEach
    void highRiskFeatures() {
        lenient().when(featureStore.snapshot(any())).thenReturn(UserFeatures.builder()
                .txCount(3)
                .avgAmount(500)
                .stdAmount(50)
                .txLast5m(9)
                .newDevice(true)
                .build());
    }

    @Test
    void offsetCommitsAfterEachRecord() {
        assertThat(kafkaListenerContainerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(props.getListener().getRetryMaxAttempts()).isEqualTo(2);
    }

    @Test
    void transactionIdUniqueConstraintRejectsASecondRow() {
        insertTransaction("constraint-1");
        assertThatThrownBy(() -> insertTransaction("constraint-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateDeliveryStoresOneRowAndOneAlert() throws Exception {
        TransactionEvent event = event("dup-1", "user_dup", new BigDecimal("90000.00"));
        String json = objectMapper.writeValueAsString(event);
        String topic = props.getTopics().getTransactions();

        kafkaTemplate.send(topic, event.getUserId(), json).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).until(() -> stored("dup-1") && alertsFor("dup-1") == 1);

        kafkaTemplate.send(topic, event.getUserId(), json).get(10, TimeUnit.SECONDS);
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(5))
                .until(() -> stored("dup-1") && alertsFor("dup-1") == 1);

        assertThat(transactionRepository.findById("dup-1")).isPresent();
        assertThat(alertsFor("dup-1")).isEqualTo(1);
        assertThat(countPayloads(props.getTopics().getAlerts(), "dup-1")).isEqualTo(1);
    }

    @Test
    void poisonMessageIsRetriedThenPublishedToDltAndConsumerContinues() throws Exception {
        String poison = "{not-json";
        String topic = props.getTopics().getTransactions();
        kafkaTemplate.send(topic, "user_poison", poison).get(10, TimeUnit.SECONDS);
        assertEventuallyOnDlt(poison);

        TransactionEvent followUp = event("after-dlt-1", "user_poison", new BigDecimal("120.00"));
        kafkaTemplate.send(topic, followUp.getUserId(), objectMapper.writeValueAsString(followUp))
                .get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).until(() -> stored("after-dlt-1"));
        assertThat(transactionRepository.findById("after-dlt-1").orElseThrow().getScoringMode())
                .isEqualTo("fallback");
    }

    @Test
    void schemaFailureIsPublishedToDlt() throws Exception {
        String missingUser = "{\"transaction_id\":\"no-user\",\"amount\":12}";
        kafkaTemplate.send(props.getTopics().getTransactions(), "nobody", missingUser).get(10, TimeUnit.SECONDS);
        assertEventuallyOnDlt(missingUser);
        assertThat(transactionRepository.findById("no-user")).isEmpty();
    }

    @Test
    void scoringDownUsesLocalFallbackAndKeepsDraining() throws Exception {
        TransactionEvent first = event("drain-1", "user_drain", new BigDecimal("80000.00"));
        TransactionEvent second = event("drain-2", "user_drain", new BigDecimal("81000.00"));
        String topic = props.getTopics().getTransactions();
        kafkaTemplate.send(topic, first.getUserId(), objectMapper.writeValueAsString(first)).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(topic, second.getUserId(), objectMapper.writeValueAsString(second)).get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(20)).until(() -> stored("drain-1") && stored("drain-2"));

        TransactionRecord a = transactionRepository.findById("drain-1").orElseThrow();
        TransactionRecord b = transactionRepository.findById("drain-2").orElseThrow();
        assertThat(a.getScoringMode()).isEqualTo("fallback");
        assertThat(b.getScoringMode()).isEqualTo("fallback");
        assertThat(a.getScoringReasons()).contains("fallback_local_rules", "high_amount");
        assertThat(b.getScoringReasons()).contains("fallback_local_rules", "high_amount");
    }

    @Test
    void consumerExposesInjectScoreAndAlertEndpoints() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/transactions/missing/score")).andExpect(status().isNotFound());

        String body = """
                {"transaction_id":"api-1","user_id":"user_api","amount":90000,"currency":"INR","device_id":"dev_x","channel":"card","timestamp":"2026-08-18T12:00:00Z"}
                """;
        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transaction_id").value("api-1"))
                .andExpect(jsonPath("$.status").value("queued"));

        await().atMost(Duration.ofSeconds(20)).until(() -> stored("api-1") && alertsFor("api-1") == 1);

        mockMvc.perform(get("/transactions/api-1/score"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction_id").value("api-1"))
                .andExpect(jsonPath("$.scoring_mode").value("fallback"));

        mockMvc.perform(get("/alerts").param("since", "2020-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.transaction_id == 'api-1')]").exists());
    }

    private void insertTransaction(String transactionId) {
        jdbcTemplate.update(
                "INSERT INTO transactions (transaction_id, user_id, amount, currency, created_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                transactionId,
                "user_constraint",
                new BigDecimal("10.00"),
                "INR");
    }

    private boolean stored(String transactionId) {
        return transactionRepository.findById(transactionId).isPresent();
    }

    private long alertsFor(String transactionId) {
        return alertRepository.findAll().stream()
                .map(FraudAlertRecord::getTransactionId)
                .filter(transactionId::equals)
                .count();
    }

    private TransactionEvent event(String transactionId, String userId, BigDecimal amount) {
        TransactionEvent event = new TransactionEvent();
        event.setTransactionId(transactionId);
        event.setUserId(userId);
        event.setAmount(amount);
        event.setCurrency("INR");
        event.setMerchantCategory("ecommerce");
        event.setDeviceId("dev_new");
        event.setChannel("card");
        event.setTimestamp("2026-08-18T02:30:00Z");
        return event;
    }

    private void assertEventuallyOnDlt(String expectedPayload) {
        String topic = props.getTopics().getTransactions() + ".DLT";
        try (Consumer<String, String> consumer = consumerOn(topic)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : polled.records(topic)) {
                    if (expectedPayload.equals(record.value())) {
                        return;
                    }
                }
            }
        }
        fail("expected payload was not published to " + topic);
    }

    private long countPayloads(String topic, String needle) {
        try (Consumer<String, String> consumer = consumerOn(topic)) {
            long found = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : polled.records(topic)) {
                    if (record.value() != null && record.value().contains(needle)) {
                        found++;
                    }
                }
            }
            return found;
        }
    }

    private Consumer<String, String> consumerOn(String topic) {
        Map<String, Object> config = new HashMap<>(KafkaTestUtils.consumerProps("assert-" + UUID.randomUUID(), "true", embeddedKafka));
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(config).createConsumer();
        embeddedKafka.consumeFromAnEmbeddedTopic(consumer, topic);
        return consumer;
    }
}
