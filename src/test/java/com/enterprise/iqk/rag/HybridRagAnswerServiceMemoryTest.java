package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.memory.RagFactMemoryRecorder;
import com.enterprise.iqk.retrieval.CitationService;
import com.enterprise.iqk.retrieval.EvidenceJudgeService;
import com.enterprise.iqk.retrieval.HybridRetrievalService;
import com.enterprise.iqk.retrieval.HybridWeights;
import com.enterprise.iqk.retrieval.ScoredDocument;
import com.enterprise.iqk.retrieval.VectorRetriever;
import com.enterprise.iqk.service.TenantCostService;
import com.enterprise.iqk.testutil.TestGuards;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证记忆注入闭环：召回的画像/事实进入生成上下文，
 * 且 memoryUsed 正确标记本次回答实际使用了哪些记忆。
 */
class HybridRagAnswerServiceMemoryTest {

    private static final ModelRouter.ModelRouteDecision DECISION = new ModelRouter.ModelRouteDecision(
            "balanced", "test-model", "balanced", false, null, null, null, null);

    private HybridRagAnswerService service;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private MemoryService memoryService;

    private void setUp(String llmContent, MemoryService.MemoryContextSnapshot snapshot) {
        HybridRetrievalService retrievalService = mock(HybridRetrievalService.class);
        EvidenceJudgeService evidenceJudgeService = mock(EvidenceJudgeService.class);
        CitationService citationService = mock(CitationService.class);
        memoryService = mock(MemoryService.class);
        ModelRouter modelRouter = mock(ModelRouter.class);
        TenantCostService tenantCostService = mock(TenantCostService.class);
        RagProperties ragProperties = mock(RagProperties.class);

        ScoredDocument doc = ScoredDocument.builder()
                .docId("doc-1").sourceType("vector").title("redis.pdf")
                .content("缓存穿透的解决方案").retrievalScore(0.9)
                .metadata(Map.of()).build();
        when(retrievalService.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new HybridRetrievalService.HybridRetrievalResult(
                        List.of(doc), 1, 1, List.of(), HybridWeights.DEFAULT));
        when(evidenceJudgeService.judge(any(), anyString())).thenReturn(List.of());
        when(citationService.buildCitations(any())).thenReturn(List.of());
        when(citationService.formatCitationFooter(any())).thenReturn("");
        when(modelRouter.resolve(nullable(String.class), anyString(), anyString(), anyString()))
                .thenReturn(DECISION);
        when(tenantCostService.estimateTokens(anyString())).thenReturn(10L);
        when(ragProperties.getRetrieveTopK()).thenReturn(5);
        when(ragProperties.getTemperature()).thenReturn(0.7);
        // 无关线兜底：不桩则为 0.0，门槛静默失效——必须与生产语义一致
        when(ragProperties.getFallbackScoreFloor()).thenReturn(0.30);
        when(memoryService.buildContext("tenant-1", "chat-1", "缓存穿透怎么防")).thenReturn(snapshot);

        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(llmContent);

        service = new HybridRagAnswerService(retrievalService, mock(VectorRetriever.class),
                evidenceJudgeService, citationService, chatClient, modelRouter, ragProperties,
                new SimpleMeterRegistry(), tenantCostService,
                mock(RagFactMemoryRecorder.class), memoryService, TestGuards.real());
    }

    @Test
    void injectsRecalledMemoryIntoPromptAndReportsMemoryUsed() {
        MemoryItemRecord profile = MemoryItemRecord.builder()
                .memoryId("mem-l1").type("long").content("画像: 用户是 Java 后端开发者").build();
        MemoryItemRecord fact = MemoryItemRecord.builder()
                .memoryId("mem-f1").type("fact").content("事实: 布隆过滤器可防缓存穿透").build();
        setUp("答案 [1]", new MemoryService.MemoryContextSnapshot(
                "用户长期记忆:\n- 画像: 用户是 Java 后端开发者\n\n可信事实:\n- 事实: 布隆过滤器可防缓存穿透\n",
                List.of(), List.of(profile), List.of(fact)));

        HybridRagAnswerService.HybridRagResult result =
                service.answer("缓存穿透怎么防", "tenant-1", "chat-1", "conv-1", null);

        // 记忆进入生成上下文（画像与事实都要出现在 user prompt 里）
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPrompt.capture());
        assertThat(userPrompt.getValue()).contains("已知记忆")
                .contains("Java 后端开发者")
                .contains("布隆过滤器可防缓存穿透");

        // memoryUsed 标记实际使用的记忆
        assertThat(result.getMemoryUsed())
                .contains("long: 画像: 用户是 Java 后端开发者")
                .contains("fact: 事实: 布隆过滤器可防缓存穿透");
    }

    @Test
    void recallFailureDegradesToNoMemoryWithoutBreakingPipeline() {
        setUp("答案 [1]", null);
        when(memoryService.buildContext("tenant-1", "chat-1", "缓存穿透怎么防"))
                .thenThrow(new RuntimeException("memory down"));

        HybridRagAnswerService.HybridRagResult result =
                service.answer("缓存穿透怎么防", "tenant-1", "chat-1", "conv-1", null);

        // 记忆挂了，RAG 依然出答案
        assertThat(result.getAnswer()).isEqualTo("答案 [1]");
        assertThat(result.getMemoryUsed()).isEmpty();
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPrompt.capture());
        assertThat(userPrompt.getValue()).doesNotContain("已知记忆");
    }
}
