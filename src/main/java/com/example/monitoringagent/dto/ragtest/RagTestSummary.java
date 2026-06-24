package com.example.monitoringagent.dto.ragtest;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RagTestSummary {
    private int total;
    private int passed;
    private int failed;
    private double passRate;
    private double averageKeywordRecall;
    private double averageTopKRecall;
    private double averageMrr;
    private double averageNdcg;
    private double averageAnswerRelevancy;
    private double expectedDocHitRate;
    private double averageLatencyMs;
}
