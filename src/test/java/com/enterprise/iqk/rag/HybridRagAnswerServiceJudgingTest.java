package com.enterprise.iqk.rag;

import com.enterprise.iqk.config.properties.RagProperties;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.memory.RagFactMemoryRecorder;
import com.enterprise.iqk.retrieval.CitationService;
import com.enterprise.iqk.retrieval.EvidenceItem;
import com.enterprise.iqk.retrieval.EvidenceJudgeService;
import com.enterprise.iqk.retrieval.HybridRetrievalService;
import com.enterprise.iqk.retrieval.ScoredDocument;
import com.enterprise.iqk.service.TenantCostService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证证据判分的"消费端"闭环：判分结果决定进入生成上下文的文档集
 * （按综合分降序、剔除低于垃圾线的证据、截断 rerankTopK）——
 * 此前 buildContext 用的是原始检索序，评审只喂展示层，对答案质量零作用。
 */
class HybridRagAnswerServiceJudgingTest {

    private static final ModelRouter.ModelRouteDecision DECISION = new ModelRouter.ModelRouteDecision(
            "balanced", "test-model", "balanced", false, null, null, null, null);

    private HybridRagAnswerService service;
    private ChatClient.ChatClientRequestSpec requestSpec;

    private void setUp(List<EvidenceItem> judgedEvidence) {
        HybridRetrievalService retrievalService = mock(HybridRetrievalService.class);
        EvidenceJudgeService evidenceJudgeService = mock(EvidenceJudgeService.class);
        CitationService citationService = mock(CitationService.class);
        ModelRouter modelRouter = mock(ModelRouter.class);
        TenantCostService tenantCostService = mock(TenantCostService.class);
        RagProperties ragProperties = mock(RagProperties.class);

        // 检索序：甲在前乙在后——判分若不被消费，上下文 [1] 永远是甲
        ScoredDocument docA = ScoredDocument.builder()
                .docId("doc-a").sourceType("vector").title("旧版手册.pdf")
                .chunkId("chunk-a").content("甲文档内容：历史版本说明")
                .retrievalScore(0.9).metadata(Map.of()).build();
        ScoredDocument docB = ScoredDocument.builder()
                .docId("doc-b").sourceType("vector").title("最新指引.pdf")
                .chunkId("chunk-b").content("乙文档内容：最新操作指引")
                .retrievalScore(0.5).metadata(Map.of()).build();
        when(retrievalService.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new HybridRetrievalService.HybridRetrievalResult(
                        List.of(docA, docB), 2, 2, List.of()));
        when(evidenceJudgeService.judge(any(), anyString())).thenReturn(judgedEvidence);
        when(citationService.buildCitations(any())).thenReturn(List.of());
        when(citationService.formatCitationFooter(any())).thenReturn("");
        when(modelRouter.resolve(nullable(String.class), anyString(), anyString(), anyString()))
                .thenReturn(DECISION);
        when(tenantCostService.estimateTokens(anyString())).thenReturn(10L);
        when(ragProperties.getRetrieveTopK()).thenReturn(5);
        when(ragProperties.getRerankTopK()).thenReturn(6);
        when(ragProperties.getTemperature()).thenReturn(0.7);

        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("答案");

        service = new HybridRagAnswerService(retrievalService, evidenceJudgeService,
                citationService, chatClient, modelRouter, ragProperties,
                new SimpleMeterRegistry(), tenantCostService,
                mock(RagFactMemoryRecorder.class), mock(MemoryService.class));
    }

    private EvidenceItem evidence(String chunkId, String title, double score) {
        return EvidenceItem.builder()
                .sourceType("vector").title(title).chunkId(chunkId)
                .score(score).snippet("摘要").build();
    }

    @Test
    void judgedOrderAndFloorShapeTheGenerationContext() {
        // 判分推翻检索序：乙（0.90）升到第一，甲（0.25）低于垃圾线被剔除
        setUp(List.of(evidence("chunk-b", "最新指引.pdf", 0.90),
                evidence("chunk-a", "旧版手册.pdf", 0.25)));

        HybridRagAnswerService.HybridRagResult result =
                service.answer("怎么操作", "tenant-1", "chat-1", "conv-1", null);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPrompt.capture());
        String prompt = userPrompt.getValue();
        // 上下文只含判分胜者乙：乙跟在编号 [1] 之后，且不存在第二条 [2]
        assertThat(prompt).contains("乙文档内容：最新操作指引");
        assertThat(prompt.indexOf("乙文档内容")).isGreaterThan(prompt.indexOf("[1]"));
        assertThat(prompt).doesNotContain("[2]");
        assertThat(prompt).doesNotContain("甲文档内容");
        assertThat(result.getRetrievalStats().getFinalCount()).isEqualTo(1);
    }

    @Test
    void allEvidenceBelowFloorFallsBackToJudgedHeadNotFakeEmpty() {
        // 全部低于垃圾线：退回检索序头部，而不是把评审失效伪装成"知识库为空"
        setUp(List.of(evidence("chunk-a", "旧版手册.pdf", 0.10),
                evidence("chunk-b", "最新指引.pdf", 0.20)));

        HybridRagAnswerService.HybridRagResult result =
                service.answer("怎么操作", "tenant-1", "chat-1", "conv-1", null);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPrompt.capture());
        assertThat(userPrompt.getValue()).contains("甲文档内容").contains("乙文档内容");
        assertThat(result.getRetrievalStats().getFinalCount()).isEqualTo(2);
    }

    @Test
    void emptyJudgementDegradesToRetrievalOrder() {
        // 判分返回空（如评审器异常被上游吞掉）：退回检索序，管线不中断
        setUp(List.of());

        HybridRagAnswerService.HybridRagResult result =
                service.answer("怎么操作", "tenant-1", "chat-1", "conv-1", null);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPrompt.capture());
        assertThat(userPrompt.getValue()).contains("甲文档内容").contains("乙文档内容");
        assertThat(result.getRetrievalStats().getFinalCount()).isEqualTo(2);
    }
}
