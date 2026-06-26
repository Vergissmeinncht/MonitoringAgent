package com.example.monitoringagent.rag.retrieval;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * BM25 关键词检索服务
 * 默认 Lucene 相似度即 BM25。检索失败或索引为空时返回空列表，由上层决定是否降级。
 */
@Service
public class Bm25SearchService {

    private static final Logger logger = LoggerFactory.getLogger(Bm25SearchService.class);

    private final Bm25IndexService indexService;

    public Bm25SearchService(Bm25IndexService indexService) {
        this.indexService = indexService;
    }

    public List<RetrievalCandidate> search(String query, int topK) {
        List<RetrievalCandidate> results = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return results;
        }

        try (DirectoryReader reader = DirectoryReader.open(indexService.getDirectory())) {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser(Bm25IndexService.FIELD_CONTENT, new StandardAnalyzer());
            parser.setDefaultOperator(QueryParser.Operator.OR);

            Query parsedQuery = parser.parse(QueryParser.escape(query));
            TopDocs topDocs = searcher.search(parsedQuery, topK);

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                results.add(RetrievalCandidate.bm25(
                        doc.get(Bm25IndexService.FIELD_ID),
                        doc.get(Bm25IndexService.FIELD_CONTENT),
                        doc.get(Bm25IndexService.FIELD_METADATA),
                        scoreDoc.score
                ));
            }
            logger.info("BM25 检索完成, query: {}, 命中: {}", query, results.size());
        } catch (org.apache.lucene.index.IndexNotFoundException e) {
            logger.warn("BM25 索引尚未建立，跳过关键词召回（将降级为纯向量）");
        } catch (Exception e) {
            logger.warn("BM25 检索失败，跳过关键词召回（将降级为纯向量）: {}", e.getMessage());
        }
        return results;
    }
}
