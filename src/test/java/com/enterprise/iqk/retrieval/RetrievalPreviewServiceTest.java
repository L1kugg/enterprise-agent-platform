package com.enterprise.iqk.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalPreviewServiceTest {

    @Test
    void shouldMergeSortAndDedupAcrossLanes() {
        VectorRetriever vector = mock(VectorRetriever.class);
        KeywordRetriever keyword = mock(KeywordRetriever.class);
        GraphRetriever graph = mock(GraphRetriever.class);
        // keyword 路与 vector 路命中同一内容（同指纹），去重应保留分高的 vector 版本
        when(vector.retrieve("q", "t1", "")).thenReturn(List.of(
                ScoredDocument.builder().sourceType("vector").title("a.md").chunkId("chunk-0")
                        .content("重复内容片段").retrievalScore(0.8).build(),
                ScoredDocument.builder().sourceType("vector").title("a.md").chunkId("chunk-1")
                        .content("独有内容").retrievalScore(0.6).build()));
        when(keyword.retrieve("q", "t1", "", 5)).thenReturn(List.of(
                ScoredDocument.builder().sourceType("keyword").title("a.md").chunkId("chunk-0")
                        .content("重复内容片段").retrievalScore(0.7).build()));
        when(graph.retrieve("q", "t1", 5)).thenReturn(List.of(
                ScoredDocument.builder().sourceType("graph").title("实体X").chunkId("e-1")
                        .content("图谱内容").retrievalScore(0.5).build()));

        RetrievalPreviewService service = new RetrievalPreviewService(vector, keyword, graph);
        RetrievalPreviewResult result = service.search("t1", "q", 5);

        assertEquals(List.of(), result.degradedSources());
        assertEquals(3, result.items().size());
        assertEquals("vector", result.items().get(0).source());
        assertEquals(0.8, result.items().get(0).score());
        assertTrue(result.items().stream().noneMatch(item -> "keyword".equals(item.source())));
    }

    @Test
    void shouldDegradeSingleLaneAndKeepOthers() {
        VectorRetriever vector = mock(VectorRetriever.class);
        KeywordRetriever keyword = mock(KeywordRetriever.class);
        GraphRetriever graph = mock(GraphRetriever.class);
        when(vector.retrieve(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("embed down"));
        when(keyword.retrieve(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of(
                ScoredDocument.builder().sourceType("keyword").title("b.md").chunkId("c0")
                        .content("关键词命中").retrievalScore(0.6).build()));
        when(graph.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());

        RetrievalPreviewService service = new RetrievalPreviewService(vector, keyword, graph);
        RetrievalPreviewResult result = service.search("t1", "q", 5);

        assertEquals(List.of("vector"), result.degradedSources());
        assertEquals(1, result.items().size());
        assertEquals("b.md", result.items().get(0).fileName());
    }

    @Test
    void shouldCapSnippetAndTopK() {
        VectorRetriever vector = mock(VectorRetriever.class);
        KeywordRetriever keyword = mock(KeywordRetriever.class);
        GraphRetriever graph = mock(GraphRetriever.class);
        String longContent = "内".repeat(1000);
        when(vector.retrieve(anyString(), anyString(), anyString())).thenReturn(List.of(
                ScoredDocument.builder().sourceType("vector").title("c.md").chunkId("c0")
                        .content(longContent).retrievalScore(0.9).build(),
                ScoredDocument.builder().sourceType("vector").title("c.md").chunkId("c1")
                        .content("第二条").retrievalScore(0.5).build()));

        RetrievalPreviewService service = new RetrievalPreviewService(vector, keyword, graph);
        RetrievalPreviewResult result = service.search("t1", "q", 1);

        assertEquals(1, result.items().size());
        // 280 字截断 + 省略号
        assertEquals(281, result.items().get(0).snippet().length());
        assertTrue(result.items().get(0).snippet().endsWith("…"));
    }
}
