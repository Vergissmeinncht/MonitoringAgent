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
 * PDF 处理器：用 Apache Tika 提取文本型 PDF 正文。
 * 仅支持文本型 PDF；扫描件/图片 PDF 无 OCR，提取不到有效文本。
 */
@Component
public class PdfDocumentProcessor implements DocumentProcessor {

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("pdf");
    }

    @Override
    public DocumentParseResult parse(Path path) throws Exception {
        // BodyContentHandler(-1) 取消默认 10 万字符截断，完整提取正文
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        AutoDetectParser parser = new AutoDetectParser();
        try (InputStream in = Files.newInputStream(path)) {
            parser.parse(in, handler, metadata, new ParseContext());
        }
        String content = handler.toString();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("PDF 未提取到文本内容（可能是扫描件/图片 PDF，本轮不支持 OCR）: " + path);
        }
        return new DocumentParseResult(content, "pdf", "PdfDocumentProcessor",
                Map.of("_parser", "tika-pdf"));
    }
}
