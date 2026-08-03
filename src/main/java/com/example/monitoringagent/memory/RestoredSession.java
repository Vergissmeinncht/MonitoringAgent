package com.example.monitoringagent.memory;

import com.example.monitoringagent.dto.chat.ChatMessage;
import com.example.monitoringagent.memory.document.SessionDoc;
import lombok.Getter;

import java.util.List;

/**
 * 从 MongoDB 恢复出的会话快照：元数据 + 有序历史消息。
 */
@Getter
public class RestoredSession {

    private final SessionDoc meta;
    private final List<ChatMessage> messages;

    public RestoredSession(SessionDoc meta, List<ChatMessage> messages) {
        this.meta = meta;
        this.messages = messages;
    }
}
