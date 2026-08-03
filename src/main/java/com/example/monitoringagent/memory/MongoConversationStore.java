package com.example.monitoringagent.memory;

import com.example.monitoringagent.config.MemoryProperties;
import com.example.monitoringagent.dto.chat.ChatMessage;
import com.example.monitoringagent.memory.document.ConversationMessageDoc;
import com.example.monitoringagent.memory.document.SessionDoc;
import com.example.monitoringagent.memory.repository.ConversationMessageRepository;
import com.example.monitoringagent.memory.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话持久化存储：把内存中的对话历史与 summary 落库到 MongoDB，
 * 并支持应用重启后按 sessionId 恢复。
 *
 * 所有方法在 {@code memory.persistence.enabled=false} 时退化为无操作，
 * 使系统可回退为纯内存模式。写入失败只记录日志，不影响主对话流程。
 */
@Service
public class MongoConversationStore {

    private static final Logger logger = LoggerFactory.getLogger(MongoConversationStore.class);

    private final ConversationMessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final MemoryProperties memoryProperties;

    public MongoConversationStore(ConversationMessageRepository messageRepository,
                                  SessionRepository sessionRepository,
                                  MemoryProperties memoryProperties) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.memoryProperties = memoryProperties;
    }

    private boolean enabled() {
        return memoryProperties.getPersistence().isEnabled();
    }

    /**
     * 首次创建会话时写入元数据。
     */
    public void createSession(String sessionId, String userId, long createTime) {
        if (!enabled()) {
            return;
        }
        try {
            if (sessionRepository.existsById(sessionId)) {
                return;
            }
            SessionDoc doc = new SessionDoc();
            doc.setSessionId(sessionId);
            doc.setUserId(userId);
            doc.setCreateTime(createTime);
            doc.setUpdatedAt(createTime);
            sessionRepository.save(doc);
        } catch (Exception e) {
            logger.warn("持久化会话元数据失败 - sessionId: {}", sessionId, e);
        }
    }

    /** 将显式用户身份绑定到会话；已有其他用户的会话不会被覆盖。 */
    public void bindUser(String sessionId, String userId) {
        if (!enabled() || userId == null || userId.isBlank()) {
            return;
        }
        try {
            sessionRepository.findById(sessionId).ifPresent(doc -> {
                if (doc.getUserId() == null || doc.getUserId().isBlank()) {
                    doc.setUserId(userId);
                    doc.setUpdatedAt(System.currentTimeMillis());
                    sessionRepository.save(doc);
                }
            });
        } catch (Exception e) {
            logger.warn("绑定会话用户失败 - sessionId: {}, userId: {}", sessionId, userId, e);
        }
    }

    /**
     * 追加一对消息（用户问题 + 助手回复），sequence 自增。
     */
    public void appendMessagePair(String sessionId, String userId, String userQuestion, String aiAnswer) {
        if (!enabled()) {
            return;
        }
        try {
            long base = messageRepository.countBySessionId(sessionId);
            long now = System.currentTimeMillis();
            saveMessage(sessionId, userId, base, "user", userQuestion, now);
            saveMessage(sessionId, userId, base + 1, "assistant", aiAnswer, now);
        } catch (Exception e) {
            logger.warn("持久化对话消息失败 - sessionId: {}", sessionId, e);
        }
    }

    private void saveMessage(String sessionId, String userId, long sequence, String role, String content, long createdAt) {
        ConversationMessageDoc doc = new ConversationMessageDoc();
        doc.setSessionId(sessionId);
        doc.setUserId(userId);
        doc.setSequence(sequence);
        doc.setRole(role);
        doc.setContent(content);
        doc.setCreatedAt(createdAt);
        messageRepository.save(doc);
    }

    /**
     * 更新会话的 summary 与压缩相关元数据。
     */
    public void updateSessionState(String sessionId, String summary, int compressionCount,
                                   long lastCompressedAt, int lastTokenEstimate) {
        if (!enabled()) {
            return;
        }
        try {
            SessionDoc doc = sessionRepository.findById(sessionId).orElseGet(() -> {
                SessionDoc created = new SessionDoc();
                created.setSessionId(sessionId);
                created.setCreateTime(System.currentTimeMillis());
                return created;
            });
            doc.setSummary(summary);
            doc.setCompressionCount(compressionCount);
            doc.setLastCompressedAt(lastCompressedAt);
            doc.setLastTokenEstimate(lastTokenEstimate);
            doc.setUpdatedAt(System.currentTimeMillis());
            sessionRepository.save(doc);
        } catch (Exception e) {
            logger.warn("更新会话状态失败 - sessionId: {}", sessionId, e);
        }
    }

    /**
     * 按 sessionId 从 MongoDB 恢复会话；不存在或未启用时返回 null。
     */
    public RestoredSession restore(String sessionId) {
        if (!enabled()) {
            return null;
        }
        try {
            SessionDoc meta = sessionRepository.findById(sessionId).orElse(null);
            if (meta == null) {
                return null;
            }
            List<ChatMessage> messages = new ArrayList<>();
            for (ConversationMessageDoc doc : messageRepository.findBySessionIdOrderBySequenceAsc(sessionId)) {
                ChatMessage message = ChatMessage.of(doc.getRole(), doc.getContent());
                message.setCreatedAt(doc.getCreatedAt());
                messages.add(message);
            }
            return new RestoredSession(meta, messages);
        } catch (Exception e) {
            logger.warn("恢复会话失败 - sessionId: {}", sessionId, e);
            return null;
        }
    }

    /**
     * 压缩后重写会话的历史消息：清空旧记录并按新的 recent 列表重新落库。
     */
    public void replaceMessages(String sessionId, String userId, List<ChatMessage> messages) {
        if (!enabled()) {
            return;
        }
        try {
            messageRepository.deleteBySessionId(sessionId);
            long seq = 0;
            for (ChatMessage message : messages) {
                saveMessage(sessionId, userId, seq++, message.getRole(), message.getContent(), message.getCreatedAt());
            }
        } catch (Exception e) {
            logger.warn("重写会话历史失败 - sessionId: {}", sessionId, e);
        }
    }

    /**
     * 清空会话的历史消息与 summary。
     */
    public void clear(String sessionId) {
        if (!enabled()) {
            return;
        }
        try {
            messageRepository.deleteBySessionId(sessionId);
            sessionRepository.findById(sessionId).ifPresent(doc -> {
                doc.setSummary(null);
                doc.setCompressionCount(0);
                doc.setLastCompressedAt(0);
                doc.setLastTokenEstimate(0);
                doc.setUpdatedAt(System.currentTimeMillis());
                sessionRepository.save(doc);
            });
        } catch (Exception e) {
            logger.warn("清空会话失败 - sessionId: {}", sessionId, e);
        }
    }
}
