package com.example.monitoringagent.agent.tool;

import com.example.monitoringagent.service.VectorSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 */
@Component
public class InternalDocsTools {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";
    
    private final VectorSearchService vectorSearchService;
    private final com.example.monitoringagent.rag.query.DiagnosticQueryParser diagnosticQueryParser;
    private final com.example.monitoringagent.rag.retrieval.HybridRetrievalService hybridRetrievalService;
    private final com.example.monitoringagent.service.DashScopeReRankService dashScopeReRankService;

    @Value("${rag.rerank.top-n:${rag.top-k:3}}")
    private int rerankTopN = 3; // 默认值

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造函数注入依赖
     */
    @Autowired
    public InternalDocsTools(VectorSearchService vectorSearchService,
                             com.example.monitoringagent.rag.query.DiagnosticQueryParser diagnosticQueryParser,
                             com.example.monitoringagent.rag.retrieval.HybridRetrievalService hybridRetrievalService,
                             com.example.monitoringagent.service.DashScopeReRankService dashScopeReRankService) {
        this.vectorSearchService = vectorSearchService;
        this.diagnosticQueryParser = diagnosticQueryParser;
        this.hybridRetrievalService = hybridRetrievalService;
        this.dashScopeReRankService = dashScopeReRankService;
    }

    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for")
            String query) {


        try {
            // 1. 查询预处理 + 混合多路召回
            com.example.monitoringagent.rag.query.DiagnosticQuery diagnosticQuery =
                    diagnosticQueryParser.parse(query);
            List<com.example.monitoringagent.rag.retrieval.RetrievalCandidate> candidates =
                    hybridRetrievalService.retrieve(diagnosticQuery);

            if (candidates.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }

            // 2. 转换为重排输入
            List<VectorSearchService.SearchResult> retrieved = new java.util.ArrayList<>();
            for (com.example.monitoringagent.rag.retrieval.RetrievalCandidate candidate : candidates) {
                VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
                result.setId(candidate.getId());
                result.setContent(candidate.getContent());
                result.setMetadata(candidate.getMetadata());
                result.setScore(candidate.getVectorScore());
                retrieved.add(result);
            }

            // 3. 百炼重排
            List<VectorSearchService.SearchResult> rerankedResults =
                    dashScopeReRankService.rerank(query, retrieved, rerankTopN);

            // 将搜索结果转换为 JSON 格式
            String resultJson = objectMapper.writeValueAsString(rerankedResults);


            return resultJson;

        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}",
                    e.getMessage());
        }
    }
}
