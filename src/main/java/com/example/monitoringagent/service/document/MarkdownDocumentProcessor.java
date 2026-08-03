package com.example.monitoringagent.service.document;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Markdown 处理器：按 UTF-8 文本读取，保留 # 标题结构，交给分片服务做标题/段落分片。
 */
@Component
public class MarkdownDocumentProcessor implements DocumentProcessor {

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("md", "markdown");
    }

    @Override
    public DocumentParseResult parse(Path path) throws Exception {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return new DocumentParseResult(content, "markdown", "MarkdownDocumentProcessor",
                Map.of("_parser", "markdown-text"));
    }
}
