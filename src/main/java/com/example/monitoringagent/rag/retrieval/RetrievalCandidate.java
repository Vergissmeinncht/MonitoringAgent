package com.example.monitoringagent.rag.retrieval;

import lombok.Getter;
import lombok.Setter;

/**
 * 多路召回候选
 * 承载单条候选文档及其来源、各路分数和命中信号
 */
@Getter
@Setter
public class RetrievalCandidate {

    public enum Source {
        VECTOR,
        BM25,
        VECTOR_AND_BM25
    }

    private String id;
    private String content;
    private String metadata;

    private Source source;
    private float vectorScore;
    private float bm25Score;

    public static RetrievalCandidate vector(String id, String content, String metadata, float vectorScore) {
        RetrievalCandidate candidate = new RetrievalCandidate();
        candidate.id = id;
        candidate.content = content;
        candidate.metadata = metadata;
        candidate.source = Source.VECTOR;
        candidate.vectorScore = vectorScore;
        return candidate;
    }

    public static RetrievalCandidate bm25(String id, String content, String metadata, float bm25Score) {
        RetrievalCandidate candidate = new RetrievalCandidate();
        candidate.id = id;
        candidate.content = content;
        candidate.metadata = metadata;
        candidate.source = Source.BM25;
        candidate.bm25Score = bm25Score;
        return candidate;
    }

    /**
     * 标记该候选同时被向量和 BM25 命中。
     */
    public void mergeBm25(float bm25Score) {
        this.bm25Score = bm25Score;
        this.source = Source.VECTOR_AND_BM25;
    }

    public void mergeVector(float vectorScore) {
        this.vectorScore = vectorScore;
        this.source = Source.VECTOR_AND_BM25;
    }
}
