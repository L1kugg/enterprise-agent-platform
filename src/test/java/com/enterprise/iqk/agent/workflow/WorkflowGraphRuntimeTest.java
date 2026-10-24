package com.enterprise.iqk.agent.workflow;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.llm.ModelCallGuard;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.retrieval.HybridRetrievalService;
import com.enterprise.iqk.service.TenantCostService;
import com.enterprise.iqk.service.platform.PlatformToolConfirmationService;
import com.enterprise.iqk.service.platform.PlatformWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流图运行时行为契约：顺序执行留痕 / 审批挂起-恢复 / 拒绝终止 / 未上线拒绝 / 队列满 429。
 */
class WorkflowGraphRuntimeTest {

    private static final String GRAPH = """
            {"nodes":[
              {"id":"n1","name":"开始","type":"START"},
              {"id":"n2","name":"查订单","type":"TOOL_CALL","parameters":{"toolId":9,"params":{"orderNo":"{{orderNo}}"}}},
              {"id":"n3","name":"人工复核","type":"HUMAN_APPROVAL","parameters":{"message":"高金额订单需要人工复核"}},
              {"id":"n4","name":"生成结论","type":"LLM","parameters":{"userPrompt":"订单信息：{{n2.result}}","outputKey":"conclusion"}},
              {"id":"n5","name":"结束","type":"END"}
            ],
            "edges":[
              {"source":"n1","target":"n2"},{"source":"n2","target":"n3"},
              {"source":"n3","target":"n4"},{"source":"n4","target":"n5"}
            ]}""";

    private PlatformAssetMapper assetMapper;
    private AgentWorkflowEngine workflowEngine;
    private PlatformWorkflowService workflowService;
    private PlatformToolConfirmationService confirmationService;
    private WorkflowGraphRuntime runtime;
    private ChatClient.CallResponseSpec callSpec;

    @BeforeEach
    void setUp() {
        assetMapper = mock(PlatformAssetMapper.class);
        workflowEngine = mock(AgentWorkflowEngine.class);
        workflowService = mock(PlatformWorkflowService.class);
        confirmationService = mock(PlatformToolConfirmationService.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("复核结论：同意退款");

        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new ModelRouter.ModelRouteDecision("balanced", "model-a", "standard", false,
                        "profile_match", "", "", null));
        TenantCostService tenantCostService = mock(TenantCostService.class);
        when(tenantCostService.estimateTokens(anyString())).thenReturn(10L);

        runtime = new WorkflowGraphRuntime(assetMapper, workflowEngine, workflowService,
                mock(HybridRetrievalService.class), modelRouter, tenantCostService,
                mock(ModelCallGuard.class), confirmationService, new ObjectMapper(),
                new SimpleMeterRegistry(), chatClient);

        when(workflowEngine.startTask(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(AgentTaskRecord.builder().taskId("task-1").tenantId("public").build());
        when(workflowEngine.startStep(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> AgentStepRecord.builder()
                        .stepId("step-" + invocation.getArgument(2))
                        .taskId(invocation.getArgument(0))
                        .build());
    }

    private void stubOnlineWorkflow() throws Exception {
        PlatformAsset workflow = PlatformAsset.builder().id(7L).name("订单复核流")
                .status("ONLINE").configJson(GRAPH).build();
        when(assetMapper.findByIdAndTypeVisible(7L, "public", "workflows")).thenReturn(workflow);
        com.fasterxml.jackson.databind.JsonNode graph = new ObjectMapper().readTree(GRAPH);
        when(workflowService.validatedGraph(any())).thenReturn(graph);
        when(workflowService.startNodeOf(any())).thenReturn(graph.get("nodes").get(0));
        when(workflowService.nextNodeAfter(any(), any(), any()))
                .thenReturn(graph.get("nodes").get(1))
                .thenReturn(graph.get("nodes").get(2))
                .thenReturn(graph.get("nodes").get(3))
                .thenReturn(graph.get("nodes").get(4))
                .thenReturn(null);
    }

    @Test
    void runsGraphAsynchronouslyAndPausesAtApprovalNode() throws Exception {
        stubOnlineWorkflow();
        when(confirmationService.createForWorkflow(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(com.enterprise.iqk.domain.AgentToolConfirmationRecord.builder().id(88L).build());

        String taskId = runtime.run(7L, "public", "chat-1", Map.of("orderNo", "SO-001"));
        awaitAsync();

        // 审批节点：任务挂起（前一执行态 → WAITING_APPROVAL）+ 24h 审批单，LLM 节点未执行
        verify(workflowEngine).transitionStatus(eq("task-1"), eq(WorkflowState.RETRIEVING),
                eq(WorkflowState.WAITING_APPROVAL));
        verify(confirmationService).createForWorkflow(eq("public"), eq("chat-1"), eq("task-1"),
                contains("3"), eq("人工复核"), contains("人工复核"));
        verify(workflowEngine, atLeastOnce()).startStep(eq("task-1"), eq("TOOL_CALL"), anyInt(), any());
        verify(workflowEngine, never()).completeTask(anyString(), any(), anyString());
    }

    @Test
    void approvalEventResumesAndCompletesTask() throws Exception {
        stubOnlineWorkflow();
        when(confirmationService.createForWorkflow(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(com.enterprise.iqk.domain.AgentToolConfirmationRecord.builder().id(88L).build());
        runtime.run(7L, "public", "chat-1", Map.of("orderNo", "SO-001"));
        awaitAsync();

        runtime.onWorkflowApproval(new WorkflowApprovalEvent("task-1", "step-3", true));
        awaitAsync();

        // 审批通过 → 恢复执行：状态从 WAITING_APPROVAL 回到执行态，后续 LLM 节点（WRITING）→ END → DONE
        verify(workflowEngine).transitionStatus(eq("task-1"), eq(WorkflowState.WAITING_APPROVAL),
                eq(WorkflowState.WRITING));
        verify(workflowEngine).completeTask(eq("task-1"), eq(WorkflowState.DONE), contains("conclusion"));
        // LLM 步骤留痕 COMPLETED（token/耗时参数为 Long，用 anyLong 匹配）
        verify(workflowEngine).completeStep(anyString(), eq("COMPLETED"), any(), any(),
                any(), eq("LLM"), any(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void rejectedApprovalFailsTask() throws Exception {
        stubOnlineWorkflow();
        when(confirmationService.createForWorkflow(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(com.enterprise.iqk.domain.AgentToolConfirmationRecord.builder().id(88L).build());
        runtime.run(7L, "public", "chat-1", Map.of());
        awaitAsync();

        runtime.onWorkflowApproval(new WorkflowApprovalEvent("task-1", "step-3", false));
        awaitAsync();

        verify(workflowEngine).failTask(eq("task-1"), contains("审批被拒绝"));
        verify(workflowEngine, never()).completeTask(anyString(), any(), anyString());
    }

    @Test
    void offlineWorkflowIsRejected() {
        when(assetMapper.findByIdAndTypeVisible(7L, "public", "workflows")).thenReturn(
                PlatformAsset.builder().id(7L).name("x").status("OFFLINE").configJson("{}").build());

        assertThatThrownBy(() -> runtime.run(7L, "public", "chat-1", Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未上线");
    }

    private static void awaitAsync() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
