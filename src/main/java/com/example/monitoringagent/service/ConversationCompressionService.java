package com.example.monitoringagent.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.monitoringagent.agent.tool.TokenEstimatorTool;
import com.example.monitoringagent.config.ChatMemoryProperties;
import com.example.monitoringagent.dto.chat.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ConversationCompressionService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationCompressionService.class);

    private final ChatMemoryProperties properties;
    private final TokenEstimatorTool tokenEstimator;
    private final ChatService chatService;

    public ConversationCompressionService(ChatMemoryProperties properties, TokenEstimatorTool tokenEstimator, ChatService chatService) {
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
        this.chatService = chatService;
    }

    public boolean shouldCompress(String summary, List<ChatMessage> messages, String currentQuestion) {
        if (!properties.isEnabled()) {
            return false;
        }
        int estimatedTokens = tokenEstimator.estimateConversation(summary, messages, currentQuestion);
        int threshold = (int) Math.ceil(properties.getMaxContextTokens() * properties.getCompressionThresholdRatio());
        return estimatedTokens + properties.getReservedOutputTokens() >= threshold;
    }

    public CompressionResult compress(String summary, List<ChatMessage> messages, String currentQuestion) {
        int estimatedTokensBefore = tokenEstimator.estimateConversation(summary, messages, currentQuestion);
        int recentMessageCount = Math.max(0, properties.getMinRecentMessagePairs() * 2);
        if (messages.size() <= recentMessageCount) {
            return CompressionResult.skipped(messages, summary, estimatedTokensBefore);
        }

        List<ChatMessage> messagesToCompress = new ArrayList<>(messages.subList(0, messages.size() - recentMessageCount));
        List<ChatMessage> recentMessages = new ArrayList<>(messages.subList(messages.size() - recentMessageCount, messages.size()));
        String nextSummary = summarize(summary, messagesToCompress);
        int estimatedTokensAfter = tokenEstimator.estimateConversation(nextSummary, recentMessages, currentQuestion);

        CompressionResult result = new CompressionResult();
        result.setSummary(nextSummary);
        result.setRecentMessages(recentMessages);
        result.setCompressed(true);
        result.setEstimatedTokensBefore(estimatedTokensBefore);
        result.setEstimatedTokensAfter(estimatedTokensAfter);
        return result;
    }

    public int estimateConversation(String summary, List<ChatMessage> messages, String currentQuestion) {
        return tokenEstimator.estimateConversation(summary, messages, currentQuestion);
    }

    private String summarize(String existingSummary, List<ChatMessage> messagesToCompress) {
        try {
            logger.info("开始压缩对话上下文，待压缩消息数: {}", messagesToCompress.size());
            DashScopeApi dashScopeApi = chatService.createDashScopeApi();
            DashScopeChatModel summaryModel = chatService.createSummaryChatModel(dashScopeApi);
            String prompt = buildSummaryPrompt(existingSummary, messagesToCompress);
            String summary = summaryModel.call(prompt);
            if (summary == null || summary.isBlank()) {
                throw new IllegalStateException("摘要模型返回空内容");
            }
            return summary.trim();
        } catch (Exception e) {
            logger.error("压缩对话上下文失败", e);
            throw new RuntimeException("压缩对话上下文失败: " + e.getMessage(), e);
        }
    }

    private String buildSummaryPrompt(String existingSummary, List<ChatMessage> messagesToCompress) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请把以下对话历史压缩成供后续对话使用的长期记忆摘要。\n");
        prompt.append("要求：\n");
        prompt.append("1. 保留用户目标、约束、关键事实、已做决定、未解决问题。\n");
        prompt.append("2. 保留工具查询得到的重要结论。\n");
        prompt.append("3. 删除寒暄、重复内容和无关细节。\n");
        prompt.append("4. 不要编造没有出现过的信息。\n");
        prompt.append("5. 用中文分点输出，控制在 ").append(properties.getSummaryMaxTokens()).append(" token 以内。\n\n");
        if (existingSummary != null && !existingSummary.isBlank()) {
            prompt.append("已有长期摘要：\n").append(existingSummary).append("\n\n");
        }
        prompt.append("本次需要压缩的对话：\n");
        for (ChatMessage message : messagesToCompress) {
            String role = "user".equals(message.getRole()) ? "用户" : "助手";
            prompt.append(role).append(": ").append(message.getContent()).append("\n");
        }
        return prompt.toString();
    }

    public static class CompressionResult {
        private String summary;
        private List<ChatMessage> recentMessages = new ArrayList<>();
        private boolean compressed;
        private int estimatedTokensBefore;
        private int estimatedTokensAfter;

        public static CompressionResult skipped(List<ChatMessage> messages, String summary, int estimatedTokens) {
            CompressionResult result = new CompressionResult();
            result.setSummary(summary);
            result.setRecentMessages(new ArrayList<>(messages));
            result.setCompressed(false);
            result.setEstimatedTokensBefore(estimatedTokens);
            result.setEstimatedTokensAfter(estimatedTokens);
            return result;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public List<ChatMessage> getRecentMessages() {
            return recentMessages;
        }

        public void setRecentMessages(List<ChatMessage> recentMessages) {
            this.recentMessages = recentMessages;
        }

        public boolean isCompressed() {
            return compressed;
        }

        public void setCompressed(boolean compressed) {
            this.compressed = compressed;
        }

        public int getEstimatedTokensBefore() {
            return estimatedTokensBefore;
        }

        public void setEstimatedTokensBefore(int estimatedTokensBefore) {
            this.estimatedTokensBefore = estimatedTokensBefore;
        }

        public int getEstimatedTokensAfter() {
            return estimatedTokensAfter;
        }

        public void setEstimatedTokensAfter(int estimatedTokensAfter) {
            this.estimatedTokensAfter = estimatedTokensAfter;
        }
    }
}
