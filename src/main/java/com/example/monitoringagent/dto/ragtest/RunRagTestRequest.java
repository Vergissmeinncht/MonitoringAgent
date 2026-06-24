package com.example.monitoringagent.dto.ragtest;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class RunRagTestRequest {
    private List<RagTestCase> testCases = new ArrayList<>();
}
