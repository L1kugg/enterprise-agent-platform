package com.enterprise.iqk.controller;

import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.ChatTurnMemoryRecorder;
import com.enterprise.iqk.memory.MemoryExtractionService;
import com.enterprise.iqk.memory.MemoryInjectionAdvisor;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.service.TenantCostService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * 验证 chat 链路的记忆闭环接线：user 消息保持原文（记忆经
 * MemoryInjectionAdvisor 以 system 消息注入，不落 ChatMemory、
 * 不随历史累积）；记忆写入按 user 键（匿名回落 chatId）+ 原始问题。
 */
class ChatControllerMemoryTest {

    private static final ModelRouter.ModelRouteDecision DECISION = new ModelRouter.ModelRouteDecision(
            "balanced", "test-model", "balanced", false, null, null, null, null);

    private ChatController controller;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatTurnMemoryRecorder recorder;
    private MemoryExtractionService extractionService;

    @SuppressWarnings("unchecked")
    private void setUp() {
        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("答案文本"));

        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.resolve(nullable(String.class), anyString(), anyString(), anyString()))
                .thenReturn(DECISION);
        recorder = mock(ChatTurnMemoryRecorder.class);
        extractionService = mock(MemoryExtractionService.class);

        controller = new ChatController(
                chatClient,
                modelRouter,
                mock(TenantCostService.class),
                mock(ChatHistoryRepository.class),
                recorder,
                extractionService
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsUserPromptUntouchedAndPassesMemoryKeysToAdvisor() {
        setUp();
        List<String> output = controller.chat("缓存穿透怎么防", "chat-1", null, null)
                .collectList().block();

        assertThat(output).containsExactly("答案文本");
        // user 消息保持用户原文 —— 记忆不再拼进 user prompt（拼进去会被
        // MessageChatMemoryAdvisor 持久化并在后续轮次重放，逐轮累积）
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPrompt.capture());
        assertThat(userPrompt.getValue()).isEqualTo("缓存穿透怎么防");
        // 记忆注入以 advisor 参数显式声明（租户 + user 键，匿名回落 chatId）
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> advisorCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(requestSpec).advisors(advisorCaptor.capture());
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class, RETURNS_SELF);
        advisorCaptor.getValue().accept(advisorSpec);
        verify(advisorSpec).param(eq(CONVERSATION_ID), anyString());
        verify(advisorSpec).param(eq(MemoryInjectionAdvisor.MEMORY_TENANT_KEY), anyString());
        verify(advisorSpec).param(eq(MemoryInjectionAdvisor.MEMORY_USER_KEY), eq("chat-1"));
        // short 记忆与画像提取：user 键 + 用户原始问题
        verify(recorder).recordTurn(anyString(), eq("chat-1"), eq("chat-1"),
                eq("缓存穿透怎么防"), eq("答案文本"));
        verify(extractionService).submitAsync(anyString(), eq("chat-1"), eq("chat-1"),
                eq("缓存穿透怎么防"), eq("答案文本"));
    }
}
