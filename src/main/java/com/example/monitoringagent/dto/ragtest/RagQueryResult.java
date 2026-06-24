package com.example.monitoringagent.dto.ragtest;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class RagQueryResult {
    private String answer;
    private String reasoningContent;
    private List<RagRetrievedDocument> searchResults = new ArrayList<>();
    private long latencyMs;
}
