package com.fraudguard.consumer.service;

import com.fraudguard.consumer.model.ScoringRequest;
import com.fraudguard.consumer.model.ScoringResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Used when the circuit breaker is open (or the HTTP call otherwise fails).
 * Intentionally dumber than the Python service — amount + new-device + velocity
 * only — so the consumer can keep draining Kafka instead of stalling.
 */
@Component
public class LocalRulesFallback {

    public ScoringResponse score(ScoringRequest req) {
        double value = 0.0;
        List<String> reasons = new ArrayList<>();
        reasons.add("fallback_local_rules");

        if (req.getAmount() >= 75000) {
            value += 0.40;
            reasons.add("high_amount");
        }
        if (req.isNewDevice()) { // lombok: isNewDevice() for boolean newDevice
            value += 0.20;
            reasons.add("new_device");
        }
        if (req.getTxLast5m() >= 8) {
            value += 0.25;
            reasons.add("velocity_spike");
        }

        value = Math.min(value, 1.0);
        String level = value >= 0.70 ? "high" : value >= 0.40 ? "medium" : "low";

        return ScoringResponse.builder()
                .transactionId(req.getTransactionId())
                .riskScore(value)
                .riskLevel(level)
                .reasons(reasons)
                .mode("fallback")
                .latencyMs(0)
                .fallback(true)
                .build();
    }
}
