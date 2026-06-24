package com.example.monitoringagent.agent.tool;

import com.example.monitoringagent.dto.chat.ChatMessage;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TokenEstimatorTool {

    public int estimateConversation(String summary, List<ChatMessage> messages, String currentQuestion) {
        int total = 300;
        total += estimate(summary);
        total += estimate(currentQuestion);
        for (ChatMessage message : messages) {
            total += 4 + estimate(message.getRole()) + estimate(message.getContent());
        }
        return total;
    }

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        double tokens = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN) {
                tokens += 1.0;
            } else if (Character.isWhitespace(value)) {
                tokens += 0;
            } else if (value < 128) {
                tokens += 0.25;
            } else {
                tokens += 0.5;
            }
        }
        return Math.max(1, (int) Math.ceil(tokens));
    }
}
