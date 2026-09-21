package com.enterprise.iqk.agent.workflow;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 孤儿任务回收器契约：只回收超过阈值仍非终态的任务；
 * 守卫式置失败（已终态不覆盖）；回收自身故障不外抛。
 */
class WorkflowTaskReclaimerTest {

    private static final AgentTaskRecord STALE_RUNNING_TASK = AgentTaskRecord.builder()
            .taskId("task-orphan")
            .tenantId("tenant-a")
            .type("REACT_STREAM")
            .status("RUNNING")
            .updatedAt(LocalDateTime.now().minusMinutes(40))
            .build();

    private AgentTaskMapper taskMapper;
    private AgentWorkflowEngine workflowEngine;
    private SimpleMeterRegistry registry;
    private WorkflowTaskReclaimer reclaimer;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AgentTaskMapper.class);
        workflowEngine = mock(AgentWorkflowEngine.class);
        registry = new SimpleMeterRegistry();
        reclaimer = new WorkflowTaskReclaimer(taskMapper, workflowEngine, registry);
        ReflectionTestUtils.setField(reclaimer, "staleMinutes", 30L);
    }

    @Test
    void reclaimsStaleNonTerminalTaskWithGuardedFailure() {
        when(taskMapper.findStaleTasks(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(STALE_RUNNING_TASK));
        when(workflowEngine.abandonTask(eq("task-orphan"), anyString())).thenReturn(true);

        reclaimer.reclaimOrphanTasks();

        verify(workflowEngine).abandonTask(eq("task-orphan"), contains("orphaned"));
        assertThat(registry.get("agent.workflow.task.reclaimed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void cutoffIsStaleMinutesBeforeNow() {
        when(taskMapper.findStaleTasks(any(LocalDateTime.class), eq(100))).thenReturn(List.of());

        reclaimer.reclaimOrphanTasks();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskMapper).findStaleTasks(cutoff.capture(), eq(100));
        assertThat(cutoff.getValue()).isBefore(LocalDateTime.now().minusMinutes(29));
    }

    @Test
    void skipsQuietlyWhenNothingStale() {
        when(taskMapper.findStaleTasks(any(LocalDateTime.class), eq(100))).thenReturn(List.of());

        reclaimer.reclaimOrphanTasks();

        verifyNoInteractions(workflowEngine);
    }

    @Test
    void alreadyTerminalTaskIsNotCountedAsReclaimed() {
        when(taskMapper.findStaleTasks(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(STALE_RUNNING_TASK));
        // 守卫式更新命中 0 行：回收与正常完成竞态时任务已被置终态
        when(workflowEngine.abandonTask(eq("task-orphan"), anyString())).thenReturn(false);

        reclaimer.reclaimOrphanTasks();

        assertThat(registry.get("agent.workflow.task.reclaimed").counter().count()).isEqualTo(0.0);
    }

    @Test
    void reclaimFailureNeverPropagatesToSchedulerThread() {
        when(taskMapper.findStaleTasks(any(LocalDateTime.class), eq(100)))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> reclaimer.reclaimOrphanTasks()).doesNotThrowAnyException();
    }
}
