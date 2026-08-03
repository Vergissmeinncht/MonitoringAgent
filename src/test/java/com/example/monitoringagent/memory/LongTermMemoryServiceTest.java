package com.example.monitoringagent.memory;

import com.example.monitoringagent.config.MemoryProperties;
import com.example.monitoringagent.memory.document.UserMemoryDoc;
import com.example.monitoringagent.memory.repository.UserMemoryRepository;
import com.example.monitoringagent.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryServiceTest {

    private UserMemoryRepository repository;
    private MemoryProperties properties;
    private LongTermMemoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserMemoryRepository.class);
        properties = new MemoryProperties();
        properties.getLongTerm().setRetrievalTopK(2);
        service = new LongTermMemoryService(
                repository, properties, mock(ChatService.class), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void retrievesOnlyRelevantMemoriesForRequestedUser() {
        UserMemoryDoc environment = memory("m1", "user-a", MemoryType.ENVIRONMENT,
                "生产环境使用 Kubernetes 1.30", 0.95);
        UserMemoryDoc unrelated = memory("m2", "user-a", MemoryType.PREFERENCE,
                "用户喜欢简洁回答", 0.90);
        when(repository.findByUserIdAndStatusOrderByUpdatedAtDesc("user-a", MemoryStatus.ACTIVE))
                .thenReturn(List.of(environment, unrelated));

        List<String> result = service.retrieve("user-a", "Kubernetes 集群应该如何配置 HPA");

        assertThat(result).containsExactly("[ENVIRONMENT] 生产环境使用 Kubernetes 1.30");
        verify(repository).findByUserIdAndStatusOrderByUpdatedAtDesc("user-a", MemoryStatus.ACTIVE);
        verify(repository).save(environment);
    }

    @Test
    void alwaysIncludesConstraintsWithinTopK() {
        UserMemoryDoc constraint = memory("m1", "user-a", MemoryType.CONSTRAINT,
                "不能自动执行重启操作", 0.98);
        when(repository.findByUserIdAndStatusOrderByUpdatedAtDesc("user-a", MemoryStatus.ACTIVE))
                .thenReturn(List.of(constraint));

        assertThat(service.retrieve("user-a", "分析 CPU 告警"))
                .containsExactly("[CONSTRAINT] 不能自动执行重启操作");
    }

    @Test
    void deleteRequiresMatchingUserId() {
        UserMemoryDoc memory = memory("m1", "user-a", MemoryType.PROJECT_FACT,
                "项目使用 Java 17", 0.9);
        when(repository.findByIdAndUserId("m1", "user-a")).thenReturn(Optional.of(memory));
        when(repository.findByIdAndUserId("m1", "user-b")).thenReturn(Optional.empty());

        assertThat(service.delete("user-b", "m1")).isFalse();
        assertThat(service.delete("user-a", "m1")).isTrue();
        assertThat(memory.getStatus()).isEqualTo(MemoryStatus.DELETED);
        verify(repository).save(memory);
    }

    @Test
    void clearSoftDeletesOnlyMemoriesReturnedForUser() {
        UserMemoryDoc first = memory("m1", "user-a", MemoryType.ENVIRONMENT, "使用 Java 17", 0.9);
        UserMemoryDoc second = memory("m2", "user-a", MemoryType.CONSTRAINT, "禁止自动重启", 0.9);
        when(repository.findByUserIdAndStatusOrderByUpdatedAtDesc("user-a", MemoryStatus.ACTIVE))
                .thenReturn(List.of(first, second));

        assertThat(service.clear("user-a")).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(MemoryStatus.DELETED);
        assertThat(second.getStatus()).isEqualTo(MemoryStatus.DELETED);
        verify(repository).saveAll(List.of(first, second));
    }

    @Test
    void disabledMemoryDoesNotAccessRepository() {
        properties.getLongTerm().setEnabled(false);

        assertThat(service.retrieve("user-a", "Java")).isEmpty();
        assertThat(service.list("user-a")).isEmpty();
        assertThat(service.delete("user-a", "m1")).isFalse();
        verify(repository, never()).findByUserIdAndStatusOrderByUpdatedAtDesc(any(), any());
    }

    private UserMemoryDoc memory(String id, String userId, MemoryType type,
                                 String content, double confidence) {
        UserMemoryDoc memory = new UserMemoryDoc();
        memory.setId(id);
        memory.setUserId(userId);
        memory.setType(type);
        memory.setContent(content);
        memory.setConfidence(confidence);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setUpdatedAt(System.currentTimeMillis());
        return memory;
    }
}
