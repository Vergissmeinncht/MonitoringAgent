package com.example.monitoringagent.service.document;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 纯文本处理器：按 UTF-8 文本读取，交给分片服务按段落分片。
 */
@Component
public class TextDocumentProcessor implements DocumentProcessor {

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("txt");
    }

    @Override
    public DocumentParseResult parse(Path path) throws Exception {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return new DocumentParseResult(content, "text", "TextDocumentProcessor",
                Map.of("_parser", "plain-text"));
    }
}
