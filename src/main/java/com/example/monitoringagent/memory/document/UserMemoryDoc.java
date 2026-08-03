package com.example.monitoringagent.memory.document;

import com.example.monitoringagent.memory.MemoryStatus;
import com.example.monitoringagent.memory.MemoryType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Setter
@Document(collection = "user_memories")
@CompoundIndexes({
        @CompoundIndex(name = "user_status_updated_idx", def = "{'userId':1,'status':1,'updatedAt':-1}"),
        @CompoundIndex(name = "user_type_content_idx", def = "{'userId':1,'type':1,'normalizedContent':1}", unique = true)
})
public class UserMemoryDoc {
    @Id
    private String id;
    private String userId;
    private MemoryType type;
    private String content;
    private String normalizedContent;
    /** 同一主题的稳定键，用于识别新旧事实冲突 */
    private String memoryKey;
    private String sourceSessionId;
    private double confidence;
    private MemoryStatus status = MemoryStatus.ACTIVE;
    private long createdAt;
    private long updatedAt;
    private long lastAccessedAt;
}
