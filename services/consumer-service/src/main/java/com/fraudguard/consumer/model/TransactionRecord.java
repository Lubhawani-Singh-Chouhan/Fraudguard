package com.fraudguard.consumer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "transactions")
public class TransactionRecord {

    @Id
    @Column(name = "transaction_id", nullable = false, unique = true, length = 64)
    private String transactionId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 8)
    private String currency;

    @Column(name = "merchant_id", length = 64)
    private String merchantId;

    @Column(name = "merchant_category", length = 64)
    private String merchantCategory;

    @Column(length = 128)
    private String location;

    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(length = 32)
    private String channel;

    @Column(name = "transaction_ts")
    private Instant transactionTs;

    @Column(name = "risk_score")
    private Double riskScore;

    @Column(name = "risk_level", length = 16)
    private String riskLevel;

    @Column(name = "scoring_reasons", columnDefinition = "TEXT")
    private String scoringReasons;

    @Column(name = "scoring_mode", length = 16)
    private String scoringMode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
