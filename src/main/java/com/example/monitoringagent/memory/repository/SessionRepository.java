package com.example.monitoringagent.memory.repository;

import com.example.monitoringagent.memory.document.SessionDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SessionRepository extends MongoRepository<SessionDoc, String> {
}
