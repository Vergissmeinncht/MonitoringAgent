package com.example.monitoringagent.dto.chat;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ChatContext {
    private String summary;
    private List<ChatMessage> recentMessages = new ArrayList<>();
    private List<String> longTermMemories = new ArrayList<>();
    private int compressionCount;
}
