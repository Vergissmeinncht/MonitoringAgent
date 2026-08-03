package com.example.monitoringagent.memory.repository;

import com.example.monitoringagent.memory.document.ConversationMessageDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ConversationMessageRepository extends MongoRepository<ConversationMessageDoc, String> {

    List<ConversationMessageDoc> findBySessionIdOrderBySequenceAsc(String sessionId);

    void deleteBySessionId(String sessionId);

    long countBySessionId(String sessionId);
}
