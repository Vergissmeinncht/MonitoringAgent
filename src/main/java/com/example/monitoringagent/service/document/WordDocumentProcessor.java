package com.example.monitoringagent.service.document;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Word 处理器：用 Apache Tika 提取 .doc 和 .docx 正文文本。
 * 复杂表格、图片文字、批注、页眉页脚不保证完整保留。
 */
@Component
public class WordDocumentProcessor implements DocumentProcessor {

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("doc", "docx");
    }

    @Override
    public DocumentParseResult parse(Path path) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        AutoDetectParser parser = new AutoDetectParser();
        try (InputStream in = Files.newInputStream(path)) {
            parser.parse(in, handler, metadata, new ParseContext());
        }
        String content = handler.toString();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Word 文档未提取到文本内容: " + path);
        }
        return new DocumentParseResult(content, "word", "WordDocumentProcessor",
                Map.of("_parser", "tika-word"));
    }
}
