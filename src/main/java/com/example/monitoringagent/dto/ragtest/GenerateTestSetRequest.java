package com.example.monitoringagent.dto.ragtest;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GenerateTestSetRequest {
    private String topic;
    private Integer count;
    private String difficulty;
    private String reference;
}
