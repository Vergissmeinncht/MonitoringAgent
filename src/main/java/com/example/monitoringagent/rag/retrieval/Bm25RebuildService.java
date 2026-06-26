package com.example.monitoringagent.rag.retrieval;

import com.example.monitoringagent.config.RagProperties;
import com.example.monitoringagent.dto.DocumentChunk;
import com.example.monitoringagent.service.DocumentChunkService;
import com.google.gson.Gson;
import org.apache.lucene.index.IndexWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * BM25 索引重建服务
 * 扫描 uploads 目录已入库文档，重新切片并重建 Lucene 索引，覆盖历史数据。
 * ID 生成规则与 VectorIndexService 保持一致，确保与 Milvus 中的分片对齐。
 */
@Service
public class Bm25RebuildService {

    private static final Logger logger = LoggerFactory.getLogger(Bm25RebuildService.class);

    private final Bm25IndexService bm25IndexService;
    private final DocumentChunkService chunkService;
    private final RagProperties ragProperties;

    @Value("${file.upload.path}")
    private String uploadPath;

    public Bm25RebuildService(Bm25IndexService bm25IndexService,
                              DocumentChunkService chunkService,
                              RagProperties ragProperties) {
        this.bm25IndexService = bm25IndexService;
        this.chunkService = chunkService;
        this.ragProperties = ragProperties;
    }

    public RebuildResult rebuild() {
        Path uploadDir = Paths.get(uploadPath).normalize();
        if (!Files.exists(uploadDir)) {
            throw new IllegalStateException("上传目录不存在: " + uploadDir);
        }

        // 1. 先清空旧索引
        bm25IndexService.clearAll();

        int fileCount = 0;
        int chunkCount = 0;

        try (IndexWriter writer = bm25IndexService.newWriter()) {
            try (Stream<Path> files = Files.walk(uploadDir)) {
                List<Path> regularFiles = files.filter(Files::isRegularFile).toList();
                for (Path file : regularFiles) {
                    try {
                        String content = Files.readString(file);
                        String normalizedPath = file.normalize().toString().replace(File.separatorChar, '/');

                        List<DocumentChunk> chunks = chunkService.chunkDocument(content, normalizedPath);
                        for (DocumentChunk chunk : chunks) {
                            String id = UUID.nameUUIDFromBytes(
                                    (normalizedPath + " " + chunk.getChunkIndex()).getBytes()).toString();
                            String metadataJson = buildMetadataJson(normalizedPath, chunk, chunks.size());
                            bm25IndexService.writeChunk(writer, id, chunk.getContent(), metadataJson, normalizedPath);
                            chunkCount++;
                        }
                        fileCount++;
                        logger.info("BM25 重建已处理文件: {}, 分片数: {}", normalizedPath, chunks.size());
                    } catch (Exception e) {
                        logger.warn("BM25 重建跳过文件: {}, 错误: {}", file, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("BM25 索引重建失败", e);
            throw new RuntimeException("BM25 索引重建失败: " + e.getMessage(), e);
        }

        logger.info("BM25 索引重建完成, 文件数: {}, 分片数: {}", fileCount, chunkCount);
        return new RebuildResult(fileCount, chunkCount);
    }

    private String buildMetadataJson(String normalizedPath, DocumentChunk chunk, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();
        Path path = Paths.get(normalizedPath);
        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "unknown";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf(".");
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }
        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        return new Gson().toJsonTree(metadata).toString();
    }

    public record RebuildResult(int fileCount, int chunkCount) {
    }
}
