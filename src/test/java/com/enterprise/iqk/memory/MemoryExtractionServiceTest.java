package com.enterprise.iqk.memory;

import com.enterprise.iqk.config.properties.MemoryProperties;
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
import static org.mockito.ArgumentMatchers.contains;
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
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ModelRouter modelRouter;
    private TenantCostService tenantCostService;
    private MemoryEmbeddingHelper embeddingHelper;
    private MemoryProperties memoryProperties;
    private MemoryExtractionService service;

    private void setUp(String llmContent) {
        memoryService = mock(MemoryService.class);
        modelRouter = mock(ModelRouter.class);
        tenantCostService = mock(TenantCostService.class);
        embeddingHelper = mock(MemoryEmbeddingHelper.class);
        memoryProperties = new MemoryProperties();
        // 语义判重默认关闭：既有用例验证文本判重与提取主链路，语义路径单独用例覆盖
        memoryProperties.setSemanticEnabled(false);

        // 用 RETURNS_SELF 模拟 ChatClient 的链式调用，只关心最终的 content()
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(llmContent);

        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(DECISION);

        service = new MemoryExtractionService(memoryService, chatClient, modelRouter,
                tenantCostService, new ObjectMapper(), embeddingHelper, memoryProperties);
    }

    @Test
    void upgradesToLongMemoryWhenModelDetectsProfile() {
        setUp("{\"action\":\"add\",\"memory\":\"用户是 Java 后端开发者，正在学习 AI Agent 开发\"}");

        service.extract("tenant-1", "user-1", "chat-1", "我是做Java后端的，想学Agent开发", "推荐如下…");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(memoryService).saveLongMemory(eq("tenant-1"), eq("user-1"), content.capture(),
                eq("extract:chat:chat-1"));
        assertThat(content.getValue())
                .startsWith("画像: ")
                .contains("Java 后端开发者");
        // add 路径不涉及纠偏软失效
        verify(memoryService, never()).softInvalidateMemory(anyString(), anyString(), anyString());
        // 提取调用计入租户成本（economy 档；user 键=认证主体）
        verify(tenantCostService).recordUsage(eq("tenant-1"), eq("user-1"), eq("economy"), anyLong(), anyLong(),
                eq("memory_extraction"));
    }

    @Test
    void correctSupersedesExistingProfile() {
        setUp("{\"action\":\"correct\",\"memory\":\"用户主攻 AI Agent 开发\",\"supersedes\":\"画像: 用户做 Java 服务端开发\"}");
        when(memoryService.queryLongMemory(eq("tenant-1"), eq("user-1"), anyInt()))
                .thenReturn(List.of(MemoryItemRecord.builder()
                        .memoryId("mem-old")
                        .content("画像: 用户做 Java 服务端开发")
                        .build()));

        service.extract("tenant-1", "user-1", "chat-1", "不对，我现在主要做 Agent 开发了", "明白");

        // 旧画像软失效（INVALIDATE 留痕），新画像照常写入——新旧矛盾画像不再共存
        verify(memoryService).softInvalidateMemory(eq("tenant-1"), eq("mem-old"),
                contains("correction"));
        verify(memoryService).saveLongMemory(eq("tenant-1"), eq("user-1"),
                eq("画像: 用户主攻 AI Agent 开发"), eq("extract:chat:chat-1"));
    }

    @Test
    void correctWithUnmatchedSupersedesFallsBackToAdd() {
        setUp("{\"action\":\"correct\",\"memory\":\"用户主攻 AI Agent 开发\",\"supersedes\":\"画像: 不存在的旧画像\"}");
        when(memoryService.queryLongMemory(eq("tenant-1"), eq("user-1"), anyInt()))
                .thenReturn(List.of(MemoryItemRecord.builder()
                        .memoryId("mem-other")
                        .content("画像: 用户偏好简洁回答")
                        .build()));

        service.extract("tenant-1", "user-1", "chat-1", "我现在主要做 Agent 开发了", "好");

        // 摘抄对不上号：绝不盲删，退化为 add 路径
        verify(memoryService, never()).softInvalidateMemory(anyString(), anyString(), anyString());
        verify(memoryService).saveLongMemory(eq("tenant-1"), eq("user-1"),
                eq("画像: 用户主攻 AI Agent 开发"), eq("extract:chat:chat-1"));
    }

    @Test
    void semanticDuplicateBlocksParaphrasedProfile() {
        setUp("{\"action\":\"add\",\"memory\":\"用户是一名 Java 后端工程师\"}");
        // 开启语义判重：既有画像换措辞（余弦 ≥ 阈值）时拦住新画像入库
        memoryProperties.setSemanticEnabled(true);
        MemoryItemRecord existing = MemoryItemRecord.builder()
                .content("画像: 用户做 Java 服务端开发")
                .embeddingJson("[1.0,0.0]")
                .build();
        when(memoryService.queryLongMemory(eq("tenant-1"), eq("user-1"), anyInt()))
                .thenReturn(java.util.List.of(existing));
        when(embeddingHelper.embed(anyString())).thenReturn(new float[]{0.99f, 0.1f});

        service.extract("tenant-1", "user-1", "chat-1", "我是写Java后端的", "推荐如下…");

        verify(memoryService, never()).saveLongMemory(anyString(), anyString(), anyString(),
                anyString());
        // 既有向量直接消费缓存，只有候选画像花一次 embedding
        verify(embeddingHelper, org.mockito.Mockito.times(1)).embed(anyString());
    }

    @Test
    void doesNotUpgradeWhenVerdictIsNone() {
        setUp("{\"action\":\"none\",\"memory\":\"\"}");

        service.extract("tenant-1", "user-1", "chat-1", "Redis 缓存穿透怎么解决", "方案是…");

        verify(memoryService, never()).saveLongMemory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void doesNotUpgradeWhenResponseIsUnparsable() {
        setUp("这不是 JSON，模型偶尔会这样回答");

        assertThatCode(() -> service.extract("tenant-1", "user-1", "chat-1", "我喜欢用 IDEA", "嗯"))
                .doesNotThrowAnyException();
        verify(memoryService, never()).saveLongMemory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void doesNotUpgradeWhenDuplicateExists() {
        setUp("{\"action\":\"add\",\"memory\":\"用户是 Java 后端开发者\"}");
        when(memoryService.queryLongMemory(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(MemoryItemRecord.builder()
                        .content("画像: 用户是 Java 后端开发者，主攻微服务")
                        .build()));

        service.extract("tenant-1", "user-1", "chat-1", "我是做Java后端的", "推荐…");

        verify(memoryService, never()).saveLongMemory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void swallowsLlmFailureWithoutPropagating() {
        setUp("unused");
        when(chatClient.prompt()).thenThrow(new RuntimeException("llm down"));

        assertThatCode(() -> service.extract("tenant-1", "user-1", "chat-1", "我是做Java后端的", "推荐…"))
                .doesNotThrowAnyException();
        verify(memoryService, never()).saveLongMemory(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void parseVerdictToleratesCodeFencedJson() {
        setUp("unused");

        MemoryExtractionService.ExtractionVerdict verdict = service.parseVerdict(
                "```json\n{\"action\":\"correct\",\"memory\":\"用户偏好简洁回答\",\"supersedes\":\"画像: 喜欢长篇大论\"}\n```");

        assertThat(verdict).isNotNull();
        assertThat(verdict.action()).isEqualTo("correct");
        assertThat(verdict.memory()).isEqualTo("用户偏好简洁回答");
        assertThat(verdict.supersedes()).isEqualTo("画像: 喜欢长篇大论");
    }

    @Test
    void parseVerdictSupportsLegacyIsProfileFormat() {
        setUp("unused");

        MemoryExtractionService.ExtractionVerdict legacyTrue = service.parseVerdict(
                "{\"isProfile\":true,\"memory\":\"用户是 Java 后端开发者\"}");
        MemoryExtractionService.ExtractionVerdict legacyFalse = service.parseVerdict(
                "{\"isProfile\":false,\"memory\":\"\"}");

        assertThat(legacyTrue.action()).isEqualTo("add");
        assertThat(legacyTrue.memory()).isEqualTo("用户是 Java 后端开发者");
        assertThat(legacyFalse.action()).isEqualTo("none");
    }

    @Test
    void parseVerdictMapsUnknownActionToNone() {
        setUp("unused");

        MemoryExtractionService.ExtractionVerdict verdict = service.parseVerdict(
                "{\"action\":\"delete\",\"memory\":\"什么都行\"}");

        assertThat(verdict).isNotNull();
        assertThat(verdict.action()).isEqualTo("none");
    }

    @Test
    void existingMemoriesAreCarriedIntoUserPromptForCorrectionMatching() {
        setUp("{\"action\":\"none\",\"memory\":\"\"}");
        when(memoryService.queryLongMemory(eq("tenant-1"), eq("user-1"), anyInt()))
                .thenReturn(List.of(MemoryItemRecord.builder()
                        .content("画像: 用户做 Java 服务端开发")
                        .build()));

        service.extract("tenant-1", "user-1", "chat-1", "我喜欢简洁的回答", "好");

        // 既有画像块拼进用户消息（模型判 correct 的依据）
        org.mockito.ArgumentCaptor<String> userPrompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPrompt.capture());
        assertThat(userPrompt.getValue()).contains("既有画像记忆").contains("Java 服务端开发");
    }

    @Test
    void submitAsyncSkipsBlankPromptWithoutCallingModel() {
        setUp("unused");

        service.submitAsync("tenant-1", "user-1", "chat-1", "   ", "answer");

        verify(chatClient, never()).prompt();
    }
}
