package com.example.monitoringagent.memory;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.example.monitoringagent.config.MemoryProperties;
import com.example.monitoringagent.memory.document.UserMemoryDoc;
import com.example.monitoringagent.memory.repository.UserMemoryRepository;
import com.example.monitoringagent.service.ChatService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class LongTermMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(LongTermMemoryService.class);
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(api[-_ ]?key|secret|password|passwd|token|cookie|private[-_ ]?key|access[-_ ]?key)\\s*(?:是|为|[:=：])");
    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^\\p{L}\\p{N}_.-]+");

    private final UserMemoryRepository repository;
    private final MemoryProperties properties;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;
    private final ExecutorService extractionExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(100), r -> {
                Thread thread = new Thread(r, "long-term-memory-extractor");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());

    public LongTermMemoryService(UserMemoryRepository repository,
                                 MemoryProperties properties,
                                 ChatService chatService,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    public List<UserMemoryDoc> list(String userId) {
        if (!enabled() || blank(userId)) {
            return List.of();
        }
        try {
            return repository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, MemoryStatus.ACTIVE);
        } catch (Exception e) {
            logger.warn("查询长期记忆失败，已降级为空结果 - userId: {}", userId, e);
            return List.of();
        }
    }

    public List<String> retrieve(String userId, String question) {
        if (!enabled() || blank(userId) || blank(question)) {
            return List.of();
        }
        try {
            Set<String> queryTokens = tokenize(question);
            long now = System.currentTimeMillis();
            List<UserMemoryDoc> candidates = new ArrayList<>(repository
                    .findByUserIdAndStatusOrderByUpdatedAtDesc(userId, MemoryStatus.ACTIVE));
            candidates.sort(Comparator
                    .comparingDouble((UserMemoryDoc memory) -> relevance(memory, queryTokens)).reversed()
                    .thenComparing(Comparator.comparingLong(UserMemoryDoc::getUpdatedAt).reversed()));

            int topK = Math.max(0, properties.getLongTerm().getRetrievalTopK());
            List<String> result = new ArrayList<>();
            for (UserMemoryDoc memory : candidates) {
                if (result.size() >= topK) {
                    break;
                }
                // 没有词面命中时，仅保留少量最新约束，避免无关记忆污染 Prompt。
                boolean related = queryTokens.stream().anyMatch(token -> tokenize(memory.getContent()).contains(token));
                if (!related && memory.getType() != MemoryType.CONSTRAINT) {
                    continue;
                }
                result.add("[" + memory.getType().name() + "] " + memory.getContent());
                memory.setLastAccessedAt(now);
                repository.save(memory);
            }
            return result;
        } catch (Exception e) {
            logger.warn("召回长期记忆失败，继续使用会话上下文 - userId: {}", userId, e);
            return List.of();
        }
    }

    public void extractAsync(String userId, String sessionId, String userMessage, String assistantAnswer) {
        if (!enabled() || !properties.getLongTerm().isExtractionEnabled() || blank(userId) || blank(userMessage)) {
            return;
        }
        extractionExecutor.execute(() -> extractAndSave(userId, sessionId, userMessage, assistantAnswer));
    }

    void extractAndSave(String userId, String sessionId, String userMessage, String assistantAnswer) {
        try {
            DashScopeChatModel model = chatService.createSummaryChatModel(chatService.createDashScopeApi());
            String raw = model.call(buildExtractionPrompt(userMessage, assistantAnswer));
            for (MemoryCandidate candidate : parseCandidates(raw)) {
                saveCandidate(userId, sessionId, candidate);
            }
            enforceLimit(userId);
        } catch (Exception e) {
            logger.warn("长期记忆抽取失败，不影响本轮对话 - userId: {}, sessionId: {}", userId, sessionId, e);
        }
    }

    public boolean delete(String userId, String memoryId) {
        if (!enabled() || blank(userId) || blank(memoryId)) {
            return false;
        }
        try {
            return repository.findByIdAndUserId(memoryId, userId).map(memory -> {
                memory.setStatus(MemoryStatus.DELETED);
                memory.setUpdatedAt(System.currentTimeMillis());
                repository.save(memory);
                return true;
            }).orElse(false);
        } catch (Exception e) {
            logger.warn("删除长期记忆失败 - userId: {}, memoryId: {}", userId, memoryId, e);
            return false;
        }
    }

    public int clear(String userId) {
        List<UserMemoryDoc> memories = list(userId);
        long now = System.currentTimeMillis();
        memories.forEach(memory -> {
            memory.setStatus(MemoryStatus.DELETED);
            memory.setUpdatedAt(now);
        });
        if (!memories.isEmpty()) {
            repository.saveAll(memories);
        }
        return memories.size();
    }

    private void saveCandidate(String userId, String sessionId, MemoryCandidate candidate) {
        if (!valid(candidate)) {
            return;
        }
        MemoryType type;
        try {
            type = MemoryType.valueOf(candidate.type().trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return;
        }
        String content = candidate.content().trim();
        String normalized = normalize(content);
        String memoryKey = normalize(candidate.memoryKey());
        if (memoryKey.isBlank()) {
            memoryKey = normalized;
        }
        long now = System.currentTimeMillis();

        var duplicate = repository.findByUserIdAndTypeAndNormalizedContent(userId, type, normalized);
        if (duplicate.isPresent()) {
            UserMemoryDoc existing = duplicate.get();
            existing.setStatus(MemoryStatus.ACTIVE);
            existing.setConfidence(Math.max(existing.getConfidence(), candidate.confidence()));
            existing.setSourceSessionId(sessionId);
            existing.setUpdatedAt(now);
            repository.save(existing);
            return;
        }

        for (UserMemoryDoc old : repository.findByUserIdAndTypeAndMemoryKeyAndStatus(
                userId, type, memoryKey, MemoryStatus.ACTIVE)) {
            old.setStatus(MemoryStatus.SUPERSEDED);
            old.setUpdatedAt(now);
            repository.save(old);
        }

        UserMemoryDoc memory = new UserMemoryDoc();
        memory.setUserId(userId);
        memory.setType(type);
        memory.setContent(content);
        memory.setNormalizedContent(normalized);
        memory.setMemoryKey(memoryKey);
        memory.setSourceSessionId(sessionId);
        memory.setConfidence(candidate.confidence());
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        try {
            repository.save(memory);
        } catch (DuplicateKeyException ignored) {
            logger.debug("并发写入产生重复长期记忆，已忽略 - userId: {}", userId);
        }
    }

    private List<MemoryCandidate> parseCandidates(String raw) throws Exception {
        if (blank(raw)) {
            return List.of();
        }
        String json = raw.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                json = json.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return objectMapper.readValue(json, new TypeReference<List<MemoryCandidate>>() {});
    }

    private boolean valid(MemoryCandidate candidate) {
        return candidate != null
                && !blank(candidate.type())
                && !blank(candidate.content())
                && candidate.content().length() <= MAX_CONTENT_LENGTH
                && candidate.confidence() >= properties.getLongTerm().getConfidenceThreshold()
                && !SENSITIVE_PATTERN.matcher(candidate.content()).find();
    }

    private String buildExtractionPrompt(String userMessage, String assistantAnswer) {
        return """
                你是长期记忆抽取器。只提取用户明确表达、对未来对话仍有价值且相对稳定的信息。
                允许类型：PREFERENCE、ENVIRONMENT、PROJECT_FACT、INCIDENT、CONSTRAINT。
                不得保存密码、Token、API Key、Cookie、私钥等敏感信息；不得把助手推测当作用户事实。
                memoryKey 是同一主题的稳定英文或中文短键，例如 production_java_version。
                如果没有值得保存的信息，返回 []。只返回 JSON 数组，不要 Markdown。
                格式：[{"type":"ENVIRONMENT","content":"生产环境使用 Java 17","memoryKey":"production_java_version","confidence":0.95}]

                用户消息：%s
                助手回答（仅用于理解上下文，不可作为用户事实）：%s
                """.formatted(limit(userMessage, 4000), limit(assistantAnswer, 2000));
    }

    private void enforceLimit(String userId) {
        int max = Math.max(1, properties.getLongTerm().getMaxMemoriesPerUser());
        List<UserMemoryDoc> active = repository
                .findByUserIdAndStatusOrderByUpdatedAtDesc(userId, MemoryStatus.ACTIVE);
        if (active.size() <= max) {
            return;
        }
        active.subList(max, active.size()).forEach(memory -> {
            memory.setStatus(MemoryStatus.SUPERSEDED);
            memory.setUpdatedAt(System.currentTimeMillis());
            repository.save(memory);
        });
    }

    private double relevance(UserMemoryDoc memory, Set<String> queryTokens) {
        Set<String> memoryTokens = tokenize(memory.getContent());
        long matches = queryTokens.stream().filter(memoryTokens::contains).count();
        return matches + memory.getConfidence() * 0.01;
    }

    private Set<String> tokenize(String value) {
        Set<String> tokens = new HashSet<>();
        String normalized = value.toLowerCase(Locale.ROOT);
        Arrays.stream(TOKEN_SPLITTER.split(normalized))
                .filter(token -> token.length() >= 2)
                .forEach(tokens::add);
        addCjkBigrams(normalized, tokens);
        return tokens;
    }

    private void addCjkBigrams(String value, Set<String> tokens) {
        StringBuilder run = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN) {
                run.appendCodePoint(codePoint);
            } else {
                flushCjkRun(run, tokens);
            }
            offset += Character.charCount(codePoint);
        }
        flushCjkRun(run, tokens);
    }

    private void flushCjkRun(StringBuilder run, Set<String> tokens) {
        for (int i = 0; i + 1 < run.length(); i++) {
            tokens.add(run.substring(i, i + 2));
        }
        run.setLength(0);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max);
    }

    private boolean enabled() {
        return properties.getLongTerm().isEnabled();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @PreDestroy
    public void shutdown() {
        extractionExecutor.shutdown();
    }

    public record MemoryCandidate(String type, String content, String memoryKey, double confidence) {}
}
