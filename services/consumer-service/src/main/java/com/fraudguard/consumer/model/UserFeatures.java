package com.fraudguard.consumer.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserFeatures {

    private long txCount;
    private double avgAmount;
    private double stdAmount;
    private int txLast5m;
    private boolean newDevice;
    private String lastTxTs;
}
