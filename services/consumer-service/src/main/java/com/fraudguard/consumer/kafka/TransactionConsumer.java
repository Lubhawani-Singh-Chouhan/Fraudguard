package com.fraudguard.consumer.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.consumer.model.TransactionEvent;
import com.fraudguard.consumer.service.TransactionProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionConsumer {

    private final ObjectMapper objectMapper;
    private final TransactionProcessingService processingService;

    @KafkaListener(topics = "${fraudguard.topics.transactions}", groupId = "fraudguard-consumer")
    public void onMessage(String payload) {
        processingService.process(parse(payload));
    }

    @KafkaListener(topics = "${fraudguard.topics.alerts}", groupId = "fraudguard-alerter")
    public void onAlert(String payload) {
        // Dedicated alert-service is future work. For now we just log so you can
        // `docker logs fraudguard-consumer` and see high-risk hits go by.
        log.info("fraud-alert {}", payload);
    }

    private TransactionEvent parse(String payload) {
        final TransactionEvent event;
        try {
            event = objectMapper.readValue(payload, TransactionEvent.class);
        } catch (JsonProcessingException e) {
            throw new InvalidTransactionException("malformed transaction json", e);
        }
        if (event.getTransactionId() == null
                || event.getTransactionId().isBlank()
                || event.getUserId() == null
                || event.getUserId().isBlank()) {
            throw new InvalidTransactionException("transaction_id and user_id are required");
        }
        return event;
    }
}
