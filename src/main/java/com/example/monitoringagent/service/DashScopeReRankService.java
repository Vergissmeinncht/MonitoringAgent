package com.example.monitoringagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashScopeReRankService {

    private static final Logger logger = LoggerFactory.getLogger(DashScopeReRankService.class);
    private static final String RERANK_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${rag.rerank.model:gte-rerank-v2}")
    private String model;

    public DashScopeReRankService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    public List<VectorSearchService.SearchResult> rerank(String query, List<VectorSearchService.SearchResult> searchResults, int topN) {
        if (searchResults == null || searchResults.isEmpty()) {
            return new ArrayList<>();
        }
        if (isBlank(apiKey)) {
            throw new IllegalStateException("DashScope API Key 不能为空，无法调用百炼重排模型");
        }

        try {
            List<String> documents = searchResults.stream()
                    .map(VectorSearchService.SearchResult::getContent)
                    .map(content -> content == null ? "" : content)
                    .toList();
            int normalizedTopN = Math.min(Math.max(topN, 1), documents.size());
            String requestBody = buildRequestBody(query, documents, normalizedTopN);
            Request request = new Request.Builder()
                    .url(RERANK_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            logger.info("开始调用百炼重排模型, model: {}, documents: {}, topN: {}", model, documents.size(), normalizedTopN);
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new RuntimeException("百炼重排请求失败: HTTP " + response.code() + ", " + responseBody);
                }
                List<VectorSearchService.SearchResult> rerankedResults = parseResponse(responseBody, searchResults);
                if (rerankedResults.isEmpty()) {
                    throw new RuntimeException("百炼重排返回空结果");
                }
                logger.info("百炼重排完成, 输出文档数: {}", rerankedResults.size());
                return rerankedResults;
            }
        } catch (Exception e) {
            logger.error("百炼重排失败", e);
            throw new RuntimeException("百炼重排失败: " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(String query, List<String> documents, int topN) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);

        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        body.put("input", input);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("top_n", topN);
        parameters.put("return_documents", false);
        body.put("parameters", parameters);

        return objectMapper.writeValueAsString(body);
    }

    private List<VectorSearchService.SearchResult> parseResponse(String responseBody, List<VectorSearchService.SearchResult> originalResults) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode resultsNode = root.path("output").path("results");
        if (!resultsNode.isArray()) {
            throw new RuntimeException("百炼重排响应缺少 output.results: " + responseBody);
        }

        List<VectorSearchService.SearchResult> rerankedResults = new ArrayList<>();
        for (JsonNode item : resultsNode) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= originalResults.size()) {
                throw new RuntimeException("百炼重排返回非法文档下标: " + index);
            }
            VectorSearchService.SearchResult original = originalResults.get(index);
            VectorSearchService.SearchResult reranked = new VectorSearchService.SearchResult();
            reranked.setId(original.getId());
            reranked.setContent(original.getContent());
            reranked.setMetadata(original.getMetadata());
            reranked.setScore((float) item.path("relevance_score").asDouble());
            rerankedResults.add(reranked);
        }
        return rerankedResults;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
