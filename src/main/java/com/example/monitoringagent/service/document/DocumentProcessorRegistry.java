package com.example.monitoringagent.service.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 文档处理器注册表。
 * 启动时收集所有 DocumentProcessor，按扩展名路由到对应处理器。
 * 向量入库与 BM25 重建共用本注册表，确保两边解析/分片一致。
 */
@Service
public class DocumentProcessorRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DocumentProcessorRegistry.class);

    /** 扩展名 -> 处理器 */
    private final Map<String, DocumentProcessor> processorByExtension = new LinkedHashMap<>();

    public DocumentProcessorRegistry(List<DocumentProcessor> processors) {
        for (DocumentProcessor processor : processors) {
            for (String ext : processor.supportedExtensions()) {
                processorByExtension.put(ext.toLowerCase(), processor);
            }
        }
        logger.info("文档处理器注册完成, 支持的扩展名: {}", getSupportedExtensions());
    }

    /**
     * 支持的扩展名集合（小写、不带点），用于配置校验、错误提示和文档展示。
     */
    public Set<String> getSupportedExtensions() {
        return new TreeSet<>(processorByExtension.keySet());
    }

    public boolean isSupported(String extension) {
        return extension != null && processorByExtension.containsKey(extension.toLowerCase());
    }

    /**
     * 解析文件：根据扩展名选择处理器，找不到时抛出异常。
     */
    public DocumentParseResult parse(Path path) throws Exception {
        String extension = extractExtension(path);
        DocumentProcessor processor = processorByExtension.get(extension);
        if (processor == null) {
            throw new IllegalArgumentException("不支持的文件类型: " + extension
                    + "，当前支持: " + getSupportedExtensions());
        }
        DocumentParseResult result = processor.parse(path);
        logger.info("文件解析完成: {}, 处理器: {}, 文本长度: {}",
                path, result.getParserName(), result.getContent() == null ? 0 : result.getContent().length());
        return result;
    }

    private String extractExtension(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}
