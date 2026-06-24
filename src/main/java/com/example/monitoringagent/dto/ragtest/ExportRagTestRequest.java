package com.example.monitoringagent.dto.ragtest;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ExportRagTestRequest {
    private String format = "json";
    private List<RagTestCase> testCases = new ArrayList<>();
    private List<RagTestResult> results = new ArrayList<>();
}
