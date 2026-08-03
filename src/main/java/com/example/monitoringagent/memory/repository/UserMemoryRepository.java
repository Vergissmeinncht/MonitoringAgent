package com.example.monitoringagent.memory.repository;

import com.example.monitoringagent.memory.MemoryStatus;
import com.example.monitoringagent.memory.MemoryType;
import com.example.monitoringagent.memory.document.UserMemoryDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface UserMemoryRepository extends MongoRepository<UserMemoryDoc, String> {
    List<UserMemoryDoc> findByUserIdAndStatusOrderByUpdatedAtDesc(String userId, MemoryStatus status);
    Optional<UserMemoryDoc> findByIdAndUserId(String id, String userId);
    Optional<UserMemoryDoc> findByUserIdAndTypeAndNormalizedContent(
            String userId, MemoryType type, String normalizedContent);
    List<UserMemoryDoc> findByUserIdAndTypeAndMemoryKeyAndStatus(
            String userId, MemoryType type, String memoryKey, MemoryStatus status);
    long countByUserIdAndStatus(String userId, MemoryStatus status);
}
