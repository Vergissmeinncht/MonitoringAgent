package com.example.monitoringagent.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.example.monitoringagent.dto.ragtest.GenerateTestSetRequest;
import com.example.monitoringagent.dto.ragtest.RagQueryResult;
import com.example.monitoringagent.dto.ragtest.RagRetrievedDocument;
import com.example.monitoringagent.dto.ragtest.RagTestCase;
import com.example.monitoringagent.dto.ragtest.RagTestResult;
import com.example.monitoringagent.dto.ragtest.RagTestRunResponse;
import com.example.monitoringagent.dto.ragtest.RagTestSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class RagTestService {

    private static final Logger logger = LoggerFactory.getLogger(RagTestService.class);
    private static final int DEFAULT_TEST_CASE_COUNT = 5;
    private static final int MAX_TEST_CASE_COUNT = 20;
    private static final double PASS_KEYWORD_RECALL_THRESHOLD = 0.6;
    private static final double PASS_ANSWER_RELEVANCY_THRESHOLD = 0.2;

    private final RagService ragService;
    private final ObjectMapper objectMapper;

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${rag.model:qwen3-30b-a3b-thinking-2507}")
    private String model;

    public RagTestService(RagService ragService, ObjectMapper objectMapper) {
        this.ragService = ragService;
        this.objectMapper = objectMapper;
    }

    public List<RagTestCase> generateTestSet(GenerateTestSetRequest request) {
        int count = normalizeCount(request.getCount());
        String prompt = buildGeneratePrompt(request, count);

        try {
            Generation generation = new Generation();
            Message userMessage = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .resultFormat("message")
                    .messages(List.of(userMessage))
                    .build();

            GenerationResult result = generation.call(param);
            String content = result.getOutput().getChoices().get(0).getMessage().getContent();
            List<RagTestCase> testCases = parseGeneratedTestCases(content);
            for (int i = 0; i < testCases.size(); i++) {
                RagTestCase testCase = testCases.get(i);
                if (isBlank(testCase.getId())) {
                    testCase.setId("case-" + (i + 1));
                }
                if (isBlank(testCase.getSource())) {
                    testCase.setSource("llm-generated");
                }
            }
            return testCases;
        } catch (Exception e) {
            logger.error("生成 RAG 测试集失败", e);
            throw new RuntimeException("生成测试集失败: " + e.getMessage(), e);
        }
    }

    public RagTestRunResponse runTest(List<RagTestCase> testCases) {
        if (testCases == null || testCases.isEmpty()) {
            throw new IllegalArgumentException("测试集不能为空");
        }
        List<RagTestResult> results = new ArrayList<>();
        for (RagTestCase testCase : testCases) {
            results.add(runSingleCase(testCase));
        }

        RagTestRunResponse response = new RagTestRunResponse();
        response.setResults(results);
        response.setSummary(buildSummary(results));
        return response;
    }

    public RagTestResult runSingleCase(RagTestCase testCase) {
        try {
            if (isBlank(testCase.getId())) {
                testCase.setId(UUID.randomUUID().toString());
            }
            RagQueryResult queryResult = ragService.query(testCase.getQuestion());
            return evaluateCase(testCase, queryResult);
        } catch (Exception e) {
            logger.error("执行 RAG 测试用例失败, caseId: {}", testCase.getId(), e);
            RagTestResult result = baseResult(testCase);
            result.setErrorMessage(e.getMessage());
            result.setPassed(false);
            return result;
        }
    }

    public RagTestResult evaluateCase(RagTestCase testCase, RagQueryResult queryResult) {
        RagTestResult result = baseResult(testCase);
        result.setActualAnswer(queryResult.getAnswer());
        result.setRetrievedDocuments(queryResult.getSearchResults());
        result.setLatencyMs(queryResult.getLatencyMs());
        result.setHitExpectedDoc(hitExpectedDoc(testCase.getExpectedDocIds(), queryResult.getSearchResults()));
        result.setKeywordRecall(calculateKeywordRecall(testCase.getExpectedKeywords(), queryResult.getAnswer()));
        result.setTopKRecall(calculateTopKRecall(testCase.getExpectedDocIds(), queryResult.getSearchResults()));
        result.setMrr(calculateMrr(testCase.getExpectedDocIds(), queryResult.getSearchResults()));
        result.setNdcg(calculateNdcg(testCase.getExpectedDocIds(), queryResult.getSearchResults()));
        result.setAnswerRelevancy(calculateAnswerRelevancy(testCase.getQuestion(), queryResult.getAnswer()));
        result.setPassed(isPassed(testCase, result));
        return result;
    }

    public String exportTestSet(List<RagTestCase> testCases, String format) {
        if (isCsv(format)) {
            StringBuilder csv = new StringBuilder("id,question,expectedAnswer,expectedKeywords,expectedDocIds,source\n");
            for (RagTestCase testCase : testCases) {
                csv.append(csvLine(
                        testCase.getId(),
                        testCase.getQuestion(),
                        testCase.getExpectedAnswer(),
                        String.join("|", safeList(testCase.getExpectedKeywords())),
                        String.join("|", safeList(testCase.getExpectedDocIds())),
                        testCase.getSource()
                ));
            }
            return csv.toString();
        }
        return toJson(testCases);
    }

    public String exportResults(List<RagTestResult> results, String format) {
        if (isCsv(format)) {
            StringBuilder csv = new StringBuilder("caseId,question,expectedAnswer,actualAnswer,retrievedDocIds,scores,hitExpectedDoc,keywordRecall,topKRecall,mrr,ndcg,answerRelevancy,latencyMs,passed,errorMessage\n");
            for (RagTestResult result : results) {
                csv.append(csvLine(
                        result.getCaseId(),
                        result.getQuestion(),
                        result.getExpectedAnswer(),
                        result.getActualAnswer(),
                        joinRetrievedDocIds(result.getRetrievedDocuments()),
                        joinRetrievedScores(result.getRetrievedDocuments()),
                        String.valueOf(result.isHitExpectedDoc()),
                        String.format(Locale.ROOT, "%.4f", result.getKeywordRecall()),
                        String.format(Locale.ROOT, "%.4f", result.getTopKRecall()),
                        String.format(Locale.ROOT, "%.4f", result.getMrr()),
                        String.format(Locale.ROOT, "%.4f", result.getNdcg()),
                        String.format(Locale.ROOT, "%.4f", result.getAnswerRelevancy()),
                        String.valueOf(result.getLatencyMs()),
                        String.valueOf(result.isPassed()),
                        result.getErrorMessage()
                ));
            }
            return csv.toString();
        }
        return toJson(results);
    }

    public byte[] toUtf8Bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private String buildGeneratePrompt(GenerateTestSetRequest request, int count) {
        String topic = isBlank(request.getTopic()) ? "当前知识库中的运维知识" : request.getTopic();
        String difficulty = isBlank(request.getDifficulty()) ? "中等" : request.getDifficulty();
        String reference = isBlank(request.getReference()) ? "请覆盖常见故障现象、原因分析、排查步骤和修复建议。" : request.getReference();
        return "请为 RAG 系统生成 " + count + " 条测试用例。\n"
                + "主题：" + topic + "\n"
                + "难度：" + difficulty + "\n"
                + "参考要求：" + reference + "\n"
                + "只返回 JSON 数组，不要返回 Markdown，不要包裹代码块。每个元素必须包含："
                + "id、question、expectedAnswer、expectedKeywords、expectedDocIds、source、metadata。"
                + "expectedKeywords 是字符串数组，expectedDocIds 不确定时返回空数组。";
    }

    private List<RagTestCase> parseGeneratedTestCases(String content) throws Exception {
        String json = extractJsonArray(content);
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    private String extractJsonArray(String content) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("大模型未返回有效 JSON 数组");
        }
        return content.substring(start, end + 1);
    }

    private RagTestResult baseResult(RagTestCase testCase) {
        RagTestResult result = new RagTestResult();
        result.setCaseId(testCase.getId());
        result.setQuestion(testCase.getQuestion());
        result.setExpectedAnswer(testCase.getExpectedAnswer());
        result.setExpectedKeywords(safeList(testCase.getExpectedKeywords()));
        result.setExpectedDocIds(safeList(testCase.getExpectedDocIds()));
        return result;
    }

    private boolean hitExpectedDoc(List<String> expectedDocIds, List<RagRetrievedDocument> retrievedDocuments) {
        List<String> expected = safeList(expectedDocIds);
        if (expected.isEmpty()) {
            return true;
        }
        for (RagRetrievedDocument document : retrievedDocuments) {
            if (document.getId() != null && expected.contains(document.getId())) {
                return true;
            }
        }
        return false;
    }

    private double calculateKeywordRecall(List<String> expectedKeywords, String answer) {
        List<String> keywords = safeList(expectedKeywords).stream()
                .filter(keyword -> !isBlank(keyword))
                .toList();
        if (keywords.isEmpty()) {
            return 1.0;
        }
        String normalizedAnswer = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        long matched = keywords.stream()
                .filter(keyword -> normalizedAnswer.contains(keyword.toLowerCase(Locale.ROOT)))
                .count();
        return (double) matched / keywords.size();
    }

    private double calculateTopKRecall(List<String> expectedDocIds, List<RagRetrievedDocument> retrievedDocuments) {
        List<String> expected = safeList(expectedDocIds).stream()
                .filter(id -> !isBlank(id))
                .toList();
        if (expected.isEmpty()) {
            return 1.0;
        }
        long hitCount = expected.stream()
                .filter(expectedId -> retrievedDocuments.stream()
                        .anyMatch(document -> expectedId.equals(document.getId())))
                .count();
        return (double) hitCount / expected.size();
    }

    private double calculateMrr(List<String> expectedDocIds, List<RagRetrievedDocument> retrievedDocuments) {
        List<String> expected = safeList(expectedDocIds);
        if (expected.isEmpty()) {
            return 1.0;
        }
        for (int i = 0; i < retrievedDocuments.size(); i++) {
            String documentId = retrievedDocuments.get(i).getId();
            if (documentId != null && expected.contains(documentId)) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    private double calculateNdcg(List<String> expectedDocIds, List<RagRetrievedDocument> retrievedDocuments) {
        List<String> expected = safeList(expectedDocIds).stream()
                .filter(id -> !isBlank(id))
                .toList();
        if (expected.isEmpty()) {
            return 1.0;
        }
        double dcg = 0;
        for (int i = 0; i < retrievedDocuments.size(); i++) {
            String documentId = retrievedDocuments.get(i).getId();
            if (documentId != null && expected.contains(documentId)) {
                dcg += 1 / log2(i + 2);
            }
        }
        double idcg = 0;
        int idealHits = Math.min(expected.size(), retrievedDocuments.size());
        for (int i = 0; i < idealHits; i++) {
            idcg += 1 / log2(i + 2);
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    private double calculateAnswerRelevancy(String question, String answer) {
        List<String> questionTokens = tokenize(question);
        List<String> answerTokens = tokenize(answer);
        if (questionTokens.isEmpty()) {
            return 1.0;
        }
        long matched = questionTokens.stream()
                .filter(answerTokens::contains)
                .count();
        return (double) matched / questionTokens.size();
    }

    private List<String> tokenize(String value) {
        if (isBlank(value)) {
            return new ArrayList<>();
        }
        List<String> tokens = new ArrayList<>();
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]+", " ");
        for (String token : normalized.split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    private boolean isPassed(RagTestCase testCase, RagTestResult result) {
        boolean keywordPassed = safeList(testCase.getExpectedKeywords()).isEmpty()
                || result.getKeywordRecall() >= PASS_KEYWORD_RECALL_THRESHOLD;
        boolean retrievalPassed = safeList(testCase.getExpectedDocIds()).isEmpty()
                || result.getTopKRecall() > 0;
        return result.isHitExpectedDoc()
                && retrievalPassed
                && keywordPassed
                && result.getAnswerRelevancy() >= PASS_ANSWER_RELEVANCY_THRESHOLD
                && isBlank(result.getErrorMessage());
    }

    private RagTestSummary buildSummary(List<RagTestResult> results) {
        RagTestSummary summary = new RagTestSummary();
        int total = results.size();
        int passed = (int) results.stream().filter(RagTestResult::isPassed).count();
        double keywordRecall = results.stream().mapToDouble(RagTestResult::getKeywordRecall).average().orElse(0);
        double topKRecall = results.stream().mapToDouble(RagTestResult::getTopKRecall).average().orElse(0);
        double mrr = results.stream().mapToDouble(RagTestResult::getMrr).average().orElse(0);
        double ndcg = results.stream().mapToDouble(RagTestResult::getNdcg).average().orElse(0);
        double answerRelevancy = results.stream().mapToDouble(RagTestResult::getAnswerRelevancy).average().orElse(0);
        double hitRate = results.stream().filter(RagTestResult::isHitExpectedDoc).count() / (double) total;
        double latency = results.stream().mapToLong(RagTestResult::getLatencyMs).average().orElse(0);

        summary.setTotal(total);
        summary.setPassed(passed);
        summary.setFailed(total - passed);
        summary.setPassRate(passed / (double) total);
        summary.setAverageKeywordRecall(keywordRecall);
        summary.setAverageTopKRecall(topKRecall);
        summary.setAverageMrr(mrr);
        summary.setAverageNdcg(ndcg);
        summary.setAverageAnswerRelevancy(answerRelevancy);
        summary.setExpectedDocHitRate(hitRate);
        summary.setAverageLatencyMs(latency);
        return summary;
    }

    private int normalizeCount(Integer count) {
        if (count == null || count <= 0) {
            return DEFAULT_TEST_CASE_COUNT;
        }
        return Math.min(count, MAX_TEST_CASE_COUNT);
    }

    private List<String> safeList(List<String> value) {
        return value == null ? new ArrayList<>() : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("JSON 导出失败: " + e.getMessage(), e);
        }
    }

    private boolean isCsv(String format) {
        return "csv".equalsIgnoreCase(format);
    }

    private String csvLine(String... values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(csvValue(values[i]));
        }
        return line.append('\n').toString();
    }

    private String csvValue(String value) {
        String safeValue = value == null ? "" : value;
        return "\"" + safeValue.replace("\"", "\"\"") + "\"";
    }

    private String joinRetrievedDocIds(List<RagRetrievedDocument> documents) {
        return documents.stream()
                .map(RagRetrievedDocument::getId)
                .filter(id -> id != null && !id.isBlank())
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    private String joinRetrievedScores(List<RagRetrievedDocument> documents) {
        return documents.stream()
                .map(document -> String.format(Locale.ROOT, "%.4f", document.getScore()))
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
