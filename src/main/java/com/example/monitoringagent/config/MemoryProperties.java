package com.example.monitoringagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {

    private Persistence persistence = new Persistence();
    private LongTerm longTerm = new LongTerm();

    @Getter
    @Setter
    public static class Persistence {
        /** 会话历史与 summary 是否落库到 MongoDB */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class LongTerm {
        /** 跨会话长期记忆总开关 */
        private boolean enabled = true;
        /** 是否在每轮对话完成后抽取长期记忆 */
        private boolean extractionEnabled = true;
        /** 单次注入 Prompt 的最大记忆数 */
        private int retrievalTopK = 5;
        /** 允许写入的最低置信度 */
        private double confidenceThreshold = 0.75;
        /** 单用户最多保留的有效记忆数 */
        private int maxMemoriesPerUser = 500;
        /** 预留语义检索开关；第一版使用 MongoDB 召回 */
        private boolean semanticSearchEnabled = false;
    }
}
