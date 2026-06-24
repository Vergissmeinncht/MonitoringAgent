package com.example.monitoringagent.dto.ragtest;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class RagTestRunResponse {
    private RagTestSummary summary;
    private List<RagTestResult> results = new ArrayList<>();
}
