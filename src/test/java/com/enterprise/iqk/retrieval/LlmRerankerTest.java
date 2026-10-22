package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.llm.ModelCallGuard;
import com.enterprise.iqk.llm.ModelRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import org.mockito.Mockito;

/**
 * LLM 重排器（rag.reranker=llm 默认策略）：按 LLM 相关性分重排并更新 finalScore;
 * LLM 失败/输出不可解析回退原序——重排是质量增强而非正确性依赖。
 */
class LlmRerankerTest {

    private static final ModelRouter.ModelRouteDecision DECISION = new ModelRouter.ModelRouteDecision(
            "economy", "test-model", "economy", false, null, null, null, null);

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ModelRouter modelRouter;
    private ModelCallGuard modelCallGuard;
    private LlmReranker reranker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatClient = mock(ChatClient.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, withSettings().defaultAnswer(Mockito.RETURNS_SELF));
        modelRouter = mock(ModelRouter.class);
        modelCallGuard = mock(ModelCallGuard.class);
        when(modelRouter.resolve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(DECISION);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(modelCallGuard.call(anyString(), any())).thenAnswer(inv -> {
            var supplier = (java.util.function.Supplier<?>) inv.getArgument(1);
            return supplier == null ? "" : supplier.get();
        });
        reranker = new LlmReranker(chatClient, modelRouter, modelCallGuard, new ObjectMapper());
    }

    private void llmReturns(String scoresJson) {
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(scoresJson);
    }

    private ScoredDocument doc(String id, String content) {
        return ScoredDocument.builder().docId(id).sourceType("vector")
                .title(id + ".pdf").chunkId("c-" + id)
                .content(content).retrievalScore(0.5).finalScore(0.5).build();
    }

    @Test
    void reranksByLlmScoreAndUpdatesFinalScore() {
        List<ScoredDocument> docs = List.of(
                doc("low", "内容甲"),
                doc("high", "内容乙"));
        llmReturns("[{\"i\":0,\"s\":2},{\"i\":1,\"s\":9}]");

        List<ScoredDocument> result = reranker.rerank("t-1", "chat-1", "查询乙", docs, 2);

        assertThat(result.get(0).getDocId()).isEqualTo("high");
        assertThat(result.get(0).getFinalScore()).isEqualTo(0.9);
        assertThat(result.get(1).getDocId()).isEqualTo("low");
    }

    @Test
    void jsonFenceAroundScoresIsTolerated() {
        List<ScoredDocument> docs = List.of(doc("a", "内容甲"), doc("b", "内容乙"));
        llmReturns("```json\n[{\"i\":1,\"s\":8},{\"i\":0,\"s\":3}]\n```");

        List<ScoredDocument> result = reranker.rerank("t-1", "chat-1", "q", docs, 2);

        assertThat(result.get(0).getDocId()).isEqualTo("b");
    }

    @Test
    void unparseableOutputFallsBackToOriginalOrder() {
        List<ScoredDocument> docs = List.of(doc("a", "甲"), doc("b", "乙"));
        llmReturns("我觉得都不错");

        List<ScoredDocument> result = reranker.rerank("t-1", "chat-1", "q", docs, 2);

        assertThat(result).containsExactlyElementsOf(docs);
    }

    @Test
    void guardFailureFallsBackToOriginalOrder() {
        List<ScoredDocument> docs = List.of(doc("a", "甲"), doc("b", "乙"));
        when(modelCallGuard.call(anyString(), any())).thenThrow(new RuntimeException("cb open"));

        List<ScoredDocument> result = reranker.rerank("t-1", "chat-1", "q", docs, 2);

        assertThat(result).containsExactlyElementsOf(docs);
    }

    @Test
    void tailDocumentsBeyondCandidatePoolAreKept() {
        List<ScoredDocument> docs = List.of(
                doc("a", "甲"), doc("b", "乙"), doc("c", "丙"), doc("d", "丁"),
                doc("e", "戊"), doc("f", "己"), doc("g", "庚"), doc("h", "辛"),
                doc("i", "壬"), doc("j", "癸"), doc("k", "子"), doc("l", "丑"),
                doc("m", "寅"));
        llmReturns("[{\"i\":0,\"s\":5}]");

        List<ScoredDocument> result = reranker.rerank("t-1", "chat-1", "q", docs, docs.size());

        // 前 12 进重排池(仅 a 得分 5,其余 -1 按 stable 排序保持原相对顺序),第 13 个原序保留
        assertThat(result).hasSize(docs.size());
        assertThat(result).extracting(ScoredDocument::getDocId).contains("m");
    }

    @Test
    void emptyQueryReturnsOriginalOrder() {
        List<ScoredDocument> docs = List.of(doc("a", "甲"));

        List<ScoredDocument> result = reranker.rerank("t-1", "chat-1", "  ", docs, 5);

        verifyNoInteractionsWithLlm(docs, result);
    }

    private void verifyNoInteractionsWithLlm(List<ScoredDocument> input, List<ScoredDocument> result) {
        org.mockito.Mockito.verify(modelRouter, Mockito.never())
                .resolve(anyString(), anyString(), anyString(), anyString());
        assertThat(result).containsExactlyElementsOf(input);
    }
}
