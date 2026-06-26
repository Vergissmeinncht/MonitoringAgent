package com.example.monitoringagent.rag.retrieval;

import com.example.monitoringagent.config.RagProperties;
import com.example.monitoringagent.rag.query.DiagnosticQuery;
import com.example.monitoringagent.service.VectorSearchService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 混合多路召回服务
 * 同时执行向量召回与 BM25 关键词召回，按 chunk ID 去重融合。
 * 向量召回与 BM25 召回并发执行；BM25 不可用时自动降级为纯向量召回。
 */
@Service
public class HybridRetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final VectorSearchService vectorSearchService;
    private final Bm25SearchService bm25SearchService;
    private final RagProperties ragProperties;

    /** 两路召回并发执行的线程池（向量 + BM25），固定 2 线程足够。 */
    private final ExecutorService retrievalExecutor =
            Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "hybrid-retrieval");
                thread.setDaemon(true);
                return thread;
            });

    public HybridRetrievalService(VectorSearchService vectorSearchService,
                                  Bm25SearchService bm25SearchService,
                                  RagProperties ragProperties) {
        this.vectorSearchService = vectorSearchService;
        this.bm25SearchService = bm25SearchService;
        this.ragProperties = ragProperties;
    }

    @PreDestroy
    public void shutdown() {
        retrievalExecutor.shutdown();
    }

    public List<RetrievalCandidate> retrieve(DiagnosticQuery diagnosticQuery) {
        RagProperties.Hybrid hybrid = ragProperties.getHybrid();
        String originalQuery = diagnosticQuery.getOriginalQuery();

        // BM25 关键词：有诊断信号时用结构化关键词，否则用原始 query
        String keywordQuery = diagnosticQuery.hasKeywordSignals()
                ? diagnosticQuery.toKeywordQuery()
                : originalQuery;

        // 1+2. 向量召回与 BM25 召回并发执行（BM25 失败自动降级）
        CompletableFuture<List<VectorSearchService.SearchResult>> vectorFuture =
                CompletableFuture.supplyAsync(
                        () -> vectorSearchService.searchSimilarDocuments(originalQuery, hybrid.getVectorTopK()),
                        retrievalExecutor);
        CompletableFuture<List<RetrievalCandidate>> bm25Future =
                CompletableFuture.supplyAsync(
                        () -> bm25SearchService.search(keywordQuery, hybrid.getBm25TopK()),
                        retrievalExecutor);

        List<VectorSearchService.SearchResult> vectorResults = vectorFuture.join();
        List<RetrievalCandidate> bm25Results = bm25Future.join();

        Map<String, RetrievalCandidate> merged = new LinkedHashMap<>();
        for (VectorSearchService.SearchResult result : vectorResults) {
            merged.put(result.getId(), RetrievalCandidate.vector(
                    result.getId(), result.getContent(), result.getMetadata(), result.getScore()));
        }
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
