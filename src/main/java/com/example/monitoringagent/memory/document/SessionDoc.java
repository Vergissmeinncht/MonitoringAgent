package com.example.monitoringagent.memory.document;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * 会话元数据与压缩后的长期摘要，_id 即 sessionId。
 * 用于应用重启后恢复内存中的 SessionInfo。
 */
@Getter
@Setter
@Document(collection = "sessions")
public class SessionDoc {

    @Id
    private String sessionId;

    private String userId;

    private String summary;

    private int compressionCount;

    private long lastCompressedAt;

    private int lastTokenEstimate;

    private long createTime;

    private long updatedAt;
}
