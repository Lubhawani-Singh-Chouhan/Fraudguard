package com.fraudguard.consumer.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.consumer.config.FraudGuardProperties;
import com.fraudguard.consumer.model.TransactionEvent;
import com.fraudguard.consumer.model.TransactionRecord;
import com.fraudguard.consumer.repo.TransactionRepository;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final FraudGuardProperties props;
    private final ObjectMapper objectMapper;
    private final TransactionRepository transactionRepository;

    @PostMapping
    public ResponseEntity<Map<String, String>> inject(@RequestBody TransactionEvent event)
            throws JsonProcessingException {
        if (event.getTransactionId() == null || event.getTransactionId().isBlank()) {
            event.setTransactionId(UUID.randomUUID().toString());
        }
        if (event.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user_id is required");
        }
        String json = objectMapper.writeValueAsString(event);
        kafkaTemplate.send(props.getTopics().getTransactions(), event.getUserId(), json);
        return ResponseEntity.accepted()
                .body(Map.of("transaction_id", event.getTransactionId(), "status", "queued"));
    }

    @GetMapping("/{id}")
    public TransactionRecord getOne(@PathVariable String id) {
        return transactionRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "transaction not scored yet"));
    }
}
