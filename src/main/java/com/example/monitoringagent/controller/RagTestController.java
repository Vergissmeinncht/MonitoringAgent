package com.example.monitoringagent.controller;

import com.example.monitoringagent.dto.ApiResponse;
import com.example.monitoringagent.dto.ragtest.ExportRagTestRequest;
import com.example.monitoringagent.dto.ragtest.GenerateTestSetRequest;
import com.example.monitoringagent.dto.ragtest.RagTestCase;
import com.example.monitoringagent.dto.ragtest.RagTestRunResponse;
import com.example.monitoringagent.dto.ragtest.RunRagTestRequest;
import com.example.monitoringagent.service.RagTestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/rag-test")
public class RagTestController {

    private static final Logger logger = LoggerFactory.getLogger(RagTestController.class);
    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RagTestService ragTestService;

    public RagTestController(RagTestService ragTestService) {
        this.ragTestService = ragTestService;
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<List<RagTestCase>>> generate(@RequestBody GenerateTestSetRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(ragTestService.generateTestSet(request)));
        } catch (Exception e) {
            logger.error("生成 RAG 测试集失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/run")
    public ResponseEntity<ApiResponse<RagTestRunResponse>> run(@RequestBody RunRagTestRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(ragTestService.runTest(request.getTestCases())));
        } catch (Exception e) {
            logger.error("执行 RAG 测试失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/export/testset")
    public ResponseEntity<byte[]> exportTestSet(@RequestBody ExportRagTestRequest request) {
        String format = normalizeFormat(request.getFormat());
        String content = ragTestService.exportTestSet(request.getTestCases(), format);
        return download(content, "rag-testset-" + timestamp() + "." + format, format);
    }

    @PostMapping("/export/results")
    public ResponseEntity<byte[]> exportResults(@RequestBody ExportRagTestRequest request) {
        String format = normalizeFormat(request.getFormat());
        String content = ragTestService.exportResults(request.getResults(), format);
        return download(content, "rag-test-results-" + timestamp() + "." + format, format);
    }

    private ResponseEntity<byte[]> download(String content, String fileName, String format) {
        MediaType mediaType = "csv".equals(format)
                ? new MediaType("text", "csv", StandardCharsets.UTF_8)
                : MediaType.APPLICATION_JSON;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(ragTestService.toUtf8Bytes(content));
    }

    private String normalizeFormat(String format) {
        return "csv".equalsIgnoreCase(format) ? "csv" : "json";
    }

    private String timestamp() {
        return LocalDateTime.now().format(FILE_TIME_FORMATTER);
    }
}
