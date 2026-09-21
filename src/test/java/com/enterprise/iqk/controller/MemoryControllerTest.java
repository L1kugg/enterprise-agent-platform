package com.enterprise.iqk.controller;

import com.enterprise.iqk.memory.MemoryEventRecord;
import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryControllerTest {

    private MemoryService memoryService;
    private MemoryController controller;

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        controller = new MemoryController(memoryService);
        // 模拟鉴权过滤器写入的租户上下文
        MDC.put(TenantContext.TENANT_REQUEST_ATTRIBUTE, "tenant-1");
    }

    @AfterEach
    void tearDown() {
        MDC.remove(TenantContext.TENANT_REQUEST_ATTRIBUTE);
    }

    @Test
    void queryWithoutTypeAggregatesAllThreeLayers() {
        when(memoryService.queryShortMemory("tenant-1", "chat-1", 20)).thenReturn(List.of(
                MemoryItemRecord.builder().memoryId("mem-s").type("short").build()));
        when(memoryService.queryLongMemory("tenant-1", "chat-1", 20)).thenReturn(List.of(
                MemoryItemRecord.builder().memoryId("mem-l").type("long").build()));
        when(memoryService.queryFactMemory(eq("tenant-1"), anyDouble(), anyInt())).thenReturn(List.of(
                MemoryItemRecord.builder().memoryId("mem-f").type("fact").build()));

        MemoryController.MemoryQueryResponse response = controller.query("chat-1", null, 20);

        assertThat(response.getShortMemories()).hasSize(1);
        assertThat(response.getLongMemories()).hasSize(1);
        assertThat(response.getFacts()).hasSize(1);
    }

    @Test
    void queryWithTypeShortOnlyQueriesThatLayer() {
        when(memoryService.queryShortMemory("tenant-1", "chat-1", 5)).thenReturn(List.of());

        MemoryController.MemoryQueryResponse response = controller.query("chat-1", "short", 5);

        assertThat(response.getShortMemories()).isEmpty();
        verify(memoryService, never()).queryLongMemory(anyString(), anyString(), anyInt());
        verify(memoryService, never()).queryFactMemory(anyString(), anyDouble(), anyInt());
    }

    @Test
    void taskMemoriesQueryByTaskIdUnderCurrentTenant() {
        // task 层读侧：按 taskId 精确召回该任务沉淀的结论，租户取自上下文
        when(memoryService.queryTaskMemory("tenant-1", "task-9")).thenReturn(List.of(
                MemoryItemRecord.builder().memoryId("mem-t").type("task").build()));

        assertThat(controller.taskMemories("task-9")).hasSize(1);
    }

    @Test
    void eventsDelegateToTenantScopedService() {
        // 服务层按租户隔离：他租户记忆返回空（此处模拟服务层判定结果）
        when(memoryService.getEvents("tenant-1", "mem-x")).thenReturn(List.of());

        assertThat(controller.events("mem-x")).isEmpty();
    }

    @Test
    void eventsReturnsFullChainForOwnMemory() {
        when(memoryService.getEvents("tenant-1", "mem-1")).thenReturn(List.of(
                MemoryEventRecord.builder().memoryId("mem-1").action("CREATE").build()));

        assertThat(controller.events("mem-1")).hasSize(1);
    }

    @Test
    void saveDispatchesByType() {
        controller.save(new MemoryController.MemorySaveRequest(
                "long", "chat-1", null, "用户偏好简洁回答", null));

        verify(memoryService).saveLongMemory("tenant-1", "chat-1", "用户偏好简洁回答", "manual");
    }

    @Test
    void saveFactDefaultsConfidenceToRecallFloor() {
        controller.save(new MemoryController.MemorySaveRequest(
                "fact", null, null, "某事实", null));

        verify(memoryService).saveFactMemory(anyString(), isNull(), anyString(), anyString(),
                eq(0.7));
    }

    @Test
    void saveValidatesInput() {
        assertThatThrownBy(() -> controller.save(new MemoryController.MemorySaveRequest(
                "short", "chat-1", null, "  ", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.save(new MemoryController.MemorySaveRequest(
                "task", "chat-1", null, "结论", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
        assertThatThrownBy(() -> controller.save(new MemoryController.MemorySaveRequest(
                "unknown", "chat-1", null, "内容", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的记忆类型");
    }
}
