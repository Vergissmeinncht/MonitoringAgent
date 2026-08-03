package com.example.monitoringagent.memory.document;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 单条对话消息（用户或助手），用于溯源。
 * 一个会话对应多条，按 sequence 递增排序。
 */
@Getter
@Setter
@Document(collection = "conversations")
@CompoundIndex(name = "session_seq_idx", def = "{'sessionId': 1, 'sequence': 1}")
public class ConversationMessageDoc {

    @Id
    private String id;

    private String sessionId;

    private String userId;

    private long sequence;

    private String role;

    private String content;

    private long createdAt;
}
