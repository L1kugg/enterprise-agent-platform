package com.enterprise.iqk.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryInjectionAdvisor 的行为契约：
 * 注入 = 在 prompt 首部插一条独立 system 消息（不落 ChatMemory、
 * 不改 user 消息文本）；未传参/召回失败一律透传原请求。
 */
class MemoryInjectionAdvisorTest {

    private MemoryService memoryService = mock(MemoryService.class);
    private MemoryInjectionAdvisor advisor = new MemoryInjectionAdvisor(memoryService);

    private ChatClientRequest request(Map<String, Object> context) {
        return new ChatClientRequest(new Prompt(List.of(new UserMessage("缓存穿透怎么防"))), context);
    }

    @Test
    void injectsMemoryAsLeadingSystemMessageWithoutTouchingUserText() {
        when(memoryService.buildContext("tenant-1", "user-1", false)).thenReturn(
                new MemoryService.MemoryContextSnapshot(
                        "用户长期记忆:\n- 画像: 用户是 Java 后端开发者\n",
                        List.of(), List.of(), List.of()));

        ChatClientRequest enriched = advisor.before(request(Map.of(
                MemoryInjectionAdvisor.MEMORY_TENANT_KEY, "tenant-1",
                MemoryInjectionAdvisor.MEMORY_USER_KEY, "user-1")), null);

        List<Message> messages = enriched.prompt().getInstructions();
        // 首部插入 system 记忆消息，user 消息保持原文
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(messages.get(0).getText())
                .contains("已知记忆")
                .contains("Java 后端开发者");
        assertThat(messages.get(1).getText()).isEqualTo("缓存穿透怎么防");
        // 默认不带 short 层（挂 ChatMemory 的链路防双份）
        verify(memoryService).buildContext("tenant-1", "user-1", false);
    }

    @Test
    void forwardsIncludeShortFlagWhenExplicitlyRequested() {
        when(memoryService.buildContext(anyString(), anyString(), anyBoolean())).thenReturn(
                new MemoryService.MemoryContextSnapshot("近期对话要点:\n- Q: x", List.of(), List.of(), List.of()));

        advisor.before(request(Map.of(
                MemoryInjectionAdvisor.MEMORY_TENANT_KEY, "tenant-1",
                MemoryInjectionAdvisor.MEMORY_USER_KEY, "user-1",
                MemoryInjectionAdvisor.MEMORY_INCLUDE_SHORT_KEY, "true")), null);

        verify(memoryService).buildContext("tenant-1", "user-1", true);
    }

    @Test
    void passesThroughUntouchedWhenMemoryKeysAreMissing() {
        ChatClientRequest original = request(Map.of("conversationId", "conv-1"));

        ChatClientRequest result = advisor.before(original, null);

        // 未显式 opt-in 的链路（react / 评测 / 画像提取）零影响：原样透传
        assertThat(result).isSameAs(original);
    }

    @Test
    void recallFailureDegradesToOriginalRequest() {
        when(memoryService.buildContext(anyString(), anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("memory down"));
        ChatClientRequest original = request(Map.of(
                MemoryInjectionAdvisor.MEMORY_TENANT_KEY, "tenant-1",
                MemoryInjectionAdvisor.MEMORY_USER_KEY, "user-1"));

        // 召回挂了不拖垮生成：静默透传原请求
        assertThat(advisor.before(original, null)).isSameAs(original);
    }

    @Test
    void blankUserKeyOrEmptySnapshotAlsoPassesThrough() {
        // user 键为空串（调用方声明宁可不注入）不召回，原样透传
        ChatClientRequest blankUser = request(Map.of(
                MemoryInjectionAdvisor.MEMORY_TENANT_KEY, "tenant-1",
                MemoryInjectionAdvisor.MEMORY_USER_KEY, ""));
        assertThat(advisor.before(blankUser, null)).isSameAs(blankUser);
        // 空快照（无记忆可用）也透传
        when(memoryService.buildContext(eq("tenant-1"), eq("user-1"), anyBoolean())).thenReturn(
                new MemoryService.MemoryContextSnapshot("", List.of(), List.of(), List.of()));
        ChatClientRequest emptySnapshot = request(Map.of(
                MemoryInjectionAdvisor.MEMORY_TENANT_KEY, "tenant-1",
                MemoryInjectionAdvisor.MEMORY_USER_KEY, "user-1"));
        assertThat(advisor.before(emptySnapshot, null)).isSameAs(emptySnapshot);
    }
}
