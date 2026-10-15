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
        verify(memoryService, times(2)).saveFactMemory(anyString(), anyString(), anyString(),
                anyString(), confidence.capture(), anyString());
        // 0.42 的低置信证据永远进不了记忆；两条达阈值的继承各自分数
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

        verify(memoryService, times(3)).saveFactMemory(anyString(), anyString(), anyString(),
                anyString(), anyDouble(), anyString());
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
        verify(memoryService).saveFactMemory(anyString(), anyString(), content.capture(),
                source.capture(), anyDouble(), embedding.capture());
        assertThat(content.getValue()).isEqualTo("事实: 图灵测试的定义");
        assertThat(source.getValue()).isEqualTo("rag:vector:untitled");
        // 批量预计算的向量透传落库；chatId 记录证据源供文档删除时级联失效
        assertThat(embedding.getValue()).isEqualTo("[0.5,0.5]");
    }

    @Test
    void embeddingFailureFallsBackToNullVectorAndStillSaves() {
        MemoryService memoryService = mock(MemoryService.class);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService,
                helperReturning(java.util.Arrays.asList((String) null)));

        recorder.recordFacts("tenant-1", "chat-7", List.of(evidence("a.pdf", 0.9, "fact 1")));

        // embedding 失败不拦写入：向量留空，召回侧回退纯词面
        verify(memoryService).saveFactMemory(anyString(), anyString(), anyString(),
                anyString(), anyDouble(), isNull());
    }

    @Test
    void oneFailingFactDoesNotBlockTheRest() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.saveFactMemory(anyString(), anyString(), anyString(), anyString(),
                anyDouble(), anyString()))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(null);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService,
                helperReturning(List.of("[1]", "[2]")));

        recorder.recordFacts("tenant-1", "chat-9", List.of(
                evidence("a.pdf", 0.9, "fact 1"),
                evidence("b.pdf", 0.9, "fact 2")));

        verify(memoryService, times(2)).saveFactMemory(anyString(), anyString(), anyString(),
                anyString(), anyDouble(), anyString());
    }

    @Test
    void skipsWhenTenantOrEvidenceMissing() {
        MemoryService memoryService = mock(MemoryService.class);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService,
                helperReturning(List.of()));

        recorder.recordFacts(null, "chat-9", List.of(evidence("a.pdf", 0.9, "fact")));
        recorder.recordFacts("tenant-1", "chat-9", List.of());

        verify(memoryService, never()).saveFactMemory(anyString(), anyString(), anyString(),
                anyString(), anyDouble(), anyString());
    }
}
