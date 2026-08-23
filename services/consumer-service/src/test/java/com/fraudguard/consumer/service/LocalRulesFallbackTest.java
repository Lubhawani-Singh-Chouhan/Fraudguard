package com.fraudguard.consumer.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudguard.consumer.model.ScoringRequest;
import com.fraudguard.consumer.model.ScoringResponse;
import org.junit.jupiter.api.Test;

class LocalRulesFallbackTest {

    private final LocalRulesFallback fallback = new LocalRulesFallback();

    @Test
    void highAmountNewDeviceIsFlagged() {
        ScoringRequest req = ScoringRequest.builder()
                .transactionId("t1")
                .userId("u1")
                .amount(90_000)
                .newDevice(true)
                .txLast5m(9)
                .build();

        ScoringResponse resp = fallback.score(req);

        assertThat(resp.isFallback()).isTrue();
        assertThat(resp.getMode()).isEqualTo("fallback");
        assertThat(resp.getRiskScore()).isGreaterThanOrEqualTo(0.70);
        assertThat(resp.getReasons()).contains("fallback_local_rules", "high_amount", "new_device");
    }

    @Test
    void ordinaryAmountStaysLow() {
        ScoringRequest req = ScoringRequest.builder()
                .transactionId("t2")
                .userId("u1")
                .amount(500)
                .newDevice(false)
                .txLast5m(1)
                .build();

        ScoringResponse resp = fallback.score(req);
        assertThat(resp.getRiskLevel()).isEqualTo("low");
        assertThat(resp.getRiskScore()).isLessThan(0.40);
    }
}
