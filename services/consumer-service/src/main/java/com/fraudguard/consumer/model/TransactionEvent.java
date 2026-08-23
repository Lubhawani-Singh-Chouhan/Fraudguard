package com.fraudguard.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionEvent {

    private String transactionId;
    private String userId;
    private BigDecimal amount;
    private String currency;
    private String merchantId;
    private String merchantCategory;
    private String location;
    private String deviceId;
    private String channel;
    private String timestamp;
}
