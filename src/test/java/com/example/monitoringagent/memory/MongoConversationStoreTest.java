package com.example.monitoringagent.memory;

import com.example.monitoringagent.config.MemoryProperties;
import com.example.monitoringagent.dto.chat.ChatMessage;
import com.example.monitoringagent.memory.repository.ConversationMessageRepository;
import com.example.monitoringagent.memory.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证会话持久化往返：写入 -> 恢复 -> 压缩重写 -> 清空。
 * 仅加载 MongoDB 切片，不启动 MCP/Milvus/Agent。
 * 需要本地 MongoDB 运行于 mongodb://localhost:27017。
 */
@DataMongoTest
class MongoConversationStoreTest {

    @Autowired
    private ConversationMessageRepository messageRepository;

    @Autowired
    private SessionRepository sessionRepository;

    private MongoConversationStore store;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        sessionRepository.deleteAll();
        MemoryProperties props = new MemoryProperties();
        props.getPersistence().setEnabled(true);
        store = new MongoConversationStore(messageRepository, sessionRepository, props);
    }

    @Test
    void persistsAndRestoresConversation() {
        String sessionId = "test-session-1";
        store.createSession(sessionId, null, System.currentTimeMillis());
        store.appendMessagePair(sessionId, null, "第一个问题", "第一个回答");
        store.appendMessagePair(sessionId, null, "第二个问题", "第二个回答");

        RestoredSession restored = store.restore(sessionId);

        assertThat(restored).isNotNull();
        List<ChatMessage> messages = restored.getMessages();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).getRole()).isEqualTo("user");
        assertThat(messages.get(0).getContent()).isEqualTo("第一个问题");
        assertThat(messages.get(1).getRole()).isEqualTo("assistant");
        assertThat(messages.get(3).getContent()).isEqualTo("第二个回答");
    }

    @Test
    void updatesAndRestoresSummary() {
        String sessionId = "test-session-2";
        store.createSession(sessionId, null, System.currentTimeMillis());
        store.updateSessionState(sessionId, "长期摘要内容", 2, 123L, 4567);

        RestoredSession restored = store.restore(sessionId);

        assertThat(restored).isNotNull();
        assertThat(restored.getMeta().getSummary()).isEqualTo("长期摘要内容");
        assertThat(restored.getMeta().getCompressionCount()).isEqualTo(2);
        assertThat(restored.getMeta().getLastCompressedAt()).isEqualTo(123L);
        assertThat(restored.getMeta().getLastTokenEstimate()).isEqualTo(4567);
    }

    @Test
    void replaceMessagesRewritesHistoryInOrder() {
        String sessionId = "test-session-3";
        store.createSession(sessionId, null, System.currentTimeMillis());
        store.appendMessagePair(sessionId, null, "旧问题", "旧回答");

        List<ChatMessage> compacted = List.of(
                ChatMessage.of("user", "保留的问题"),
                ChatMessage.of("assistant", "保留的回答"));
        store.replaceMessages(sessionId, null, compacted);

        RestoredSession restored = store.restore(sessionId);
        assertThat(restored).isNotNull();
        assertThat(restored.getMessages()).hasSize(2);
        assertThat(restored.getMessages().get(0).getContent()).isEqualTo("保留的问题");
    }

    @Test
    void clearRemovesHistoryAndSummary() {
        String sessionId = "test-session-4";
        store.createSession(sessionId, null, System.currentTimeMillis());
        store.appendMessagePair(sessionId, null, "问题", "回答");
        store.updateSessionState(sessionId, "摘要", 1, 1L, 100);

        store.clear(sessionId);

        RestoredSession restored = store.restore(sessionId);
        assertThat(restored).isNotNull();
        assertThat(restored.getMessages()).isEmpty();
        assertThat(restored.getMeta().getSummary()).isNull();
        assertThat(restored.getMeta().getCompressionCount()).isZero();
    }

    @Test
    void disabledPersistenceIsNoOp() {
        MemoryProperties disabled = new MemoryProperties();
        disabled.getPersistence().setEnabled(false);
        MongoConversationStore offStore =
                new MongoConversationStore(messageRepository, sessionRepository, disabled);

        offStore.createSession("off-session", null, System.currentTimeMillis());
        offStore.appendMessagePair("off-session", null, "q", "a");

        assertThat(offStore.restore("off-session")).isNull();
        assertThat(messageRepository.count()).isZero();
        assertThat(sessionRepository.count()).isZero();
    }
}
