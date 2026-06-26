package com.example.monitoringagent.controller;

import com.example.monitoringagent.rag.retrieval.Bm25RebuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * BM25 索引管理接口
 */
@RestController
@RequestMapping("/api/rag/bm25")
public class Bm25AdminController {

    private static final Logger logger = LoggerFactory.getLogger(Bm25AdminController.class);

    private final Bm25RebuildService rebuildService;

    public Bm25AdminController(Bm25RebuildService rebuildService) {
        this.rebuildService = rebuildService;
    }

    /**
     * 重建 BM25 索引：扫描 uploads 目录，重新切片并覆盖已入库文档。
     */
    @PostMapping("/rebuild")
    public ResponseEntity<Map<String, Object>> rebuild() {
        Map<String, Object> body = new HashMap<>();
        try {
            Bm25RebuildService.RebuildResult result = rebuildService.rebuild();
            body.put("code", 200);
            body.put("message", "success");
            body.put("fileCount", result.fileCount());
            body.put("chunkCount", result.chunkCount());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.error("BM25 索引重建失败", e);
            body.put("code", 500);
            body.put("message", "BM25 索引重建失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }
}
