package com.enterprise.iqk.memory;

import com.enterprise.iqk.config.properties.MemoryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryEmbeddingHelperTest {

    private OpenAiEmbeddingModel embeddingModel;
    private MemoryProperties properties;
    private SimpleMeterRegistry registry;
    private MemoryEmbeddingHelper helper;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(OpenAiEmbeddingModel.class);
        properties = new MemoryProperties();
        registry = new SimpleMeterRegistry();
        helper = new MemoryEmbeddingHelper(embeddingModel, properties, registry);
    }

    @Test
    void embedsToJsonAndRoundTrips() {
        when(embeddingModel.embed("用户偏好中文")).thenReturn(new float[]{0.5f, -0.25f});

        String json = helper.embedToJson("用户偏好中文");

        assertThat(json).isEqualTo("[0.5,-0.25]");
        float[] vector = MemoryEmbeddingHelper.fromJson(json);
        assertThat(vector).hasSize(2);
        assertThat(vector[0]).isEqualTo(0.5f);
        assertThat(vector[1]).isEqualTo(-0.25f);
    }

    @Test
    void embedFailureReturnsNullAndCountsFailure() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("api down"));

        assertThat(helper.embed("x")).isNull();
        assertThat(registry.counter("memory.embed.failures").count()).isEqualTo(1.0);
    }

    @Test
    void disabledSwitchShortCircuitsWithoutCallingModel() {
        properties.setSemanticEnabled(false);

        assertThat(helper.embed("x")).isNull();
        assertThat(helper.embedToJson("x")).isNull();
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    void batchFailureKeepsNullPlaceholdersAligned() {
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("api down"));

        List<String> embeddings = helper.embedAllToJson(List.of("a", "b", "c"));

        assertThat(embeddings).hasSize(3).containsOnlyNulls();
        assertThat(registry.counter("memory.embed.failures").count()).isEqualTo(1.0);
    }

    @Test
    void batchEmbeddingKeepsPositionalAlignment() {
        when(embeddingModel.embed(anyList()))
                .thenReturn(List.of(new float[]{1f}, new float[]{2f}));

        List<String> embeddings = helper.embedAllToJson(List.of("a", "b"));

        assertThat(embeddings).containsExactly("[1.0]", "[2.0]");
    }

    @Test
    void cosineHandlesDegenerateVectorsAsZeroSignal() {
        assertThat(MemoryEmbeddingHelper.cosine(null, new float[]{1f})).isEqualTo(0.0);
        assertThat(MemoryEmbeddingHelper.cosine(new float[]{0f, 0f}, new float[]{1f, 1f})).isEqualTo(0.0);
        assertThat(MemoryEmbeddingHelper.cosine(new float[]{1f}, new float[]{1f, 2f})).isEqualTo(0.0);
        assertThat(MemoryEmbeddingHelper.cosine(new float[]{1f, 0f}, new float[]{1f, 0f})).isEqualTo(1.0);
        assertThat(MemoryEmbeddingHelper.fromJson("not-json")).isNull();
        assertThat(MemoryEmbeddingHelper.fromJson(null)).isNull();
    }
}
