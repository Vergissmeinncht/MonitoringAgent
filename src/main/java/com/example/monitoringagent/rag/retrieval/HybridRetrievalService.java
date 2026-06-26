package com.example.monitoringagent.rag.retrieval;

import com.example.monitoringagent.config.RagProperties;
import com.example.monitoringagent.rag.query.DiagnosticQuery;
import com.example.monitoringagent.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合多路召回服务
 * 同时执行向量召回与 BM25 关键词召回，按 chunk ID 去重融合。
 * BM25 不可用时自动降级为纯向量召回。
 */
@Service
public class HybridRetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final VectorSearchService vectorSearchService;
    private final Bm25SearchService bm25SearchService;
    private final RagProperties ragProperties;

    public HybridRetrievalService(VectorSearchService vectorSearchService,
                                  Bm25SearchService bm25SearchService,
                                  RagProperties ragProperties) {
        this.vectorSearchService = vectorSearchService;
        this.bm25SearchService = bm25SearchService;
        this.ragProperties = ragProperties;
    }

    public List<RetrievalCandidate> retrieve(DiagnosticQuery diagnosticQuery) {
        RagProperties.Hybrid hybrid = ragProperties.getHybrid();
        String originalQuery = diagnosticQuery.getOriginalQuery();

        // 1. 向量召回
        Map<String, RetrievalCandidate> merged = new LinkedHashMap<>();
        List<VectorSearchService.SearchResult> vectorResults =
                vectorSearchService.searchSimilarDocuments(originalQuery, hybrid.getVectorTopK());
        for (VectorSearchService.SearchResult result : vectorResults) {
            merged.put(result.getId(), RetrievalCandidate.vector(
                    result.getId(), result.getContent(), result.getMetadata(), result.getScore()));
        }

        // 2. BM25 关键词召回（有诊断信号或原始 query 时执行；失败自动降级）
        String keywordQuery = diagnosticQuery.hasKeywordSignals()
                ? diagnosticQuery.toKeywordQuery()
                : originalQuery;
        List<RetrievalCandidate> bm25Results = bm25SearchService.search(keywordQuery, hybrid.getBm25TopK());
        if (bm25Results.isEmpty()) {
            logger.warn("BM25 未召回任何文档，本次为纯向量召回");
        }

        // 3. 融合去重
        for (RetrievalCandidate bm25 : bm25Results) {
            RetrievalCandidate existing = merged.get(bm25.getId());
            if (existing != null) {
                existing.mergeBm25(bm25.getBm25Score());
            } else {
                merged.put(bm25.getId(), bm25);
            }
        }

        List<RetrievalCandidate> candidates = new ArrayList<>(merged.values());
        int limit = Math.min(candidates.size(), hybrid.getCandidateLimit());
        List<RetrievalCandidate> limited = new ArrayList<>(candidates.subList(0, limit));
        logger.info("混合召回完成: 向量={}, BM25={}, 融合去重后={}, 进入重排={}",
                vectorResults.size(), bm25Results.size(), candidates.size(), limited.size());
        return limited;
    }
}
