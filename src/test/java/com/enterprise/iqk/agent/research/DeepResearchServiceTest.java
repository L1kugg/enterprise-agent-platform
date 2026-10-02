package com.enterprise.iqk.agent.research;

import com.enterprise.iqk.agent.workflow.AgentStepRecord;
import com.enterprise.iqk.agent.workflow.AgentTaskRecord;
import com.enterprise.iqk.agent.workflow.AgentWorkflowEngine;
import com.enterprise.iqk.agent.workflow.WorkflowState;
import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.retrieval.HybridRetrievalService;
import com.enterprise.iqk.retrieval.ScoredDocument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 深度研究编排契约：同步剧本（拆题→检索→成稿）全程留痕、失败置 FAILED 并重抛；
 * 异步受理（createResearch 毫秒级返回 202 形状、队列满守卫式放弃并抛 ResearchQueueFullException）。
 */
class DeepResearchServiceTest {

    private ResearchPlannerAgent plannerAgent;
    private ReportWriterAgent writerAgent;
    private HybridRetrievalService hybridRetrievalService;
    private AgentWorkflowEngine workflowEngine;
    private MemoryService memoryService;
    private SimpleMeterRegistry registry;
    private DeepResearchService service;

    private final AtomicInteger taskSeq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        plannerAgent = mock(ResearchPlannerAgent.class);
        writerAgent = mock(ReportWriterAgent.class);
        hybridRetrievalService = mock(HybridRetrievalService.class);
        workflowEngine = mock(AgentWorkflowEngine.class);
        memoryService = mock(MemoryService.class);
        registry = new SimpleMeterRegistry();
        service = service(1, 5);
    }

    private DeepResearchService service(int workers, int capacity) {
        return new DeepResearchService(plannerAgent, writerAgent, hybridRetrievalService,
                workflowEngine, registry, memoryService, workers, capacity);
    }

    private AgentTaskRecord nextTask() {
        return AgentTaskRecord.builder()
                .taskId("task-" + taskSeq.incrementAndGet())
                .tenantId("tenant-a")
                .type("DEEP_RESEARCH")
                .status("PLANNING")
                .build();
    }

    /** executeResearch 不再自行落库：直接调用时传入 createResearch 已创建的任务记录。 */
    private AgentTaskRecord startedTask(String taskId) {
        return AgentTaskRecord.builder()
                .taskId(taskId)
                .tenantId("tenant-a")
                .type("DEEP_RESEARCH")
                .status("PLANNING")
                .build();
    }

    private AgentStepRecord nextStep(String taskId, int order) {
        return AgentStepRecord.builder()
                .stepId("step-" + order)
                .taskId(taskId)
                .agentName("agent")
                .status("RUNNING")
                .stepOrder(order)
                .build();
    }

    private ResearchTaskRequest request(String topic, String modelProfile) {
        ResearchTaskRequest request = new ResearchTaskRequest();
        request.setTopic(topic);
        request.setModelProfile(modelProfile);
        return request;
    }

    private void stubHappyPipeline(String taskId) {
        when(workflowEngine.startTask(eq("tenant-a"), eq("DEEP_RESEARCH"), anyString(), any(), isNull(), isNull()))
                .thenReturn(AgentTaskRecord.builder().taskId(taskId).tenantId("tenant-a")
                        .type("DEEP_RESEARCH").status("PLANNING").build());
        when(workflowEngine.startStep(eq(taskId), anyString(), anyInt(), anyMap()))
                .thenAnswer(inv -> nextStep(taskId, inv.getArgument(2)));
        when(plannerAgent.plan(anyString(), anyString(), eq("research_" + taskId), eq("tenant-a"), any()))
                .thenReturn(new ResearchPlannerAgent.ResearchPlan(
                        List.of("子问题一", "子问题二"), List.of("关键词"), "direct"));
        when(hybridRetrievalService.retrieve(eq("子问题一"), eq("tenant-a"), eq("research_" + taskId), eq(5)))
                .thenReturn(retrieval(List.of(doc("vec-1", "vector", "向量证据", 0.9))));
        when(hybridRetrievalService.retrieve(eq("子问题二"), eq("tenant-a"), eq("research_" + taskId), eq(5)))
                .thenReturn(retrieval(List.of()));
        when(writerAgent.writeReport(anyString(), anyString(), eq("research_" + taskId), eq("tenant-a"), any()))
                .thenReturn("# 研究报告正文");
    }

    private HybridRetrievalService.HybridRetrievalResult retrieval(List<ScoredDocument> docs) {
        return new HybridRetrievalService.HybridRetrievalResult(docs, docs.size(), docs.size(), List.of());
    }

    private ScoredDocument doc(String id, String sourceType, String content, double score) {
        return ScoredDocument.builder()
                .docId(id)
                .sourceType(sourceType)
                .title(id)
                .content(content)
                .retrievalScore(score)
                .metadata(Map.of())
                .build();
    }

    @Test
    void completesPipelineAndPersistsDone() {
        stubHappyPipeline("task-1");
        when(memoryService.queryRecentTaskMemories("tenant-a", 5)).thenReturn(List.of());

        DeepResearchService.DeepResearchResult result =
                service.executeResearch(startedTask("task-1"), request("测试主题", "balanced"), "tenant-a");

        assertThat(result.getStatus()).isEqualTo("DONE");
        assertThat(result.getReport()).isEqualTo("# 研究报告正文");
        assertThat(result.getTaskId()).isEqualTo("task-1");
        // executeResearch 不落库新任务：任务由 createResearch 创建（防"客户端 taskId 永远 PLANNING"回归）
        verify(workflowEngine, never()).startTask(anyString(), anyString(), anyString(), any(), any(), any());
        verify(workflowEngine).transitionStatus("task-1", WorkflowState.PLANNING, WorkflowState.SEARCHING);
        verify(workflowEngine).transitionStatus("task-1", WorkflowState.SEARCHING, WorkflowState.RETRIEVING);
        verify(workflowEngine).transitionStatus("task-1", WorkflowState.RETRIEVING, WorkflowState.WRITING);
        verify(workflowEngine).completeTask("task-1", WorkflowState.DONE, "# 研究报告正文");
        verify(workflowEngine).recordTaskMetrics(eq("DEEP_RESEARCH"), eq("DONE"), anyLong());
        // 每个子问题各一次混合检索（深度 "research_" 前缀作会话作用域）
        verify(hybridRetrievalService).retrieve("子问题一", "tenant-a", "research_task-1", 5);
        verify(hybridRetrievalService).retrieve("子问题二", "tenant-a", "research_task-1", 5);
    }

    @Test
    void injectsRecalledPriorFindingsIntoPlanner() {
        stubHappyPipeline("task-1");
        when(memoryService.queryRecentTaskMemories("tenant-a", 5))
                .thenReturn(List.of(MemoryItemRecord.builder().content("早前结论：X 已验证").build()));

        service.executeResearch(startedTask("task-1"), request("测试主题", "balanced"), "tenant-a");

        ArgumentCaptor<String> prior = ArgumentCaptor.forClass(String.class);
        verify(plannerAgent).plan(eq("测试主题"), prior.capture(), eq("research_task-1"),
                eq("tenant-a"), eq("balanced"));
        assertThat(prior.getValue()).contains("早前结论：X 已验证");
    }

    @Test
    void memoryRecallFailureDegradesToEmptyAndStillCompletes() {
        stubHappyPipeline("task-1");
        when(memoryService.queryRecentTaskMemories("tenant-a", 5)).thenThrow(new RuntimeException("memory down"));

        DeepResearchService.DeepResearchResult result =
                service.executeResearch(startedTask("task-1"), request("测试主题", "balanced"), "tenant-a");

        // 记忆召回失败按无结论降级，不阻断研究主链路
        assertThat(result.getStatus()).isEqualTo("DONE");
        verify(plannerAgent).plan(eq("测试主题"), anyString(), eq("research_task-1"),
                eq("tenant-a"), eq("balanced"));
    }

    @Test
    void failsTaskAndRethrowsOnPlannerFailure() {
        stubHappyPipeline("task-1");
        when(plannerAgent.plan(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("planner down"));

        assertThatThrownBy(() -> service.executeResearch(startedTask("task-1"), request("测试主题", "balanced"), "tenant-a"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("planner down");
        verify(workflowEngine).failTask("task-1", "planner down");
        verify(workflowEngine).recordTaskMetrics(eq("DEEP_RESEARCH"), eq("FAILED"), anyLong());
    }

    @Test
    void failsTaskAndRethrowsOnRetrievalFailure() {
        stubHappyPipeline("task-1");
        when(hybridRetrievalService.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("retrieval down"));

        assertThatThrownBy(() -> service.executeResearch(startedTask("task-1"), request("测试主题", "balanced"), "tenant-a"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("retrieval down");
        verify(workflowEngine).failTask("task-1", "retrieval down");
        verify(workflowEngine).recordTaskMetrics(eq("DEEP_RESEARCH"), eq("FAILED"), anyLong());
    }

    @Test
    void createResearchReturnsAcceptedShapedResultAndRunsInBackground() {
        stubHappyPipeline("task-1");
        when(memoryService.queryRecentTaskMemories("tenant-a", 5)).thenReturn(List.of());
        // 拆题阻塞在 latch 上：若 createResearch 是同步执行，它会在这里挂满整个 await 超时
        CountDownLatch plannerGate = new CountDownLatch(1);
        when(plannerAgent.plan(anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    plannerGate.await(5, TimeUnit.SECONDS);
                    return new ResearchPlannerAgent.ResearchPlan(List.of("子问题一"), List.of(), "direct");
                });

        long startedNs = System.nanoTime();
        DeepResearchService.DeepResearchResult accepted = service.createResearch(
                request("测试主题", "balanced"), "tenant-a");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);

        // 受理形状：taskId 已生成、状态 PLANNING、报告为空（经报告端点另行获取）
        assertThat(accepted.getTaskId()).isEqualTo("task-1");
        assertThat(accepted.getStatus()).isEqualTo("PLANNING");
        assertThat(accepted.getReport()).isNull();
        // 毫秒级受理：同步实现会阻塞在 plannerGate 直到 5 秒超时
        assertThat(elapsedMs).isLessThan(2000);

        plannerGate.countDown();
        verify(workflowEngine, timeout(5000)).completeTask("task-1", WorkflowState.DONE, "# 研究报告正文");
    }

    @Test
    void backgroundWorkRunsUnderTheAcceptedTaskIdNotADuplicate() {
        // 线上回归复现：startTask 每次返回不同 taskId（重复落库时后台会在新任务名下干活，
        // 客户端持有的 taskId 永远 PLANNING）——修复后每次受理只允许落库一条任务
        when(workflowEngine.startTask(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> nextTask());
        when(workflowEngine.startStep(anyString(), anyString(), anyInt(), anyMap()))
                .thenAnswer(inv -> nextStep("task-x", inv.getArgument(2)));
        when(memoryService.queryRecentTaskMemories(anyString(), anyInt())).thenReturn(List.of());
        when(plannerAgent.plan(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new ResearchPlannerAgent.ResearchPlan(
                        List.of("子问题一", "子问题二"), List.of(), "direct"));
        when(hybridRetrievalService.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(retrieval(List.of()));
        when(writerAgent.writeReport(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn("# 研究报告正文");

        DeepResearchService.DeepResearchResult accepted = service.createResearch(
                request("测试主题", "balanced"), "tenant-a");

        // 后台剧本必须在受理返回的那条任务名下跑完，且全程只落库这一条任务
        verify(workflowEngine, timeout(5000)).completeTask(
                eq(accepted.getTaskId()), eq(WorkflowState.DONE), anyString());
        verify(workflowEngine, times(1)).startTask(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void queueFullAbandonsTaskAndThrowsResearchQueueFullException() {
        DeepResearchService singleSlot = service(1, 1);
        CountDownLatch plannerGate = new CountDownLatch(1);
        when(workflowEngine.startTask(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> nextTask());
        when(workflowEngine.startStep(anyString(), anyString(), anyInt(), anyMap()))
                .thenAnswer(inv -> nextStep("task-x", inv.getArgument(2)));
        when(plannerAgent.plan(anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    plannerGate.await(5, TimeUnit.SECONDS);
                    return new ResearchPlannerAgent.ResearchPlan(List.of(), List.of(), "direct");
                });
        try {
            DeepResearchService.DeepResearchResult running = singleSlot.createResearch(
                    request("主题", null), "tenant-a");      // 占住唯一 worker
            DeepResearchService.DeepResearchResult queued = singleSlot.createResearch(
                    request("主题", null), "tenant-a");      // 占满队列（容量 1）

            assertThatThrownBy(() -> singleSlot.createResearch(request("主题", null), "tenant-a"))
                    .isInstanceOf(ResearchQueueFullException.class);
            // 队列满走守卫式放弃（不改写可能已完成的任务），并计入拒绝指标
            verify(workflowEngine).abandonTask(anyString(), contains("queue full"));
            assertThat(registry.get("research.task.rejected").counter().count()).isEqualTo(1.0);
            assertThat(running.getTaskId()).isNotEqualTo(queued.getTaskId());
        } finally {
            plannerGate.countDown();
        }
    }

    @Test
    void shutdownIsIdempotentAndNeverThrows() {
        assertThatCode(() -> {
            service.shutdownResearchExecutor();
            service.shutdownResearchExecutor();
        }).doesNotThrowAnyException();
    }
}
