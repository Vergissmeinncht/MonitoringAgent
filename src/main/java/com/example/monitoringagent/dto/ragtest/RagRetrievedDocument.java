package com.example.monitoringagent.dto.ragtest;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RagRetrievedDocument {
    private String id;
    private String content;
    private float score;
    private String metadata;
}
