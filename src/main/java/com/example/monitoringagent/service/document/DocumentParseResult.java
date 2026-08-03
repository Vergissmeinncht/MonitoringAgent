package com.example.monitoringagent.service.document;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档解析结果：把任意文件类型解析后的统一产物。
 * content 为提取出的纯文本，交给 DocumentChunkService 做分片。
 */
public class DocumentParseResult {

    private final String content;
    private final String fileType;
    private final String parserName;
    private final Map<String, Object> metadata;

    public DocumentParseResult(String content, String fileType, String parserName, Map<String, Object> metadata) {
        this.content = content;
        this.fileType = fileType;
        this.parserName = parserName;
        this.metadata = metadata == null ? new HashMap<>() : metadata;
    }

    public String getContent() {
        return content;
    }

    public String getFileType() {
        return fileType;
    }

    public String getParserName() {
        return parserName;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
