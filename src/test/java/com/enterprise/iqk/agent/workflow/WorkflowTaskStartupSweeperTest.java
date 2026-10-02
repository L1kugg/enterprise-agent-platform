package com.enterprise.iqk.agent.workflow;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 启动任务清扫器契约：启动即把遗留非终态任务守卫式收尾；
 * 守卫输家（已终态）不计数；循环扫到空批；自身故障绝不阻断应用启动。
 */
class WorkflowTaskStartupSweeperTest {

    private AgentTaskMapper taskMapper;
    private AgentWorkflowEngine workflowEngine;
    private SimpleMeterRegistry registry;
    private WorkflowTaskStartupSweeper sweeper;

    private static AgentTaskRecord task(String taskId) {
        return AgentTaskRecord.builder()
                .taskId(taskId)
                .tenantId("tenant-a")
                .type("DEEP_RESEARCH")
                .status("SEARCHING")
                .build();
    }

    @BeforeEach
    void setUp() {
        taskMapper = mock(AgentTaskMapper.class);
        workflowEngine = mock(AgentWorkflowEngine.class);
        registry = new SimpleMeterRegistry();
        sweeper = new WorkflowTaskStartupSweeper(taskMapper, workflowEngine, registry);
    }

    @Test
    void sweepsNonTerminalTasksAtStartupWithGuardedFailure() {
        when(taskMapper.findNonTerminalTasks(eq(500)))
                .thenReturn(List.of(task("task-1"), task("task-2")), List.of());
        when(workflowEngine.abandonTask(anyString(), anyString())).thenReturn(true);

        sweeper.run(null);

        verify(workflowEngine).abandonTask(eq("task-1"), contains("restarted"));
        verify(workflowEngine).abandonTask(eq("task-2"), contains("restarted"));
        assertThat(registry.get("agent.workflow.task.swept").counter().count()).isEqualTo(2.0);
    }

    @Test
    void stopsQuietlyWhenScanReturnsEmpty() {
        when(taskMapper.findNonTerminalTasks(eq(500))).thenReturn(List.of());

        sweeper.run(null);

        verifyNoInteractions(workflowEngine);
        // 无遗留时不注册计数器（Prometheus 语义下缺席即 0）
        assertThat(registry.find("agent.workflow.task.swept").counter()).isNull();
    }

    @Test
    void guardedAbandonRacingCompletionIsNotCounted() {
        when(taskMapper.findNonTerminalTasks(eq(500)))
                .thenReturn(List.of(task("task-1")), List.of());
        // 守卫式更新命中 0 行：清扫与正常完成竞态时任务已被置终态
        when(workflowEngine.abandonTask(anyString(), anyString())).thenReturn(false);

        sweeper.run(null);

        verify(workflowEngine).abandonTask(eq("task-1"), anyString());
        assertThat(registry.find("agent.workflow.task.swept").counter()).isNull();
    }

    @Test
    void sweepFailureNeverFailsStartup() {
        when(taskMapper.findNonTerminalTasks(eq(500))).thenThrow(new RuntimeException("db down"));

        // ApplicationRunner 抛异常会中止 Spring Boot 启动——清扫失败必须吞掉
        assertThatCode(() -> sweeper.run(null)).doesNotThrowAnyException();
    }

    @Test
    void loopsUntilScanEmpty() {
        when(taskMapper.findNonTerminalTasks(eq(500)))
                .thenReturn(List.of(task("task-1")), List.of());
        when(workflowEngine.abandonTask(anyString(), anyString())).thenReturn(true);

        sweeper.run(null);

        verify(taskMapper, times(2)).findNonTerminalTasks(500);
        verify(workflowEngine, times(1)).abandonTask(anyString(), anyString());
    }
}
