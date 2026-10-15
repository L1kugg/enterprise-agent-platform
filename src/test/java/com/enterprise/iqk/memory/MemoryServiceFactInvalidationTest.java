package com.enterprise.iqk.memory;

import com.enterprise.iqk.config.properties.MemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryServiceFactInvalidationTest {

    private MemoryItemMapper itemMapper;
    private MemoryEventMapper eventMapper;
    private MemoryProperties properties;
    private MemoryService service;

    @BeforeEach
    void setUp() {
        itemMapper = mock(MemoryItemMapper.class);
        eventMapper = mock(MemoryEventMapper.class);
        properties = new MemoryProperties();
        properties.setSemanticEnabled(false);
        service = new MemoryService(itemMapper, eventMapper, new ObjectMapper(),
                new MemoryRelevanceScorer(), mock(MemoryEmbeddingHelper.class), properties);
    }

    @Test
    void invalidationMarksStaleAndEmitsEventPerMemory() {
        when(itemMapper.findActiveFactMemoryIdsByChat("tenant-1", "chat-9"))
                .thenReturn(List.of("mem-1", "mem-2"));
        when(itemMapper.markFactsStaleByChat("tenant-1", "chat-9")).thenReturn(2);

        int rows = service.invalidateFactsForChat("tenant-1", "chat-9");

        assertThat(rows).isEqualTo(2);
        ArgumentCaptor<MemoryEventRecord> events = ArgumentCaptor.forClass(MemoryEventRecord.class);
        verify(eventMapper, times(2)).insert(events.capture());
        assertThat(events.getAllValues())
                .allSatisfy(event -> {
                    assertThat(event.getAction()).isEqualTo("INVALIDATE");
                    assertThat(event.getReason()).contains("chat=chat-9");
                })
                .extracting(MemoryEventRecord::getMemoryId)
                .containsExactlyInAnyOrder("mem-1", "mem-2");
    }

    @Test
    void noActiveFactsSkipsUpdateAndEvents() {
        when(itemMapper.findActiveFactMemoryIdsByChat("tenant-1", "chat-9"))
                .thenReturn(List.of());

        assertThat(service.invalidateFactsForChat("tenant-1", "chat-9")).isZero();

        verify(itemMapper, never()).markFactsStaleByChat(anyString(), anyString());
        verify(eventMapper, never()).insert(any(MemoryEventRecord.class));
    }

    @Test
    void cascadeGateDisabledSkipsEverything() {
        properties.setCascadeInvalidateEnabled(false);

        assertThat(service.invalidateFactsForChat("tenant-1", "chat-9")).isZero();

        verify(itemMapper, never()).findActiveFactMemoryIdsByChat(anyString(), anyString());
        verify(itemMapper, never()).markFactsStaleByChat(anyString(), anyString());
    }

    @Test
    void blankChatIdIsIgnored() {
        assertThat(service.invalidateFactsForChat("tenant-1", "  ")).isZero();

        verify(itemMapper, never()).findActiveFactMemoryIdsByChat(anyString(), anyString());
    }

    @Test
    void saveFactRecordsEvidenceChatLinkage() {
        when(itemMapper.markFactsStaleByChat(anyString(), anyString())).thenReturn(0);
        MemoryItemRecord saved = service.saveFactMemory(
                "tenant-1", "chat-7", "事实: x", "rag:vector:a.pdf", 0.9, null);

        ArgumentCaptor<MemoryItemRecord> captor = ArgumentCaptor.forClass(MemoryItemRecord.class);
        verify(itemMapper).insert(captor.capture());
        assertThat(captor.getValue().getSourceChatId()).isEqualTo("chat-7");
        assertThat(saved.getSourceChatId()).isEqualTo("chat-7");
        // 显式传入 null 向量且语义关闭时不补算，召回侧回退词面
        assertThat(captor.getValue().getEmbeddingJson()).isNull();
    }

    @Test
    void buildContextEmbedsQueryOnceAndConsumesCachedVectors() {
        MemoryEmbeddingHelper helper = mock(MemoryEmbeddingHelper.class);
        properties.setSemanticEnabled(true);
        service = new MemoryService(itemMapper, eventMapper, new ObjectMapper(),
                new MemoryRelevanceScorer(), helper, properties);
        MemoryItemRecord fact = MemoryItemRecord.builder()
                .memoryId("mem-f").type("fact").content("住宿报销每晚上限 400 元")
                .confidence(0.9).embeddingJson("[1.0,0.0]")
                .createdAt(java.time.LocalDateTime.now()).build();
        when(itemMapper.findByTypeAndConfidence(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of(fact));
        when(itemMapper.findByUserAndType(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(helper.embed(anyString())).thenReturn(new float[]{1f, 0f});

        service.buildContext("tenant-1", "user-1", "出差住宿标准", false);

        // 查询向量只算一次；三个候选池共用同一语义上下文
        verify(helper, times(1)).embed(anyString());
    }
}
