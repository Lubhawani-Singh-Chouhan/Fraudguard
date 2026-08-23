package com.fraudguard.consumer.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoringRequest {

    private String transactionId;
    private String userId;
    private double amount;
    private String merchantCategory;
    private String location;
    private String deviceId;
    private String channel;
    private String timestamp;
    private long txCount;
    private double avgAmount;
    private double stdAmount;
    private int txLast5m;

    // Lombok/Jackson would otherwise emit `new_device` from the boolean getter.
    @JsonProperty("is_new_device")
    private boolean newDevice;

    private String lastTxTs;
}
