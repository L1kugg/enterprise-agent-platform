package com.enterprise.iqk.memory;

import com.enterprise.iqk.config.properties.MemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fact 写侧近重复合并（upsert）行为：
 * 词面/语义判重命中走合并——新证据置信度严格更高时"接管"既有条目
 * （内容/来源/溯源锚点切到新证据，处理政策修订类冲突），否则只做
 * 置信度 max 与缺失向量补齐；未命中或判重失败/开关关闭回退直接写入。
 */
class MemoryServiceFactUpsertTest {

    private MemoryItemMapper itemMapper;
    private MemoryEventMapper eventMapper;
    private MemoryProperties properties;
    private MemoryService service;

    @BeforeEach
    void setUp() {
        itemMapper = mock(MemoryItemMapper.class);
        eventMapper = mock(MemoryEventMapper.class);
        properties = new MemoryProperties();
        service = new MemoryService(itemMapper, eventMapper, new ObjectMapper(),
                new MemoryRelevanceScorer(), mock(MemoryEmbeddingHelper.class), properties);
    }

    private MemoryItemRecord fact(long id, String memoryId, String content,
                                  double confidence, String embeddingJson) {
        return MemoryItemRecord.builder()
                .id(id).memoryId(memoryId).tenantId("tenant-1").type("fact")
                .content(content).confidence(confidence).embeddingJson(embeddingJson)
                .stale(Boolean.FALSE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void strongerEvidenceTakesOverContentAndProvenance() {
        MemoryItemRecord existing = fact(7L, "mem-old", "事实: 住宿报销每晚上限 400 元", 0.8, "[0.1,0.2]");
        existing.setSource("rag:vector:旧政策.pdf");
        existing.setSourceChatId("chat-1");
        when(itemMapper.findActiveFactsByTenant("tenant-1", 50)).thenReturn(List.of(existing));

        MemoryItemRecord result = service.upsertFactMemory("tenant-1", "chat-9",
                "事实: 住宿报销每晚上限 500 元", "rag:vector:新政策.pdf", 0.95, "[0.3,0.4]");

        verify(itemMapper, never()).insert(any(MemoryItemRecord.class));
        // 政策修订类冲突：更强证据（0.95 > 0.8）接管内容/来源/溯源锚点，置信度合并
        verify(itemMapper).mergeFactSupersede(eq(7L), eq("事实: 住宿报销每晚上限 500 元"),
                eq("rag:vector:新政策.pdf"), eq("chat-9"), eq(0.95), eq("[0.3,0.4]"), isNull());
        verify(itemMapper, never()).mergeFactConfidence(anyLong(), anyDouble(), anyString());
        ArgumentCaptor<MemoryEventRecord> events = ArgumentCaptor.forClass(MemoryEventRecord.class);
        verify(eventMapper).insert(events.capture());
        assertThat(events.getValue().getAction()).isEqualTo("UPDATE");
        assertThat(events.getValue().getReason()).contains("superseded by stronger evidence");
        assertThat(result.getConfidence()).isEqualTo(0.95);
        assertThat(result.getContent()).contains("500 元");
        assertThat(result.getSourceChatId()).isEqualTo("chat-9");
    }

    @Test
    void weakerNewEvidenceKeepsExistingContentAndProvenance() {
        MemoryItemRecord existing = fact(7L, "mem-old", "事实: 住宿报销每晚上限 400 元", 0.95, null);
        existing.setSource("rag:vector:旧政策.pdf");
        existing.setSourceChatId("chat-1");
        when(itemMapper.findActiveFactsByTenant("tenant-1", 50)).thenReturn(List.of(existing));

        MemoryItemRecord result = service.upsertFactMemory("tenant-1", "chat-9",
                "事实: 住宿报销每晚上限 400 元", "rag:vector:另一文档.pdf", 0.72, "[0.5,0.5]");

        // 弱证据不接管内容（防"旧事实被弱证据改写"），只做置信度 max 与缺失向量补齐
        verify(itemMapper).mergeFactConfidence(eq(7L), eq(0.95), eq("[0.5,0.5]"));
        verify(itemMapper, never()).mergeFactSupersede(anyLong(), anyString(), anyString(),
                anyString(), anyDouble(), anyString(), any());
        assertThat(result.getConfidence()).isEqualTo(0.95);
        assertThat(result.getSourceChatId()).isEqualTo("chat-1");
    }

    @Test
    void semanticDuplicateMergesWhenCachedVectorsAligned() {
        properties.setSemanticEnabled(true);
        // 文本判重不命中（措辞不同），但缓存向量余弦 1.0 >= 0.88 命中语义判重；新证据更强 → 接管
        when(itemMapper.findActiveFactsByTenant("tenant-1", 50)).thenReturn(List.of(
                fact(3L, "mem-sem", "事实: 出差住宿每晚报销上限为四百元", 0.85, "[1.0,0.0]")));

        service.upsertFactMemory("tenant-1", "chat-9",
                "事实: 住宿费报销标准是每晚 400 元", "rag:vector:policy.pdf", 0.9, "[1.0,0.0]");

        verify(itemMapper).mergeFactSupersede(eq(3L), anyString(), anyString(),
                eq("chat-9"), eq(0.9), eq("[1.0,0.0]"), isNull());
        verify(itemMapper, never()).insert(any(MemoryItemRecord.class));
    }

    @Test
    void noDuplicateFallsBackToInsert() {
        when(itemMapper.findActiveFactsByTenant("tenant-1", 50)).thenReturn(List.of(
                fact(7L, "mem-old", "事实: 完全不相关的另一条事实", 0.8, "[0.1]")));

        MemoryItemRecord result = service.upsertFactMemory("tenant-1", "chat-9",
                "事实: 住宿报销每晚上限 400 元", "rag:vector:policy.pdf", 0.9, "[0.9,0.1]");

        verify(itemMapper, never()).mergeFactConfidence(anyLong(), anyDouble(), anyString());
        verify(itemMapper, never()).mergeFactSupersede(anyLong(), anyString(), anyString(),
                anyString(), anyDouble(), anyString(), any());
        ArgumentCaptor<MemoryItemRecord> inserted = ArgumentCaptor.forClass(MemoryItemRecord.class);
        verify(itemMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getSourceChatId()).isEqualTo("chat-9");
        assertThat(result).isSameAs(inserted.getValue());
    }

    @Test
    void disabledFlagSkipsScanAndInsertsDirectly() {
        properties.setFactWriteDedupEnabled(false);

        service.upsertFactMemory("tenant-1", "chat-9",
                "事实: 住宿报销每晚上限 400 元", "rag:vector:policy.pdf", 0.9, null);

        verify(itemMapper, never()).findActiveFactsByTenant(anyString(), anyInt());
        verify(itemMapper).insert(any(MemoryItemRecord.class));
    }

    @Test
    void scanFailureFallsBackToInsert() {
        when(itemMapper.findActiveFactsByTenant(anyString(), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        service.upsertFactMemory("tenant-1", "chat-9",
                "事实: 住宿报销每晚上限 400 元", "rag:vector:policy.pdf", 0.9, "[0.1]");

        // 判重失败不阻塞证据沉淀：回退直接写入
        verify(itemMapper).insert(any(MemoryItemRecord.class));
    }

    @Test
    void blankContentNeverTouchesDatabase() {
        assertThat(service.upsertFactMemory("tenant-1", "chat-9",
                "   ", "rag:vector:policy.pdf", 0.9, null)).isNull();

        verify(itemMapper, never()).findActiveFactsByTenant(anyString(), anyInt());
        verify(itemMapper, never()).insert(any(MemoryItemRecord.class));
    }
}
