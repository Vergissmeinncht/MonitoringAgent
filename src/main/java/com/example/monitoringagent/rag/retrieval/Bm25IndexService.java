package com.example.monitoringagent.rag.retrieval;

import com.example.monitoringagent.config.RagProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * BM25 索引写入服务
 * 使用 Apache Lucene 在本地维护一份关键词索引，与 Milvus 向量库双写。
 */
@Service
public class Bm25IndexService {

    private static final Logger logger = LoggerFactory.getLogger(Bm25IndexService.class);

    public static final String FIELD_ID = "id";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_METADATA = "metadata";
    public static final String FIELD_SOURCE = "_source";

    private final RagProperties ragProperties;
    private Directory directory;

    public Bm25IndexService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @PostConstruct
    public void init() throws Exception {
        Path indexPath = Paths.get(ragProperties.getBm25().getIndexPath()).normalize();
        Files.createDirectories(indexPath);
        this.directory = FSDirectory.open(indexPath);
        logger.info("BM25 索引初始化完成, 路径: {}", indexPath);
    }

    @PreDestroy
    public void close() {
        if (directory != null) {
            try {
                directory.close();
            } catch (Exception e) {
                logger.warn("关闭 BM25 索引目录失败", e);
            }
        }
    }

    public Directory getDirectory() {
        return directory;
    }

    /**
     * 写入或更新一条文档分片。按 id 去重更新。
     */
    public void indexChunk(String id, String content, String metadataJson, String source) {
        try (IndexWriter writer = newWriter()) {
            writeChunk(writer, id, content, metadataJson, source);
        } catch (Exception e) {
            logger.error("BM25 写入分片失败, id: {}", id, e);
            throw new RuntimeException("BM25 写入分片失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除某个源文件的所有分片。
     */
    public void deleteBySource(String source) {
        try (IndexWriter writer = newWriter()) {
            writer.deleteDocuments(new Term(FIELD_SOURCE, source));
        } catch (Exception e) {
            logger.warn("BM25 删除旧分片失败, source: {}", source, e);
        }
    }

    /**
     * 清空整个 BM25 索引（用于重建）。
     */
    public void clearAll() {
        try (IndexWriter writer = newWriter()) {
            writer.deleteAll();
            logger.info("BM25 索引已清空");
        } catch (Exception e) {
            logger.error("清空 BM25 索引失败", e);
            throw new RuntimeException("清空 BM25 索引失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提供给重建流程的批量写入器，避免每条分片都开关一次 writer。
     */
    public IndexWriter newWriter() throws Exception {
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        return new IndexWriter(directory, config);
    }

    public void writeChunk(IndexWriter writer, String id, String content, String metadataJson, String source) throws Exception {
        Document doc = new Document();
        doc.add(new StringField(FIELD_ID, id, Field.Store.YES));
        doc.add(new TextField(FIELD_CONTENT, content == null ? "" : content, Field.Store.YES));
        doc.add(new StringField(FIELD_METADATA, metadataJson == null ? "" : metadataJson, Field.Store.YES));
        doc.add(new StringField(FIELD_SOURCE, source == null ? "" : source, Field.Store.YES));
        // 按 id 更新，保证幂等
        writer.updateDocument(new Term(FIELD_ID, id), doc);
    }
}
