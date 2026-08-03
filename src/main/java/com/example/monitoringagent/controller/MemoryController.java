package com.example.monitoringagent.controller;

import com.example.monitoringagent.dto.ApiResponse;
import com.example.monitoringagent.memory.LongTermMemoryService;
import com.example.monitoringagent.memory.document.UserMemoryDoc;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/memory/users")
public class MemoryController {

    private final LongTermMemoryService memoryService;

    public MemoryController(LongTermMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<List<UserMemoryDoc>>> list(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success(memoryService.list(userId)));
    }

    @DeleteMapping("/{userId}/{memoryId}")
    public ResponseEntity<ApiResponse<String>> delete(@PathVariable String userId,
                                                       @PathVariable String memoryId) {
        if (!memoryService.delete(userId, memoryId)) {
            return ResponseEntity.ok(ApiResponse.error("长期记忆不存在或删除失败"));
        }
        return ResponseEntity.ok(ApiResponse.success("长期记忆已删除"));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse<String>> clear(@PathVariable String userId) {
        int count = memoryService.clear(userId);
        return ResponseEntity.ok(ApiResponse.success("已清空 " + count + " 条长期记忆"));
    }
}
