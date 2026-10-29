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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fact 写侧近重复合并（upsert）行为：
 * 词面/语义判重命中走合并（不新增行、置信度取 max、缺失向量补齐、UPDATE 事件），
 * 未命中或判重失败/开关关闭回退直接写入。
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
    void containmentDuplicateMergesInsteadOfInserting() {
        when(itemMapper.findActiveFactsByTenant("tenant-1", 50)).thenReturn(List.of(
                fact(7L, "mem-old", "事实: 住宿报销每晚上限 400 元", 0.8, "[0.1,0.2]")));

        MemoryItemRecord result = service.upsertFactMemory("tenant-1", "chat-9",
                "事实: 住宿报销每晚上限 400 元", "rag:vector:policy.pdf", 0.95, "[0.3,0.4]");

        verify(itemMapper, never()).insert(any(MemoryItemRecord.class));
        // 置信度取 max：新证据 0.95 > 既有 0.8
        verify(itemMapper).mergeFactConfidence(eq(7L), eq(0.95), eq("[0.3,0.4]"));
        ArgumentCaptor<MemoryEventRecord> events = ArgumentCaptor.forClass(MemoryEventRecord.class);
        verify(eventMapper).insert(events.capture());
        assertThat(events.getValue().getAction()).isEqualTo("UPDATE");
        assertThat(events.getValue().getMemoryId()).isEqualTo("mem-old");
        assertThat(result.getMemoryId()).isEqualTo("mem-old");
        assertThat(result.getConfidence()).isEqualTo(0.95);
    }

    @Test
    void weakerNewEvidenceKeepsExistingConfidence() {
        when(itemMapper.findActiveFactsByTenant("tenant-1", 50)).thenReturn(List.of(
                fact(7L, "mem-old", "事实: 住宿报销每晚上限 400 元", 0.95, null)));

        MemoryItemRecord result = service.upsertFactMemory("tenant-1", "chat-9",
                "事实: 住宿报销每晚上限 400 元", "rag:vector:policy.pdf", 0.72, "[0.5,0.5]");

        verify(itemMapper).mergeFactConfidence(eq(7L), eq(0.95), eq("[0.5,0.5]"));
        assertThat(result.getConfidence()).isEqualTo(0.95);
    }

    @Test
    void mergeFillsMissingVectorButKeepsProvenance() {
        MemoryItemRecord existing = fact(7L, "mem-old", "事实: 发票需在 30 天内提交", 0.8, null);
        existing.setSourceChatId("chat-1");
        when(itemMapper.findActiveFactsByTenant("tenant-1", 50)).thenReturn(List.of(existing));

        service.upsertFactMemory("tenant-1", "chat-9",
                "事实: 发票需在 30 天内提交", "rag:vector:policy.pdf", 0.9, "[0.6,0.6]");

        verify(itemMapper).mergeFactConfidence(eq(7L), eq(0.9), eq("[0.6,0.6]"));
        // 合并不新增行；溯源锚点 source_chat_id 保留首次写入值（仅经 mergeFactConfidence 更新置信/向量）
        verify(itemMapper, never()).insert(any(MemoryItemRecord.class));
    }

    @Test
    void semanticDuplicateMergesWhenCachedVectorsAligned() {
        properties.setSemanticEnabled(true);
        // 文本判重不命中（措辞不同），但缓存向量余弦 1.0 >= 0.88 命中语义判重
        when(itemMapper.findActiveFactsByTenant("tenant-1", 50)).thenReturn(List.of(
                fact(3L, "mem-sem", "事实: 出差住宿每晚报销上限为四百元", 0.85, "[1.0,0.0]")));

        service.upsertFactMemory("tenant-1", "chat-9",
                "事实: 住宿费报销标准是每晚 400 元", "rag:vector:policy.pdf", 0.9, "[1.0,0.0]");

        verify(itemMapper).mergeFactConfidence(eq(3L), eq(0.9), eq("[1.0,0.0]"));
        verify(itemMapper, never()).insert(any(MemoryItemRecord.class));
    }

    @Test
    void noDuplicateFallsBackToInsert() {
        when(itemMapper.findActiveFactsByTenant("tenant-1", 50)).thenReturn(List.of(
                fact(7L, "mem-old", "事实: 完全不相关的另一条事实", 0.8, "[0.1]")));

        MemoryItemRecord result = service.upsertFactMemory("tenant-1", "chat-9",
                "事实: 住宿报销每晚上限 400 元", "rag:vector:policy.pdf", 0.9, "[0.9,0.1]");

        verify(itemMapper, never()).mergeFactConfidence(anyLong(), anyDouble(), anyString());
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
