package com.enterprise.iqk.service;

import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.ChatTurnMemoryRecorder;
import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactAgentServiceTest {

    @Test
    void rejectsRequestsWithoutPromptOrChatIdBeforeCallingExternalDependencies() {
        ReactAgentService service = new ReactAgentService(
                mock(AgentHarnessService.class),
                mock(ChatClient.class),
                mock(ModelRouter.class),
                mock(TenantCostService.class),
                mock(MeterRegistry.class),
                new ReactDecisionParser(new ObjectMapper()),
                new ReactResponseFormatter(new ObjectMapper()),
                mock(MemoryService.class),
                mock(ChatTurnMemoryRecorder.class)
        );
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
        ReactAgentService service = new ReactAgentService(
                harness,
                chatClient,
                modelRouter,
                mock(TenantCostService.class),
                mock(MeterRegistry.class),
                new ReactDecisionParser(new ObjectMapper()),
                new ReactResponseFormatter(new ObjectMapper()),
                mock(MemoryService.class),
                mock(ChatTurnMemoryRecorder.class)
        );
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
        when(memoryService.buildContext(anyString(), anyString())).thenReturn(
                new MemoryService.MemoryContextSnapshot(
                        "用户长期记忆:\n- 画像: 用户是 Java 后端开发者\n",
                        List.of(), List.of(profile), List.of()));
        ChatTurnMemoryRecorder recorder = mock(ChatTurnMemoryRecorder.class);

        ReactAgentService service = new ReactAgentService(
                mock(AgentHarnessService.class),
                chatClient,
                modelRouter,
                mock(TenantCostService.class),
                mock(MeterRegistry.class),
                new ReactDecisionParser(new ObjectMapper()),
                new ReactResponseFormatter(new ObjectMapper()),
                memoryService,
                recorder
        );
        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt("帮我推荐一门课");
        request.setChatId("chat-1");
        request.setModelProfile("balanced");

        var response = service.chat(request);

        // 记忆进入 planner 的提示词（读侧闭环）
        ArgumentCaptor<String> plannerPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(plannerPrompt.capture());
        assertThat(plannerPrompt.getValue())
                .contains("已知记忆")
                .contains("用户是 Java 后端开发者");
        // memoryUsed 上报实际注入的记忆
        assertThat(response.getMemoryUsed()).contains("long: 画像: 用户是 Java 后端开发者");
        // 成稿写回 short 记忆（写侧闭环）：user 键（匿名回落 chatId）+ 用户原始问题与最终答案
        verify(recorder).recordTurn(anyString(), eq("chat-1"), eq("chat-1"),
                eq("帮我推荐一门课"), eq("推荐《Java并发实战》"));
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
        when(memoryService.buildContext(anyString(), anyString()))
                .thenThrow(new RuntimeException("memory down"));

        ReactAgentService service = new ReactAgentService(
                mock(AgentHarnessService.class),
                chatClient,
                modelRouter,
                mock(TenantCostService.class),
                mock(MeterRegistry.class),
                new ReactDecisionParser(new ObjectMapper()),
                new ReactResponseFormatter(new ObjectMapper()),
                memoryService,
                mock(ChatTurnMemoryRecorder.class)
        );
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
