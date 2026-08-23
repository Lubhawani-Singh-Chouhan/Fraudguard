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

    @KafkaListener(topics = "#{fraudGuardProperties.topics.transactions}", groupId = "fraudguard-consumer")
    public void onMessage(String payload) {
        try {
            TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
            if (event.getTransactionId() == null || event.getUserId() == null) {
                log.warn("dropping malformed transaction payload={}", payload);
                return;
            }
            processingService.process(event);
        } catch (JsonProcessingException e) {
            log.warn("could not parse transaction json: {}", e.getOriginalMessage());
        } catch (RuntimeException e) {
            // don't ack-poison the partition forever; log and move on for demo traffic
            log.error("failed to process transaction: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "#{fraudGuardProperties.topics.alerts}", groupId = "fraudguard-alerter")
    public void onAlert(String payload) {
        // Dedicated alert-service is future work. For now we just log so you can
        // `docker logs fraudguard-consumer` and see high-risk hits go by.
        log.info("fraud-alert {}", payload);
    }
}
