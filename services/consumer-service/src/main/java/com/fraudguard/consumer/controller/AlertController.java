package com.fraudguard.consumer.controller;

import com.fraudguard.consumer.model.FraudAlertRecord;
import com.fraudguard.consumer.repo.FraudAlertRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final FraudAlertRepository fraudAlertRepository;

    @GetMapping
    public List<FraudAlertRecord> list(
            @RequestParam(name = "since", required = false) String since) {
        Instant from = Instant.EPOCH;
        if (since != null && !since.isBlank()) {
            from = Instant.parse(since);
        }
        return fraudAlertRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(from);
    }
}
