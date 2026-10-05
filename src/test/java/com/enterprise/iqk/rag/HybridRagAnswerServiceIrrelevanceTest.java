package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.llm.ModelRouter;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 无关线兜底闭环：全路原始检索分低于 rag.fallback-score-floor 时，
 * 先走向量放宽阈值重试一次；仍无过线文档则不调模型直接返回固定话术——
 * 与主聊天 RagAnswerService 的"完全无关拒答"防线同语义（主聊天 rag_search 切四路后承接）。
 */
class HybridRagAnswerServiceIrrelevanceTest {

    private static final ModelRouter.ModelRouteDecision DECISION = new ModelRouter.ModelRouteDecision(
            "balanced", "test-model", "balanced", false, null, null, null, null);

    private HybridRetrievalService retrievalService;
    private VectorRetriever vectorRetriever;
    private EvidenceJudgeService evidenceJudgeService;
    private RagFactMemoryRecorder factRecorder;
    private ChatClient chatClient;
    private ModelRouter modelRouter;
    private RagProperties ragProperties;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private HybridRagAnswerService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        retrievalService = mock(HybridRetrievalService.class);
        vectorRetriever = mock(VectorRetriever.class);
        evidenceJudgeService = mock(EvidenceJudgeService.class);
        CitationService citationService = mock(CitationService.class);
        chatClient = mock(ChatClient.class);
        modelRouter = mock(ModelRouter.class);
        ragProperties = mock(RagProperties.class);
        factRecorder = mock(RagFactMemoryRecorder.class);
        meterRegistry = new SimpleMeterRegistry();

        // 四路严格检索返回的文档分数由各用例桩定，这里只定通用行为
        when(evidenceJudgeService.judge(any(), anyString())).thenReturn(List.of());
        when(citationService.buildCitations(any())).thenReturn(List.of());
        when(citationService.formatCitationFooter(any())).thenReturn("");
        when(modelRouter.resolve(nullable(String.class), anyString(), anyString(), anyString()))
                .thenReturn(DECISION);
        when(ragProperties.getRetrieveTopK()).thenReturn(5);
        when(ragProperties.getRerankTopK()).thenReturn(6);
        when(ragProperties.getTemperature()).thenReturn(0.7);
        // 无关线：不桩则为 0.0，门槛静默失效——必须与生产语义一致
        when(ragProperties.getFallbackScoreFloor()).thenReturn(0.30);

        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("答案");

