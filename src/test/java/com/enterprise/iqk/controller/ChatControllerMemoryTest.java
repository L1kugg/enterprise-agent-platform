package com.enterprise.iqk.controller;

import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.ChatTurnMemoryRecorder;
import com.enterprise.iqk.memory.MemoryExtractionService;
import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import com.enterprise.iqk.service.TenantCostService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 chat 链路的记忆注入闭环：跨会话记忆（long/fact）追加进
 * 发给模型的用户消息；记忆写入仍用原始 prompt（防自我循环）；
 * 召回失败降级为无记忆，绝不中断聊天。
 */
class ChatControllerMemoryTest {

    private static final ModelRouter.ModelRouteDecision DECISION = new ModelRouter.ModelRouteDecision(
            "balanced", "test-model", "balanced", false, null, null, null, null);

    private ChatController controller;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private MemoryService memoryService;
    private ChatTurnMemoryRecorder recorder;
    private MemoryExtractionService extractionService;

    private void setUp(MemoryService.MemoryContextSnapshot snapshot) {
        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("答案文本"));

        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.resolve(nullable(String.class), anyString(), anyString(), anyString()))
                .thenReturn(DECISION);
        memoryService = mock(MemoryService.class);
        when(memoryService.buildContext(anyString(), eq("chat-1"), eq(false))).thenReturn(snapshot);
        recorder = mock(ChatTurnMemoryRecorder.class);
        extractionService = mock(MemoryExtractionService.class);

        controller = new ChatController(
                chatClient,
                modelRouter,
                mock(TenantCostService.class),
                mock(ChatHistoryRepository.class),
                recorder,
                extractionService,
                memoryService
        );
    }

    @Test
    void appendsCrossSessionMemoryToUserPromptButStoresOriginalPrompt() {
        MemoryItemRecord profile = MemoryItemRecord.builder()
                .memoryId("mem-l1").type("long").content("画像: 用户是 Java 后端开发者").build();
        MemoryItemRecord fact = MemoryItemRecord.builder()
                .memoryId("mem-f1").type("fact").content("事实: 布隆过滤器可防缓存穿透").build();
        setUp(new MemoryService.MemoryContextSnapshot(
                "用户长期记忆:\n- 画像: 用户是 Java 后端开发者\n\n可信事实:\n- 事实: 布隆过滤器可防缓存穿透\n",
                List.of(), List.of(profile), List.of(fact)));

        List<String> output = controller.chat("缓存穿透怎么防", "chat-1", null, null)
                .collectList().block();

        assertThat(output).containsExactly("答案文本");
        // 发给模型的用户消息末尾带已知记忆段（long/fact 跨会话视图，short 留给 ChatMemory）
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPrompt.capture());
        assertThat(userPrompt.getValue())
                .startsWith("缓存穿透怎么防")
                .contains("已知记忆")
                .contains("Java 后端开发者")
                .contains("布隆过滤器可防缓存穿透")
                .doesNotContain("近期对话要点");
        // short 记忆与画像提取写入的是用户原始问题，不是增强后 prompt —— 否则记忆自我循环增殖
        verify(recorder).recordTurn(anyString(), eq("chat-1"), eq("缓存穿透怎么防"), eq("答案文本"));
        verify(extractionService).submitAsync(anyString(), eq("chat-1"), eq("缓存穿透怎么防"), eq("答案文本"));
    }

    @Test
    void recallFailureDegradesToPlainChatWithoutMemory() {
        setUp(null);
        when(memoryService.buildContext(anyString(), eq("chat-1"), eq(false)))
                .thenThrow(new RuntimeException("memory down"));

        List<String> output = controller.chat("缓存穿透怎么防", "chat-1", null, null)
                .collectList().block();

        // 记忆挂了，聊天照常返回
        assertThat(output).containsExactly("答案文本");
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPrompt.capture());
        assertThat(userPrompt.getValue()).isEqualTo("缓存穿透怎么防");
        verify(recorder).recordTurn(anyString(), eq("chat-1"), eq("缓存穿透怎么防"), eq("答案文本"));
    }
}
