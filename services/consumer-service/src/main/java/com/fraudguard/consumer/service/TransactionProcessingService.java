package com.fraudguard.consumer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.consumer.client.ScoringClient;
import com.fraudguard.consumer.config.FraudGuardProperties;
import com.fraudguard.consumer.kafka.AlertProducer;
import com.fraudguard.consumer.model.FraudAlertRecord;
import com.fraudguard.consumer.model.ScoringRequest;
import com.fraudguard.consumer.model.ScoringResponse;
import com.fraudguard.consumer.model.TransactionEvent;
import com.fraudguard.consumer.model.TransactionRecord;
import com.fraudguard.consumer.model.UserFeatures;
import com.fraudguard.consumer.repo.FraudAlertRepository;
import com.fraudguard.consumer.repo.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class TransactionProcessingService {

    private final FeatureStoreService featureStore;
    private final ScoringClient scoringClient;
    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final AlertProducer alertProducer;
    private final FraudGuardProperties props;
    private final ObjectMapper objectMapper;
    private final Counter scoredCounter;
    private final Counter alertCounter;
    private final Counter fallbackCounter;
    private final Timer e2eTimer;

    public TransactionProcessingService(
            FeatureStoreService featureStore,
            ScoringClient scoringClient,
            TransactionRepository transactionRepository,
            FraudAlertRepository fraudAlertRepository,
            AlertProducer alertProducer,
            FraudGuardProperties props,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.featureStore = featureStore;
        this.scoringClient = scoringClient;
        this.transactionRepository = transactionRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.alertProducer = alertProducer;
        this.props = props;
        this.objectMapper = objectMapper;
        this.scoredCounter = Counter.builder("fraudguard.transactions.scored")
                .description("Transactions scored (including fallback)")
                .register(meterRegistry);
        this.alertCounter = Counter.builder("fraudguard.alerts.published")
                .description("Fraud alerts published to Kafka + Postgres")
                .register(meterRegistry);
        this.fallbackCounter = Counter.builder("fraudguard.transactions.fallback")
                .description("Scores produced by the local circuit-breaker fallback")
                .register(meterRegistry);
        this.e2eTimer = Timer.builder("fraudguard.pipeline.latency")
                .description("Kafka consume -> score -> persist")
                .register(meterRegistry);
    }

    @Transactional
    public void process(TransactionEvent event) {
        e2eTimer.record(() -> doProcess(event));
    }

    private void doProcess(TransactionEvent event) {
        UserFeatures features = featureStore.snapshot(event);

        ScoringRequest request = ScoringRequest.builder()
                .transactionId(event.getTransactionId())
                .userId(event.getUserId())
                .amount(event.getAmount() == null ? 0.0 : event.getAmount().doubleValue())
                .merchantCategory(event.getMerchantCategory())
                .location(event.getLocation())
                .deviceId(event.getDeviceId())
                .channel(event.getChannel())
                .timestamp(event.getTimestamp())
                .txCount(features.getTxCount())
                .avgAmount(features.getAvgAmount())
                .stdAmount(features.getStdAmount())
                .txLast5m(features.getTxLast5m())
                .newDevice(features.isNewDevice())
                .lastTxTs(features.getLastTxTs())
                .build();

        ScoringResponse scored = scoringClient.score(request);
        scoredCounter.increment();
        if (scored.isFallback()) {
            fallbackCounter.increment();
        }

        TransactionRecord row = toRecord(event, scored);
        transactionRepository.save(row);
        featureStore.record(event);

        if (scored.getRiskScore() >= props.getAlertThreshold()) {
            persistAndPublishAlert(event, scored);
        }

        log.info(
                "processed tx={} user={} score={} level={} fallback={} reasons={}",
                event.getTransactionId(),
                event.getUserId(),
                scored.getRiskScore(),
                scored.getRiskLevel(),
                scored.isFallback(),
                scored.getReasons());
    }

    private void persistAndPublishAlert(TransactionEvent event, ScoringResponse scored) {
        Instant now = Instant.now();
        FraudAlertRecord alert = new FraudAlertRecord();
        alert.setTransactionId(event.getTransactionId());
        alert.setUserId(event.getUserId());
        alert.setRiskScore(scored.getRiskScore());
        alert.setReasons(joinReasons(scored.getReasons()));
        alert.setAmount(event.getAmount());
        alert.setCreatedAt(now);
        fraudAlertRepository.save(alert);

        try {
            String payload = objectMapper.writeValueAsString(new AlertPayload(
                    event.getTransactionId(),
                    event.getUserId(),
                    scored.getRiskScore(),
                    scored.getReasons(),
                    event.getAmount(),
                    now.toString()));
            alertProducer.publish(event.getUserId(), payload);
            alertCounter.increment();
        } catch (JsonProcessingException e) {
            log.error("failed to serialize alert for tx={}", event.getTransactionId(), e);
        }
    }

    private TransactionRecord toRecord(TransactionEvent event, ScoringResponse scored) {
        TransactionRecord row = new TransactionRecord();
        row.setId(event.getTransactionId());
        row.setUserId(event.getUserId());
        row.setAmount(event.getAmount());
        row.setCurrency(event.getCurrency() == null ? "INR" : event.getCurrency());
        row.setMerchantId(event.getMerchantId());
        row.setMerchantCategory(event.getMerchantCategory());
        row.setLocation(event.getLocation());
        row.setDeviceId(event.getDeviceId());
        row.setChannel(event.getChannel());
        row.setTransactionTs(parseTs(event.getTimestamp()));
        row.setRiskScore(scored.getRiskScore());
        row.setRiskLevel(scored.getRiskLevel());
        row.setScoringReasons(joinReasons(scored.getReasons()));
        row.setScoringMode(scored.getMode());
        row.setCreatedAt(Instant.now());
        return row;
    }

    private static Instant parseTs(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private static String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "";
        }
        return String.join(",", reasons);
    }

    private record AlertPayload(
            String transactionId,
            String userId,
            double riskScore,
            List<String> reasons,
            java.math.BigDecimal amount,
            String createdAt) {}
}
