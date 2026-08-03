package com.example.monitoringagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private int topK = 10;
    private String model = "qwen3-max";
    private Rerank rerank = new Rerank();
    private Hybrid hybrid = new Hybrid();
    private Bm25 bm25 = new Bm25();
    private Diagnosis diagnosis = new Diagnosis();

    @Getter
    @Setter
    public static class Rerank {
        private String model = "gte-rerank-v2";
        private int topN = 3;
    }

    @Getter
    @Setter
    public static class Hybrid {
        private int vectorTopK = 10;
        private int bm25TopK = 10;
        private int candidateLimit = 20;
    }

    @Getter
    @Setter
    public static class Bm25 {
        private String indexPath = "./volumes/bm25-index";
    }

    @Getter
    @Setter
    public static class Diagnosis {
        private boolean enableReflection = true;
    }
}
