package com.enterprise.iqk.memory;

import com.enterprise.iqk.retrieval.EvidenceItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
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

    @Test
    void savesOnlyEvidenceAtOrAboveThresholdWithInheritedConfidence() {
        MemoryService memoryService = mock(MemoryService.class);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService);

        recorder.recordFacts("tenant-1", List.of(
                evidence("high.pdf", 0.92, "Redis 缓存穿透的解决方案是布隆过滤器"),
                evidence("low.pdf", 0.42, "低置信证据不应入库"),
                evidence("edge.pdf", 0.70, "恰好达到阈值边界的事实")));

        ArgumentCaptor<Double> confidence = ArgumentCaptor.forClass(Double.class);
        verify(memoryService, times(2)).saveFactMemory(anyString(), isNull(), anyString(),
                anyString(), confidence.capture());
        // 0.42 的低置信证据永远进不了记忆；两条达阈值的继承各自分数
        assertThat(confidence.getAllValues()).containsExactly(0.92, 0.70);
    }

    @Test
    void capsFactsPerRequestAndSkipsBlankSnippets() {
        MemoryService memoryService = mock(MemoryService.class);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService);

        recorder.recordFacts("tenant-1", List.of(
                evidence("a.pdf", 0.9, "fact 1"),
                evidence("b.pdf", 0.9, "   "),
                evidence("c.pdf", 0.9, "fact 2"),
                evidence("d.pdf", 0.9, "fact 3"),
                evidence("e.pdf", 0.9, "fact 4")));

        verify(memoryService, times(3)).saveFactMemory(anyString(), isNull(), anyString(),
                anyString(), anyDouble());
    }

    @Test
    void formatsContentAndSourceFromEvidence() {
        MemoryService memoryService = mock(MemoryService.class);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService);

        recorder.recordFacts("tenant-1", List.of(evidence(null, 0.9, "图灵测试的定义")));

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(memoryService).saveFactMemory(anyString(), isNull(), content.capture(),
                source.capture(), anyDouble());
        assertThat(content.getValue()).isEqualTo("事实: 图灵测试的定义");
        assertThat(source.getValue()).isEqualTo("rag:vector:untitled");
    }

    @Test
    void oneFailingFactDoesNotBlockTheRest() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.saveFactMemory(anyString(), isNull(), anyString(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(null);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService);

        recorder.recordFacts("tenant-1", List.of(
                evidence("a.pdf", 0.9, "fact 1"),
                evidence("b.pdf", 0.9, "fact 2")));

        verify(memoryService, times(2)).saveFactMemory(anyString(), isNull(), anyString(),
                anyString(), anyDouble());
    }

    @Test
    void skipsWhenTenantOrEvidenceMissing() {
        MemoryService memoryService = mock(MemoryService.class);
        RagFactMemoryRecorder recorder = new RagFactMemoryRecorder(memoryService);

        recorder.recordFacts(null, List.of(evidence("a.pdf", 0.9, "fact")));
        recorder.recordFacts("tenant-1", List.of());

        verify(memoryService, never()).saveFactMemory(anyString(), isNull(), anyString(),
                anyString(), anyDouble());
    }
}
