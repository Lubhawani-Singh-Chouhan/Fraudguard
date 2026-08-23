package com.fraudguard.consumer.client;

import com.fraudguard.consumer.model.ScoringRequest;
import com.fraudguard.consumer.model.ScoringResponse;
import com.fraudguard.consumer.service.LocalRulesFallback;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScoringClient {

    private final RestClient scoringRestClient;
    private final LocalRulesFallback localRulesFallback;

    @CircuitBreaker(name = "scoringService", fallbackMethod = "fallbackScore")
    public ScoringResponse score(ScoringRequest request) {
        ScoringResponse response = scoringRestClient.post()
                .uri("/score")
                .body(request)
                .retrieve()
                .body(ScoringResponse.class);
        if (response == null) {
            throw new IllegalStateException("empty scoring response");
        }
        response.setFallback(false);
        return response;
    }

    @SuppressWarnings("unused")
    public ScoringResponse fallbackScore(ScoringRequest request, Throwable error) {
        log.warn(
                "scoring-service unavailable for tx={}, using local fallback: {}",
                request.getTransactionId(),
                error.toString());
        return localRulesFallback.score(request);
    }
}
