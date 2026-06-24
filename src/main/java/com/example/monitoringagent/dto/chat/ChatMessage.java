package com.example.monitoringagent.dto.chat;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatMessage {
    private String role;
    private String content;
    private long createdAt;

    public static ChatMessage of(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(System.currentTimeMillis());
        return message;
    }
}
