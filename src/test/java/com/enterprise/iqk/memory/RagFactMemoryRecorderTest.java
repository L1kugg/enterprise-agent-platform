package com.enterprise.iqk.memory;

import com.enterprise.iqk.retrieval.EvidenceItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagFactMemoryRecorderTest {

    private static EvidenceItem evidence(String title, double score, String snippet) {
        return EvidenceItem.builder()
                .sourceType("vector")
                .title(title)
                .score(score)
                .snippet(snippet)
                .knowledgeBaseId(12L)
                .build();
    }

    private MemoryEmbeddingHelper helperReturning(List<String> embeddings) {
        MemoryEmbeddingHelper helper = mock(MemoryEmbeddingHelper.class);
        when(helper.embedAllToJson(anyList())).thenReturn(embeddings);
        return helper;
    }

    @Test
    void savesOnlyEvidenceAtOrAboveThresholdWithInheritedConfidence() {
        MemoryService memoryService = mock(MemoryService.class);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService,
                helperReturning(List.of("[0.1]", "[0.2]")));

        recorder.recordFacts("tenant-1", "chat-9", List.of(
                evidence("high.pdf", 0.92, "Redis 缓存穿透的解决方案是布隆过滤器"),
                evidence("low.pdf", 0.42, "低置信证据不应入库"),
                evidence("edge.pdf", 0.70, "恰好达到阈值边界的事实")));

        ArgumentCaptor<Double> confidence = ArgumentCaptor.forClass(Double.class);
        verify(memoryService, times(2)).upsertFactMemory(anyString(), anyString(), anyString(),
                anyString(), confidence.capture(), anyString(), anyString());
        assertThat(confidence.getAllValues()).containsExactly(0.92, 0.70);
    }

    @Test
    void capsFactsPerRequestAndSkipsBlankSnippets() {
        MemoryService memoryService = mock(MemoryService.class);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService,
                helperReturning(List.of("[1]", "[2]", "[3]")));

        recorder.recordFacts("tenant-1", "chat-9", List.of(
                evidence("a.pdf", 0.9, "fact 1"),
                evidence("b.pdf", 0.9, "   "),
                evidence("c.pdf", 0.9, "fact 2"),
                evidence("d.pdf", 0.9, "fact 3"),
                evidence("e.pdf", 0.9, "fact 4")));

        verify(memoryService, times(3)).upsertFactMemory(anyString(), anyString(), anyString(),
                anyString(), anyDouble(), anyString(), anyString());
    }

    @Test
    void formatsContentSourceAndEvidenceChatLinkage() {
        MemoryService memoryService = mock(MemoryService.class);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService,
                helperReturning(List.of("[0.5,0.5]")));

        recorder.recordFacts("tenant-1", "chat-7", List.of(evidence(null, 0.9, "图灵测试的定义")));

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> embedding = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> kbIds = ArgumentCaptor.forClass(String.class);
        verify(memoryService).upsertFactMemory(anyString(), anyString(), content.capture(),
                source.capture(), anyDouble(), embedding.capture(), kbIds.capture());
        assertThat(content.getValue()).isEqualTo("事实: 图灵测试的定义");
        assertThat(source.getValue()).isEqualTo("rag:vector:untitled");
        assertThat(embedding.getValue()).isEqualTo("[0.5,0.5]");
        // 证据带 KB 归属时 fact 也带同一作用域（JSON 数字数组）
        assertThat(kbIds.getValue()).isEqualTo("[12]");
    }

    @Test
    void embeddingFailureFallsBackToNullVectorAndStillSaves() {
        MemoryService memoryService = mock(MemoryService.class);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService,
                helperReturning(java.util.Arrays.asList((String) null)));

        recorder.recordFacts("tenant-1", "chat-7", List.of(evidence("a.pdf", 0.9, "fact 1")));

        verify(memoryService).upsertFactMemory(anyString(), anyString(), anyString(),
                anyString(), anyDouble(), isNull(), anyString());
    }

    @Test
    void oneFailingFactDoesNotBlockTheRest() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.upsertFactMemory(anyString(), anyString(), anyString(), anyString(),
                anyDouble(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(null);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService,
                helperReturning(List.of("[1]", "[2]")));

        recorder.recordFacts("tenant-1", "chat-9", List.of(
                evidence("a.pdf", 0.9, "fact 1"),
                evidence("b.pdf", 0.9, "fact 2")));

        verify(memoryService, times(2)).upsertFactMemory(anyString(), anyString(), anyString(),
                anyString(), anyDouble(), anyString(), anyString());
    }

    @Test
    void skipsWhenTenantOrEvidenceMissing() {
        MemoryService memoryService = mock(MemoryService.class);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService,
                helperReturning(List.of()));

        recorder.recordFacts(null, "chat-9", List.of(evidence("a.pdf", 0.9, "fact")));
        recorder.recordFacts("tenant-1", "chat-9", List.of());

        verify(memoryService, never()).upsertFactMemory(anyString(), anyString(), anyString(),
                anyString(), anyDouble(), anyString(), anyString());
    }
}
