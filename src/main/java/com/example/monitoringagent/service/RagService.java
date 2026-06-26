package com.example.monitoringagent.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import com.example.monitoringagent.config.RagProperties;
import com.example.monitoringagent.dto.ragtest.RagQueryResult;
import com.example.monitoringagent.dto.ragtest.RagRetrievedDocument;
import com.example.monitoringagent.rag.query.DiagnosticQuery;
import com.example.monitoringagent.rag.query.DiagnosticQueryParser;
import com.example.monitoringagent.rag.retrieval.HybridRetrievalService;
import com.example.monitoringagent.rag.retrieval.RetrievalCandidate;
import io.reactivex.Flowable;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG (Retrieval-Augmented Generation) 服务
 * 结合向量检索和大语言模型生成答案
 */
@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private DashScopeReRankService dashScopeReRankService;

    @Autowired
    private DiagnosticQueryParser diagnosticQueryParser;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private RagProperties ragProperties;

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${rag.top-k:3}")
    private int topK;

    @Value("${rag.rerank.top-n:${rag.top-k:3}}")
    private int rerankTopN;

    @Value("${rag.model:qwen3-30b-a3b-thinking-2507}")
    private String model;

    private Generation generation;

    @PostConstruct
    public void init() {
        // 设置 API Key 和 Base URL
        Constants.apiKey = apiKey;
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        
        // 创建 Generation 实例
        generation = new Generation();
        
        logger.info("RAG 服务初始化完成，model: {}, topK: {}, rerankTopN: {}", model, topK, rerankTopN);
    }

    /**
     * 流式处理用户问题（不带历史消息）
     * 
     * @param question 用户问题
     * @param callback 流式回调接口
     */
    public void queryStream(String question, StreamCallback callback) {
        queryStream(question, new ArrayList<>(), callback);
    }

    /**
     * 流式处理用户问题（带历史消息）
     * 
     * @param question 用户问题
     * @param history 历史消息列表，格式：[{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
     * @param callback 流式回调接口
     */
    public void queryStream(String question, List<Map<String, String>> history, StreamCallback callback) {
        try {
            logger.info("收到 RAG 流式查询: {}", question);
            long t0 = System.currentTimeMillis();

            // 1. 查询预处理：提取错误码/异常类/组件/版本/环境
            DiagnosticQuery diagnosticQuery = diagnosticQueryParser.parse(question);
            long tParse = System.currentTimeMillis();

            // 2. 混合多路召回（向量 + BM25，BM25 不可用时自动降级为纯向量）
            List<RetrievalCandidate> candidates = hybridRetrievalService.retrieve(diagnosticQuery);
            long tRetrieve = System.currentTimeMillis();

            if (candidates.isEmpty()) {
                logger.warn("未找到相关文档");
                callback.onComplete("抱歉，我在知识库中没有找到相关信息来回答您的问题。", "");
                return;
            }

            // 3. 转换为重排输入
            List<VectorSearchService.SearchResult> retrieved = toSearchResults(candidates);

            // 4. 百炼重排（确保代码/版本匹配项优先）
            List<VectorSearchService.SearchResult> rerankedResults =
                    dashScopeReRankService.rerank(question, retrieved, rerankTopN);
            long tRerank = System.currentTimeMillis();

            callback.onSearchResults(rerankedResults);

            // 5. 构建上下文和诊断反思提示词
            String context = buildContext(rerankedResults);
            String prompt = buildPrompt(question, context, diagnosticQuery);

            logger.info("RAG 检索阶段耗时(ms): 解析={}, 召回={}, 重排={}, 检索合计={}",
                    tParse - t0, tRetrieve - tParse, tRerank - tRetrieve, tRerank - t0);

            // 6. 流式调用大语言模型（传入历史消息）
            generateAnswerStream(prompt, history, callback);

        } catch (Exception e) {
            logger.error("RAG 流式查询失败", e);
            callback.onError(e);
        }
    }

    /**
     * 将混合召回候选转换为重排服务可用的检索结果。
     */
    private List<VectorSearchService.SearchResult> toSearchResults(List<RetrievalCandidate> candidates) {
        List<VectorSearchService.SearchResult> results = new ArrayList<>();
        for (RetrievalCandidate candidate : candidates) {
            VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
            result.setId(candidate.getId());
            result.setContent(candidate.getContent());
            result.setMetadata(candidate.getMetadata());
            result.setScore(candidate.getVectorScore());
            results.add(result);
        }
        return results;
    }

    /**
     * 同步处理用户问题，便于批量评测场景复用完整 RAG 链路。
     */
    public RagQueryResult query(String question, List<Map<String, String>> history) {
        long startTime = System.currentTimeMillis();
        RagQueryResult queryResult = new RagQueryResult();
        List<RagRetrievedDocument> retrievedDocuments = new ArrayList<>();
        StringBuilder answerBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        long[] firstTokenAt = {-1};

        queryStream(question, history == null ? new ArrayList<>() : history, new StreamCallback() {
            @Override
            public void onSearchResults(List<VectorSearchService.SearchResult> results) {
                for (VectorSearchService.SearchResult result : results) {
                    RagRetrievedDocument document = new RagRetrievedDocument();
                    document.setId(result.getId());
                    document.setContent(result.getContent());
                    document.setScore(result.getScore());
                    document.setMetadata(result.getMetadata());
                    retrievedDocuments.add(document);
                }
            }

            @Override
            public void onReasoningChunk(String chunk) {
                reasoningBuilder.append(chunk);
            }

            @Override
            public void onContentChunk(String chunk) {
                if (firstTokenAt[0] < 0 && chunk != null && !chunk.isEmpty()) {
                    firstTokenAt[0] = System.currentTimeMillis();
                }
                answerBuilder.append(chunk);
            }

            @Override
            public void onComplete(String fullContent, String fullReasoning) {
                queryResult.setAnswer(fullContent);
                queryResult.setReasoningContent(fullReasoning);
            }

            @Override
            public void onError(Exception e) {
                throw new RuntimeException(e);
            }
        });

        if (queryResult.getAnswer() == null) {
            queryResult.setAnswer(answerBuilder.toString());
        }
        if (queryResult.getReasoningContent() == null) {
            queryResult.setReasoningContent(reasoningBuilder.toString());
        }
        queryResult.setSearchResults(retrievedDocuments);
        queryResult.setLatencyMs(System.currentTimeMillis() - startTime);
        queryResult.setFirstTokenMs(firstTokenAt[0] < 0 ? -1 : firstTokenAt[0] - startTime);
        logger.info("RAG 查询完成: 首字={}ms, 总时长={}ms", queryResult.getFirstTokenMs(), queryResult.getLatencyMs());
        return queryResult;
    }

    /**
     * 同步处理用户问题（不带历史消息）。
     */
    public RagQueryResult query(String question) {
        return query(question, new ArrayList<>());
    }

    /**
     * 构建上下文
     */
    private String buildContext(List<VectorSearchService.SearchResult> searchResults) {
        StringBuilder context = new StringBuilder();
        
        for (int i = 0; i < searchResults.size(); i++) {
            VectorSearchService.SearchResult result = searchResults.get(i);
            context.append("【参考资料 ").append(i + 1).append("】\n");
            context.append(result.getContent()).append("\n\n");
        }
        
        return context.toString();
    }

    /**
     * 构建提示词（支持诊断反思）
     */
    private String buildPrompt(String question, String context, DiagnosticQuery diagnosticQuery) {
        if (!ragProperties.getDiagnosis().isEnableReflection()) {
            return String.format(
                "你是一个专业的AI助手。请根据以下参考资料回答用户的问题。\n\n" +
                "参考资料：\n%s\n" +
                "用户问题：%s\n\n" +
                "请基于上述参考资料给出准确、详细的回答。如果参考资料中没有相关信息，请明确说明。",
                context, question
            );
        }

        StringBuilder signals = new StringBuilder();
        if (diagnosticQuery != null && diagnosticQuery.hasKeywordSignals()) {
            if (!diagnosticQuery.getErrorCodes().isEmpty()) {
                signals.append("- 错误码: ").append(diagnosticQuery.getErrorCodes()).append("\n");
            }
            if (!diagnosticQuery.getExceptions().isEmpty()) {
                signals.append("- 异常类: ").append(diagnosticQuery.getExceptions()).append("\n");
            }
            if (!diagnosticQuery.getComponents().isEmpty()) {
                signals.append("- 组件: ").append(diagnosticQuery.getComponents()).append("\n");
            }
            if (!diagnosticQuery.getVersions().isEmpty()) {
                signals.append("- 版本: ").append(diagnosticQuery.getVersions()).append("\n");
            }
            if (!diagnosticQuery.getEnvironments().isEmpty()) {
                signals.append("- 环境: ").append(diagnosticQuery.getEnvironments()).append("\n");
            }
        }

        return String.format(
            "你是一个专业的报错诊断助手。请根据以下参考资料诊断用户的报错问题。\n\n" +
            "参考资料：\n%s\n" +
            "用户问题：%s\n\n" +
            "已从用户问题中识别到的关键信息：\n%s\n" +
            "在回答前，请先进行诊断反思：\n" +
            "1. 判断每条参考资料是否真正匹配用户的错误码、异常类、组件。\n" +
            "2. 校验参考资料的版本与环境是否与用户一致；若不一致，必须明确指出差异和风险。\n" +
            "3. 不要把其他版本或其他组件的解决方案直接当作确定结论。\n\n" +
            "请按以下结构输出：\n" +
            "## 结论\n## 命中的关键信息\n## 可能原因\n## 排查步骤\n## 修复建议\n## 适用条件与风险\n## 仍需补充的信息\n\n" +
            "如果参考资料不足以诊断，请在“仍需补充的信息”中明确说明。",
            context, question, signals.length() == 0 ? "（未识别到明确的错误码/版本/环境信息）\n" : signals.toString()
        );
    }

    /**
     * 生成答案（流式）
     * 
     * @param prompt 当前问题的提示词
     * @param history 历史消息列表
     * @param callback 流式回调接口
     */
    private void generateAnswerStream(String prompt, List<Map<String, String>> history, StreamCallback callback) 
            throws NoApiKeyException, ApiException, InputRequiredException {
        
        // 构建消息列表：历史消息 + 当前问题
        List<Message> messages = new ArrayList<>();
        
        // 添加历史消息
        for (Map<String, String> historyMsg : history) {
            String role = historyMsg.get("role");
            String content = historyMsg.get("content");
            
            if ("user".equals(role)) {
                messages.add(Message.builder()
                        .role(Role.USER.getValue())
                        .content(content)
                        .build());
            } else if ("assistant".equals(role)) {
                messages.add(Message.builder()
                        .role(Role.ASSISTANT.getValue())
                        .content(content)
                        .build());
            }
        }
        
        // 添加当前用户问题
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build();
        messages.add(userMsg);
        
        logger.debug("发送给AI模型的消息数量: {}（包含 {} 条历史消息）", 
            messages.size(), history.size());

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .incrementalOutput(true)
                .resultFormat("message")
                .messages(messages)
                .build();

        logger.info("开始调用AI模型流式接口...");
        long llmCallStart = System.currentTimeMillis();

        Flowable<GenerationResult> result = generation.streamCall(param);

        StringBuilder reasoningContent = new StringBuilder();
        StringBuilder finalContent = new StringBuilder();
        long[] llmFirstTokenAt = {-1};

        logger.info("开始接收AI模型流式响应...");

        result.blockingForEach(message -> {
            if (message.getOutput() != null &&
                message.getOutput().getChoices() != null &&
                !message.getOutput().getChoices().isEmpty()) {

                // 获取消息内容
                // 注意：qwen3-30b-a3b-thinking-2507 模型会在 content 中返回完整内容
                // reasoning 部分可能需要通过特殊方式提取或者直接包含在 content 中
                String content = message.getOutput().getChoices().get(0).getMessage().getContent();

                if (content != null && !content.isEmpty()) {
                    if (llmFirstTokenAt[0] < 0) {
                        llmFirstTokenAt[0] = System.currentTimeMillis();
                        logger.info("LLM 首字到达，模型生成首字耗时(ms): {}", llmFirstTokenAt[0] - llmCallStart);
                    }
                    logger.debug("收到AI模型内容块: {}", content);

                    // 对于 thinking 模型，content 可能包含思考过程和最终答案
                    // 这里我们将所有内容都作为答案返回
                    finalContent.append(content);
                    callback.onContentChunk(content);

                    logger.debug("已调用 onContentChunk 回调");
                } else {
                    logger.debug("收到空内容块，跳过");
                }
            }
        });
        
        logger.info("AI模型流式响应完成，总内容长度: {}", finalContent.length());

        callback.onComplete(finalContent.toString(), reasoningContent.toString());
        logger.info("已调用 onComplete 回调");
    }

    /**
     * 流式回调接口
     */
    public interface StreamCallback {
        void onSearchResults(List<VectorSearchService.SearchResult> results);
        void onReasoningChunk(String chunk);
        void onContentChunk(String chunk);
        void onComplete(String fullContent, String fullReasoning);
        void onError(Exception e);
    }
}
