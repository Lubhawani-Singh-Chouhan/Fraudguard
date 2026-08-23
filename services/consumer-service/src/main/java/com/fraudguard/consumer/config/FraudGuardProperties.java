package com.fraudguard.consumer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fraudguard")
public class FraudGuardProperties {

    private Topics topics = new Topics();
    private Scoring scoring = new Scoring();
    private double alertThreshold = 0.70;

    @Getter
    @Setter
    public static class Topics {
        private String transactions = "transactions";
        private String alerts = "fraud-alerts";
    }

    @Getter
    @Setter
    public static class Scoring {
        private String baseUrl = "http://localhost:8000";
        private int connectTimeoutMs = 500;
        private int readTimeoutMs = 1500;
    }
}
