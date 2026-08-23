package com.fraudguard.consumer.kafka;

import com.fraudguard.consumer.config.FraudGuardProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final FraudGuardProperties props;

    public void publish(String userId, String payload) {
        kafkaTemplate.send(props.getTopics().getAlerts(), userId, payload);
        log.info("published fraud alert user={}", userId);
    }
}
