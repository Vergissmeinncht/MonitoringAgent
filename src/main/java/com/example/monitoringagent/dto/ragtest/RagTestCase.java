package com.example.monitoringagent.dto.ragtest;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class RagTestCase {
    private String id;
    private String question;
    private String expectedAnswer;
    private List<String> expectedKeywords = new ArrayList<>();
    private List<String> expectedDocIds = new ArrayList<>();
    private String source;
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
