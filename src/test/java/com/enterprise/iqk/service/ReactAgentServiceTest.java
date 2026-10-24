package com.enterprise.iqk.service;

import com.enterprise.iqk.agent.harness.ActionSchemaRegistry;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.agent.harness.McpToolCatalog;
import com.enterprise.iqk.agent.harness.PlannerActionCatalog;
import com.enterprise.iqk.config.properties.AgentRuntimeProperties;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.llm.PlatformChatModelRegistry;
import com.enterprise.iqk.memory.ChatTurnMemoryRecorder;
import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.service.agent.AgentChatRuntime;
import com.enterprise.iqk.service.agent.AgentInvocationLogRecorder;
import com.enterprise.iqk.service.agent.AgentPromptMerger;
import com.enterprise.iqk.domain.agent.AgentRuntimeConfig;
import com.enterprise.iqk.service.agent.AgentRuntimeConfigService;
import com.enterprise.iqk.service.agent.AgentSafetyService;
import com.enterprise.iqk.service.agent.SafetyMatchResult;
import com.enterprise.iqk.service.agent.ReActPlannerPrompts;
import com.enterprise.iqk.service.agent.ReActSupport;
import com.enterprise.iqk.testutil.TestGuards;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactAgentServiceTest {

    /** 智能体运行时桩：agentId 不传时全部穿透，模型调用只走档位路由路径；传 config 时 prepare 返回该配置。 */
    private static AgentChatRuntime routerOnlyRuntime(ChatClient chatClient, AgentRuntimeConfig config) {
        AgentRuntimeConfigService configService = mock(AgentRuntimeConfigService.class);
        AgentSafetyService safetyService = mock(AgentSafetyService.class);
        if (config != null) {
            when(configService.loadForChat(anyString(), org.mockito.ArgumentMatchers.anyLong(), anyBoolean()))
                    .thenReturn(config);
            when(safetyService.interceptInput(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                    .thenReturn(SafetyMatchResult.notBlocked());
        }
        return new AgentChatRuntime(
                configService,
                safetyService,
                mock(PlatformChatModelRegistry.class),
                mock(AgentInvocationLogRecorder.class),
                new AgentRuntimeProperties(),
                TestGuards.real(),
                mock(TenantCostService.class),
                chatClient);
    }

    /** 统一装配：共享内核（决策解析/提示词/执行支撑）用真实实现，协作方按用例注入桩。 */
    private static ReactAgentService newService(ModelRouter modelRouter, ChatClient chatClient,
                                                AgentHarnessService harness, MemoryService memoryService,
                                                ChatTurnMemoryRecorder recorder) {
        return newService(modelRouter, chatClient, harness, memoryService, recorder, null);
    }

    private static ReactAgentService newService(ModelRouter modelRouter, ChatClient chatClient,
                                                AgentHarnessService harness, MemoryService memoryService,
                                                ChatTurnMemoryRecorder recorder, AgentRuntimeConfig config) {
        PlannerActionCatalog catalog =
                new PlannerActionCatalog(new ActionSchemaRegistry(), mock(McpToolCatalog.class));
        ReactResponseFormatter formatter = new ReactResponseFormatter(new ObjectMapper());
        com.enterprise.iqk.service.agent.AgentToolCatalog toolCatalog =
                new com.enterprise.iqk.service.agent.AgentToolCatalog(
                        mock(com.enterprise.iqk.mapper.AgentBindingMapper.class),
                        mock(com.enterprise.iqk.mapper.PlatformAssetMapper.class),
                        new ObjectMapper());
        return new ReactAgentService(
                modelRouter,
                new ReactDecisionParser(catalog, new ObjectMapper()),
                formatter,
                recorder,
                new AgentPromptMerger(),
                routerOnlyRuntime(chatClient, config),
                new ReActPlannerPrompts(catalog, toolCatalog, formatter),
                new ReActSupport(harness, memoryService, mock(MeterRegistry.class)),
                new com.enterprise.iqk.ingestion.ChatAttachmentService(
                        mock(com.enterprise.iqk.security.FileSafetyScanner.class),
                        new com.enterprise.iqk.ingestion.ChatAttachmentStore()));
    }

    @Test
    void rejectsRequestsWithoutPromptOrChatIdBeforeCallingExternalDependencies() {
        ReactAgentService service = newService(mock(ModelRouter.class), mock(ChatClient.class),
                mock(AgentHarnessService.class), mock(MemoryService.class), mock(ChatTurnMemoryRecorder.class));
        ReactChatRequestVO missingPrompt = new ReactChatRequestVO();
        missingPrompt.setChatId("chat-1");
        ReactChatRequestVO missingChatId = new ReactChatRequestVO();
        missingChatId.setPrompt("hello");

        assertThatThrownBy(() -> service.chat(missingPrompt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("问题内容不能为空");
        assertThatThrownBy(() -> service.chat(missingChatId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话 ID 不能为空");
    }

    @Test
    void marksTheResponseAsFallbackWhenThePlannerModelIsUnavailable() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        ChatClient chatClient = mock(ChatClient.class);
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(chatClient.prompt()).thenThrow(new IllegalStateException("model unavailable"));
        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new ModelRouter.ModelRouteDecision("quality", "model-a", "premium", false, "profile_match", "", "", null)
        );
        ReactAgentService service = newService(modelRouter, chatClient, harness,
                mock(MemoryService.class), mock(ChatTurnMemoryRecorder.class));
        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt("高温健康风险有哪些？");
        request.setChatId("chat-1");
        request.setModelProfile("quality");

        assertThat(service.chat(request).getFallback()).isTrue();
        verify(harness, never()).execute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void injectsRecalledMemoryIntoPlannerPromptAndReportsMemoryUsed() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(
                "{\"thought\":\"可直答\",\"action\":\"finish\",\"answer\":\"推荐《Java并发实战》\"}");
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new ModelRouter.ModelRouteDecision("balanced", "model-a", "standard", false, "profile_match", "", "", null)
        );
        MemoryService memoryService = mock(MemoryService.class);
        MemoryItemRecord profile = MemoryItemRecord.builder()
                .memoryId("mem-l1").type("long").content("画像: 用户是 Java 后端开发者").build();
        when(memoryService.buildContext(anyString(), anyString(), anyString())).thenReturn(
                new MemoryService.MemoryContextSnapshot(
                        "用户长期记忆:\n- 画像: 用户是 Java 后端开发者\n",
                        List.of(), List.of(profile), List.of()));
        ChatTurnMemoryRecorder recorder = mock(ChatTurnMemoryRecorder.class);

        ReactAgentService service = newService(modelRouter, chatClient, mock(AgentHarnessService.class),
                memoryService, recorder);
        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt("帮我推荐一门课");
        request.setChatId("chat-1");
        request.setModelProfile("balanced");

        var response = service.chat(request);

        // 轨迹步骤带真实耗时（每步掐表），null 只出现在工作流引擎/历史数据里
        assertThat(response.getTrace()).isNotEmpty();
        assertThat(response.getTrace().get(0).getElapsedMs()).isNotNull().isGreaterThanOrEqualTo(0L);

        // 记忆进入 planner 的提示词（读侧闭环）；动作列表由 PlannerActionCatalog 从注册表生成
        ArgumentCaptor<String> plannerPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(plannerPrompt.capture());
        assertThat(plannerPrompt.getValue())
                .contains("已知记忆")
                .contains("用户是 Java 后端开发者")
                .contains("可选动作（只能从列表中选）")
                .contains("- mcp_call（");
        // memoryUsed 上报实际注入的记忆
        assertThat(response.getMemoryUsed()).contains("long: 画像: 用户是 Java 后端开发者");
        // 成稿写回 short 记忆（写侧闭环）：user 键（匿名回落 chatId）+ 用户原始问题与最终答案
        verify(recorder).recordTurn(anyString(), eq("chat-1"), eq("chat-1"),
                eq("帮我推荐一门课"), eq("推荐《Java并发实战》"));
    }

    @Test
    void stepsExhaustedForcesSummaryWithTransparencyNote() {
        AgentHarnessService harness = mock(AgentHarnessService.class);
        when(harness.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.enterprise.iqk.agent.harness.AgentObservation.success(
                        "builtin", java.util.Map.of("rows", 2), 3));
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        // 第 1 次调用 = 规划（rag_search，非 finish），第 2 次 = 步数用尽后的强制成稿
        when(callSpec.content()).thenReturn(
                "{\"thought\":\"先查库\",\"action\":\"rag_search\",\"action_input\":{\"query\":\"x\"}}",
                "已获取的部分结果汇总");
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new ModelRouter.ModelRouteDecision("balanced", "model-a", "standard", false, "profile_match", "", "", null)
        );
        // maxSteps=1 的智能体：单步未 finish 即触顶
        AgentRuntimeConfig config = new AgentRuntimeConfig(35L, "受限智能体", "PUBLISHED", "提示词",
                null, 10, 1, null, List.of(), false, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of());

        ReactAgentService service = newService(modelRouter, chatClient, harness,
                mock(MemoryService.class), mock(ChatTurnMemoryRecorder.class), config);
        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt("复杂问题");
        request.setChatId("chat-1");
        request.setModelProfile("balanced");
        request.setAgentId(35L);

        var response = service.chat(request);

        assertThat(response.getAnswer()).isEqualTo("已获取的部分结果汇总");
        // 成稿提示词带透明化说明：如实汇总 + 建议拆分提问
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(requestSpec, org.mockito.Mockito.times(2)).user(prompts.capture());
        assertThat(prompts.getAllValues().get(1))
                .contains("步数上限内未完成全部动作")
                .contains("拆分为多个小问题");
    }

    @Test
    void recallFailureDegradesToNoMemoryWithoutBreakingReactLoop() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(
                "{\"thought\":\"可直答\",\"action\":\"finish\",\"answer\":\"推荐《数据结构》\"}");
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new ModelRouter.ModelRouteDecision("balanced", "model-a", "standard", false, "profile_match", "", "", null)
        );
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.buildContext(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("memory down"));

        ReactAgentService service = newService(modelRouter, chatClient, mock(AgentHarnessService.class),
                memoryService, mock(ChatTurnMemoryRecorder.class));
        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt("帮我推荐一门课");
        request.setChatId("chat-1");
        request.setModelProfile("balanced");

        var response = service.chat(request);

        // 记忆挂了，ReAct 照常出答案，memoryUsed 为空
        assertThat(response.getAnswer()).isEqualTo("推荐《数据结构》");
        assertThat(response.getMemoryUsed()).isEmpty();
        ArgumentCaptor<String> plannerPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(plannerPrompt.capture());
        assertThat(plannerPrompt.getValue()).contains("(none)").doesNotContain("已知记忆:");
    }
}
