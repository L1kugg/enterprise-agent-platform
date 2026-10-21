package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.domain.DocumentSegmentRecord;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.mapper.DocumentSegmentMapper;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.retrieval.KeywordIndexStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分段治理操作语义（R8，FR-R8-04）：编辑→删旧向量重嵌、删除三面清理、
 * 合并仅限相邻、拆分产出校验、整篇重建上限与全量重嵌。
 */
@ExtendWith(MockitoExtension.class)
class DocumentSegmentServiceTest {

    @Mock
    private DocumentSegmentMapper segmentMapper;

    @Mock
    private IngestionJobMapper ingestionJobMapper;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private KeywordIndexStore keywordIndexStore;

    private DocumentSegmentService service;

    @BeforeEach
    void setUp() {
        var catalog = mock(com.enterprise.iqk.service.kb.KnowledgeBaseCatalogService.class);
        var ragProperties = new com.enterprise.iqk.config.properties.RagProperties();
        var queue = mock(com.enterprise.iqk.ingestion.queue.IngestionQueue.class);
        var ingestionProperties = new com.enterprise.iqk.config.properties.IngestionProperties();
        service = new DocumentSegmentService(segmentMapper, ingestionJobMapper,
                vectorStore, keywordIndexStore, catalog, ragProperties, queue, ingestionProperties);
    }

    @Test
    void updateContentReembedsSingleSegment() {
        when(segmentMapper.selectById(5L)).thenReturn(segment(5L, "job-1", 0, "旧内容"));
        when(ingestionJobMapper.findLatestByChatId("t-1", "chat-1", 1)).thenReturn(List.of(job()));

        service.updateContent("t-1", 5L, "新内容");

        // 删除旧向量（tenant+job+chunk 精确过滤）→ add 重嵌新文本（接口 default add 逐条 accept）
        verify(vectorStore).delete(any(Filter.Expression.class));
        org.mockito.Mockito.verify(vectorStore).add(org.mockito.ArgumentMatchers.argThat(
                (List<org.springframework.ai.document.Document> docs) ->
                        docs.size() == 1 && "新内容".equals(docs.get(0).getText())));
        verify(segmentMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                (DocumentSegmentRecord row) -> "新内容".equals(row.getContent())
                        && Boolean.FALSE.equals(row.getVectorSynced())));
    }

    @Test
    void deleteCleansVectorKeywordAndRow() {
        when(segmentMapper.selectById(6L)).thenReturn(segment(6L, "job-1", 1, "内容"));

        service.delete("t-1", 6L);

        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(keywordIndexStore).deleteByJobChunkIndex("t-1", "job-1", 1);
        verify(segmentMapper).deleteById(6L);
    }

    @Test
    void mergeRequiresAdjacentSegments() {
        when(segmentMapper.selectById(1L)).thenReturn(segment(1L, "job-1", 0, "A"));
        when(segmentMapper.selectById(3L)).thenReturn(segment(3L, "job-1", 2, "C"));

        assertThatThrownBy(() -> service.merge("t-1", 1L, 3L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).contains("相邻"));
        verify(segmentMapper, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void splitBySeparatorProducesBoundedParts() {
        when(segmentMapper.selectById(7L)).thenReturn(segment(7L, "job-1", 2, "甲。乙。丙。丁"));
        when(ingestionJobMapper.findLatestByChatId("t-1", "chat-1", 1)).thenReturn(List.of(job()));
        when(segmentMapper.maxSegIndex("t-1", "chat-1")).thenReturn(2);

        List<DocumentSegmentRecord> created = service.split("t-1", 7L, "。", null);

        // 4 段：原段保留首段"甲"（updateById 落库），其余 3 段追加段序 3/4/5 并逐段重嵌
        assertThat(created).hasSize(4);
        assertThat(created.get(1).getSegIndex()).isEqualTo(3);
        assertThat(created.get(1).getContent()).isEqualTo("乙");
        assertThat(created.get(3).getSegIndex()).isEqualTo(5);
        org.mockito.Mockito.verify(vectorStore, times(4)).add(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void splitRejectsUnboundedOutput() {
        when(segmentMapper.selectById(7L)).thenReturn(segment(7L, "job-1", 0, "无分隔符内容"));
        when(ingestionJobMapper.findLatestByChatId("t-1", "chat-1", 1)).thenReturn(List.of(job()));

        assertThatThrownBy(() -> service.split("t-1", 7L, "。", null))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).contains("非空分段"));
    }

    @Test
    void rebuildDocumentDeletesAllVectorsAndReembeds() {
        when(ingestionJobMapper.findLatestByChatId("t-1", "chat-1", 1)).thenReturn(List.of(job()));
        when(segmentMapper.findByChat("t-1", "chat-1")).thenReturn(List.of(
                segment(1L, "job-1", 0, "第一段"),
                segment(2L, "job-1", 1, "第二段")));

        Map<String, Object> result = service.rebuildDocument("t-1", "chat-1");

        assertThat(result).containsEntry("segments", 2).containsEntry("status", "rebuilt");
        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(keywordIndexStore).deleteByChat("t-1", "chat-1");
        verify(keywordIndexStore).indexAll(eq("t-1"), any());
        verify(segmentMapper, times(2)).updateById(any(DocumentSegmentRecord.class));
    }

    @Test
    void rebuildRejectsDocumentsWithoutSegments() {
        when(ingestionJobMapper.findLatestByChatId("t-1", "chat-1", 1)).thenReturn(List.of(job()));
        when(segmentMapper.findByChat("t-1", "chat-1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.rebuildDocument("t-1", "chat-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).contains("没有分段数据"));
    }

    private DocumentSegmentRecord segment(Long id, String jobId, int segIndex, String content) {
        return DocumentSegmentRecord.builder()
                .id(id).tenantId("t-1").chatId("chat-1").jobId(jobId)
                .segIndex(segIndex).content(content).vectorSynced(true).build();
    }

    private IngestionJob job() {
        return IngestionJob.builder()
                .jobId("job-1").tenantId("t-1").chatId("chat-1")
                .sourceName("手册.pdf").sourceType("PDF").build();
    }
}