        service = new HybridRagAnswerService(retrievalService, vectorRetriever,
                evidenceJudgeService, citationService, chatClient, modelRouter, ragProperties,
                meterRegistry, mock(TenantCostService.class), factRecorder, mock(MemoryService.class),
                TestGuards.real());
    }

    private void strictRetrievalReturns(ScoredDocument... docs) {
        when(retrievalService.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new HybridRetrievalService.HybridRetrievalResult(
                        List.of(docs), docs.length, docs.length, List.of(), HybridWeights.DEFAULT));
    }

    private ScoredDocument doc(String id, double retrievalScore) {
        return ScoredDocument.builder()
                .docId(id).sourceType("vector").title("手册.pdf")
                .chunkId(id).content(id + " 正文")
                .retrievalScore(retrievalScore).metadata(Map.of()).build();
    }

    @Test
    void allDocsBelowFloorShortCircuitsToFixedMessageWithoutModelCall() {
        strictRetrievalReturns(doc("doc-a", 0.10), doc("doc-b", 0.20));
        when(vectorRetriever.retrieve(anyString(), anyString(), anyString(),
                eq(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL))).thenReturn(List.of());

        HybridRagAnswerService.HybridRagResult result =
                service.answer("推荐几个长春美食", "tenant-1", "chat-1", "conv-1", null);

        assertThat(result.getAnswer()).isEqualTo("没有在当前知识库中检索到可用内容。");
        // 提示行借 EvidenceItem.snippet 装载（观测载荷以文本呈现，对齐旧单路空结果提示）
        assertThat(result.getEvidence()).singleElement().satisfies(item ->
                assertThat(item.getSnippet()).isEqualTo("未检索到匹配文档，请先上传资料或调整检索词。"));
        assertThat(result.getCitations()).isEmpty();
        // 权重照常回传（前端色条在"没找到"时也要画）
        assertThat(result.getWeights())
                .containsEntry("vector", 0.40).containsEntry("keyword", 0.25)
                .containsEntry("graph", 0.20).containsEntry("web", 0.15);
        assertThat(result.getWeights().keySet())
                .containsExactly("vector", "keyword", "graph", "web");
        // 低于线的文档不进判分、不进事实沉淀、不调模型
        verifyNoInteractions(evidenceJudgeService, factRecorder, chatClient, modelRouter);
        assertThat(meterRegistry.get("rag.hybrid.pipeline.requests")
                .tag("outcome", "empty").counter().count()).isEqualTo(1.0);
    }

    @Test
    void gateRejectedThenRelaxedRetryAboveFloorRunsFullPipeline() {
        strictRetrievalReturns(doc("doc-a", 0.10), doc("doc-b", 0.20));
        ScoredDocument rescued = doc("doc-rescued", 0.55);
        when(vectorRetriever.retrieve(anyString(), anyString(), anyString(),
                eq(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL))).thenReturn(List.of(rescued));
        // 手动捕获判分入参（List 泛型 captor 需强转，这里用 thenAnswer 更直白）
        List<List<ScoredDocument>> judgedInputs = new ArrayList<>();
        when(evidenceJudgeService.judge(any(), anyString())).thenAnswer(invocation -> {
            judgedInputs.add(invocation.getArgument(0));
            return List.of();
        });

        HybridRagAnswerService.HybridRagResult result =
                service.answer("这份文档讲了什么", "tenant-1", "chat-1", "conv-1", null);

        // 重试捞回的文档走完整管线：判分 → 上下文 → 生成
        assertThat(judgedInputs).hasSize(1);
        assertThat(judgedInputs.get(0)).hasSize(1);
        // 重试文档不经 HybridRetrievalService 加权，finalScore 按向量权重补齐（判分相关度基底）
        assertThat(judgedInputs.get(0).get(0).getFinalScore())
                .isCloseTo(0.55 * HybridWeights.DEFAULT.vectorWeight(), within(1e-9));
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPrompt.capture());
        assertThat(userPrompt.getValue()).contains("doc-rescued 正文");
        assertThat(result.getRetrievalStats().getFinalCount()).isEqualTo(1);
    }

    @Test
    void relaxedRetryFailureDegradesToFixedMessageNotError() {
        strictRetrievalReturns(doc("doc-a", 0.10));
        when(vectorRetriever.retrieve(anyString(), anyString(), anyString(),
                eq(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)))
                .thenThrow(new RuntimeException("vector down"));

        HybridRagAnswerService.HybridRagResult result =
                service.answer("随便问问", "tenant-1", "chat-1", "conv-1", null);

        // 兜底通道的故障不升级成整次回答 error，按"无可用内容"处理
        assertThat(result.getAnswer()).isEqualTo("没有在当前知识库中检索到可用内容。");
        assertThat(meterRegistry.get("rag.hybrid.pipeline.requests")
                .tag("outcome", "empty").counter().count()).isEqualTo(1.0);
        verifyNoInteractions(chatClient);
    }

    @Test
    void retryResultStillBelowFloorReturnsFixedMessage() {
        strictRetrievalReturns(doc("doc-a", 0.10));
        when(vectorRetriever.retrieve(anyString(), anyString(), anyString(),
                eq(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL))).thenReturn(List.of(doc("doc-r", 0.20)));

        HybridRagAnswerService.HybridRagResult result =
                service.answer("随便问问", "tenant-1", "chat-1", "conv-1", null);

        assertThat(result.getAnswer()).isEqualTo("没有在当前知识库中检索到可用内容。");
        verifyNoInteractions(chatClient);
    }
}
