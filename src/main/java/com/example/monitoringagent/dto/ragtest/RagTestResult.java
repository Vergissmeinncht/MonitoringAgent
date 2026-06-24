package com.example.monitoringagent.dto.ragtest;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class RagTestResult {
    private String caseId;
    private String question;
    private String expectedAnswer;
    private String actualAnswer;
    private List<String> expectedKeywords = new ArrayList<>();
    private List<String> expectedDocIds = new ArrayList<>();
    private List<RagRetrievedDocument> retrievedDocuments = new ArrayList<>();
    private boolean hitExpectedDoc;
    private double keywordRecall;
    private double topKRecall;
    private double mrr;
    private double ndcg;
    private double answerRelevancy;
    private long latencyMs;
    private boolean passed;
    private String errorMessage;
}
