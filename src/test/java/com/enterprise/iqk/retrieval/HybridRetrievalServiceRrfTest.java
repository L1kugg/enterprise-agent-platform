package com.enterprise.iqk.retrieval;

import com.enterprise.iqk.retrieval.web.WebSearchProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceRrfTest {

    @Test
    void multiLaneHitsBeatHigherRawScoreFromOneLane() {
        VectorRetriever vectorRetriever = mock(VectorRetriever.class);
        KeywordRetriever keywordRetriever = mock(KeywordRetriever.class);
        GraphRetriever graphRetriever = mock(GraphRetriever.class);
        WebRetriever webRetriever = mock(WebRetriever.class);
        WebSearchProperties webProperties = new WebSearchProperties();
        webProperties.setEnabled(true);
        HybridRetrievalService service = new HybridRetrievalService(
                vectorRetriever, keywordRetriever, graphRetriever, webRetriever,
                webProperties, new SimpleMeterRegistry(), 3000, 8, 64,
                60, 0.30, 0.50, 0.10, 0.10);

        when(vectorRetriever.retrieve("q", "tenant", "chat"))
                .thenReturn(List.of(doc("vector-rank2", 0.98)));
        when(keywordRetriever.retrieve(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(doc("keyword-rank1", 0.61)));
        when(graphRetriever.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(webRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());

        var result = service.retrieve("q", "tenant", "chat", 2);

        assertThat(result.documents()).extracting(ScoredDocument::getDocId)
                .containsExactly("keyword-rank1", "vector-rank2");
        assertThat(result.documents().get(0).getFinalScore())
                .isGreaterThan(result.documents().get(1).getFinalScore());
    }

    private ScoredDocument doc(String id, double score) {
        return ScoredDocument.builder()
                .docId(id)
                .sourceType(id.startsWith("vector") ? "vector" : "keyword")
                .title(id)
                .content(id + " content")
                .retrievalScore(score)
                .build();
    }
}
