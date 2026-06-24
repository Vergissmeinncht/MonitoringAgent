package com.example.monitoringagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chat.memory")
public class ChatMemoryProperties {
    private boolean enabled = true;
    private int maxContextTokens = 1000000;
    private double compressionThresholdRatio = 0.7;
    private int reservedOutputTokens = 2000;
    private int minRecentMessagePairs = 3;
    private int summaryMaxTokens = 1000;
}
