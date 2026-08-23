package com.fraudguard.consumer.config;

import java.time.Duration;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ScoringClientConfig {

    @Bean
    public RestClient scoringRestClient(RestClient.Builder builder, FraudGuardProperties props) {
        // Use the auto-configured builder so we inherit the SNAKE_CASE ObjectMapper
        // from application.yml. A raw RestClient.builder() would send camelCase and
        // the FastAPI service would treat is_new_device as false every time.
        ClientHttpRequestFactory factory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofMillis(props.getScoring().getConnectTimeoutMs()))
                        .withReadTimeout(Duration.ofMillis(props.getScoring().getReadTimeoutMs())));
        return builder.clone()
                .baseUrl(props.getScoring().getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
