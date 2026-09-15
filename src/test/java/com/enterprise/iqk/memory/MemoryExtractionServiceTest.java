package com.enterprise.iqk.memory;

import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.service.TenantCostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.ai.chat.client.ChatClient;

class MemoryExtractionServiceTest {

    private static final ModelRouter.ModelRouteDecision DECISION = new ModelRouter.ModelRouteDecision(
            "economy", "test-mini", "economy", false, null, null, null, null);

    private MemoryService memoryService;
    private ChatClient chatClient;
    private ModelRouter modelRouter;
    private TenantCostService tenantCostService;
    private MemoryExtractionService service;

    private void setUp(String llmContent) {
        memoryService = mock(MemoryService.class);
        modelRouter = mock(ModelRouter.class);
        tenantCostService = mock(TenantCostService.class);

        // 用 RETURNS_SELF 模拟 ChatClient 的链式调用，只关心最终的 content()
        chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec =
                mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(llmContent);

        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(DECISION);

        service = new MemoryExtractionService(memoryService, chatClient, modelRouter,
                tenantCostService, new ObjectMapper());
    }

    @Test
    void upgradesToLongMemoryWhenModelDetectsProfile() {
        setUp("{\"isProfile\":true,\"memory\":\"用户是 Java 后端开发者，正在学习 AI Agent 开发\"}");

        service.extract("tenant-1", "chat-1", "我是做Java后端的，想学Agent开发", "推荐如下…");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(memoryService).saveLongMemory(eq("tenant-1"), eq("chat-1"), content.capture(),
                eq("extract:chat:chat-1"));
        assertThat(content.getValue())
                .startsWith("画像: ")
                .contains("Java 后端开发者");
        // 提取调用计入租户成本（economy 档）
        verify(tenantCostService).recordUsage(eq("tenant-1"), eq("economy"), anyLong(), anyLong(),
                eq("memory_extraction"));
    }

    @Test
    void doesNotUpgradeWhenVerdictIsNegative() {
        setUp("{\"isProfile\":false,\"memory\":\"\"}");

        service.extract("tenant-1", "chat-1", "Redis 缓存穿透怎么解决", "方案是…");

        verify(memoryService, never()).saveLongMemory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void doesNotUpgradeWhenResponseIsUnparsable() {
        setUp("这不是 JSON，模型偶尔会这样回答");

        assertThatCode(() -> service.extract("tenant-1", "chat-1", "我喜欢用 IDEA", "嗯"))
                .doesNotThrowAnyException();
        verify(memoryService, never()).saveLongMemory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void doesNotUpgradeWhenDuplicateExists() {
        setUp("{\"isProfile\":true,\"memory\":\"用户是 Java 后端开发者\"}");
        when(memoryService.queryLongMemory(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(MemoryItemRecord.builder()
                        .content("画像: 用户是 Java 后端开发者，主攻微服务")
                        .build()));

        service.extract("tenant-1", "chat-1", "我是做Java后端的", "推荐…");

        verify(memoryService, never()).saveLongMemory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void swallowsLlmFailureWithoutPropagating() {
        setUp("unused");
        when(chatClient.prompt()).thenThrow(new RuntimeException("llm down"));

        assertThatCode(() -> service.extract("tenant-1", "chat-1", "我是做Java后端的", "推荐…"))
                .doesNotThrowAnyException();
        verify(memoryService, never()).saveLongMemory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void parseVerdictToleratesCodeFencedJson() {
        setUp("unused");

        MemoryExtractionService.ExtractionVerdict verdict = service.parseVerdict(
                "```json\n{\"isProfile\":true,\"memory\":\"用户偏好简洁回答\"}\n```");

        assertThat(verdict).isNotNull();
        assertThat(verdict.isProfile()).isTrue();
        assertThat(verdict.memory()).isEqualTo("用户偏好简洁回答");
    }

    @Test
    void submitAsyncSkipsBlankPromptWithoutCallingModel() {
        setUp("unused");

        service.submitAsync("tenant-1", "chat-1", "   ", "answer");

        verify(chatClient, never()).prompt();
    }
}
