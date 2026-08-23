package com.fraudguard.consumer.model;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoringResponse {

    private String transactionId;
    private double riskScore;
    private String riskLevel;
    private List<String> reasons = new ArrayList<>();
    private String mode;
    private int latencyMs;
    private boolean fallback;
}
