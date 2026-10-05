package com.enterprise.iqk.controller;

import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import com.enterprise.iqk.domain.vo.DocumentContentVO;
import com.enterprise.iqk.domain.vo.PagedResult;
import com.enterprise.iqk.graph.GraphExtractionService;
import com.enterprise.iqk.ingestion.DocumentContentService;
import com.enterprise.iqk.ingestion.DocumentGraphBackfillService;
import com.enterprise.iqk.ingestion.IngestionService;
import com.enterprise.iqk.retrieval.RetrievalPreviewItem;
import com.enterprise.iqk.retrieval.RetrievalPreviewResult;
import com.enterprise.iqk.retrieval.RetrievalPreviewService;
import com.enterprise.iqk.repository.ChatHistoryRepository;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = IngestionController.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.ApiKeyOrJwtAuthFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.RateLimitFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.AuditLogFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.HttpMetricsFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.RequestContextFilter.class)
})
@AutoConfigureMockMvc(addFilters = false)
class IngestionControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IngestionService ingestionService;
    @MockBean
    private DocumentContentService documentContentService;
    @MockBean
    private DocumentGraphBackfillService documentGraphBackfillService;
    @MockBean
    private ChatHistoryRepository chatHistoryRepository;
    @MockBean
    private org.springframework.beans.factory.ObjectProvider<Tracer> tracerProvider;
    @MockBean
    private IngestionProperties ingestionProperties;
    @MockBean
    private RetrievalPreviewService retrievalPreviewService;

    @Test
    void shouldAcceptUploadJob() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "sample.pdf", "application/pdf", "data".getBytes());
        when(ingestionProperties.getQueueBackend()).thenReturn("redis_stream");
        when(ingestionService.submitDocument(any(), any(), any(), any(), any())).thenReturn(IngestionJob.builder()
                .jobId("job-1")
                .tenantId("public")
                .chatId("chat-1")
                .sourceName("sample.pdf")
                .status(IngestionJobStatus.PENDING)
                .attemptCount(0)
                .maxRetries(3)
                .createdAt(LocalDateTime.now())
                .build());

        mockMvc.perform(multipart("/ingestion/upload/chat-1").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.job.jobId").value("job-1"))
                .andExpect(jsonPath("$.job.queueBackend").value("redis_stream"));
    }

    @Test
    void shouldReturnGraphBuildSummary() throws Exception {
        when(documentGraphBackfillService.rebuildForChat(any(), eq("chat-1")))
                .thenReturn(Optional.of(new GraphExtractionService.GraphExtractionResult(3, 2, 1, null)));

        mockMvc.perform(post("/ingestion/documents/chat-1/graph/build"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.chatId").value("chat-1"))
                .andExpect(jsonPath("$.entities").value(3))
                .andExpect(jsonPath("$.relations").value(2))
                .andExpect(jsonPath("$.facts").value(1))
                .andExpect(jsonPath("$.skipped").doesNotExist());
    }

    @Test
    void shouldReturn404WhenNoDocumentToRebuild() throws Exception {
        when(documentGraphBackfillService.rebuildForChat(any(), eq("chat-none")))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/ingestion/documents/chat-none/graph/build"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldListTenantDocuments() throws Exception {
        when(ingestionService.listDocumentsByTenant(any(), any(), eq(1), eq(20)))
                .thenReturn(new PagedResult<>(List.of(IngestionJob.builder()
                        .jobId("job-1")
                        .chatId("doc-1")
                        .sourceName("a.md")
                        .sourceType("MD")
                        .status(IngestionJobStatus.SUCCEEDED)
                        .chunkCount(3)
                        .build()), 1, 1, 20));

        mockMvc.perform(get("/ingestion/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sourceName").value("a.md"))
                .andExpect(jsonPath("$.items[0].chunkCount").value(3));
    }

    @Test
    void shouldReturnDocumentContent() throws Exception {
        when(documentContentService.loadContent(any(), eq("doc-1"))).thenReturn(DocumentContentVO.builder()
                .chatId("doc-1")
                .sourceName("a.md")
                .sourceType("MD")
                .chunkCount(2)
                .truncated(false)
                .blocks(List.of(
                        DocumentContentVO.Block.builder().index(0).page(null).text("第一块").build(),
                        DocumentContentVO.Block.builder().index(1).page(3).text("第二块").build()))
                .build());

        mockMvc.perform(get("/ingestion/documents/doc-1/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("doc-1"))
                .andExpect(jsonPath("$.sourceName").value("a.md"))
                .andExpect(jsonPath("$.chunkCount").value(2))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.blocks[0].page").doesNotExist())
                .andExpect(jsonPath("$.blocks[1].page").value(3));
    }

    @Test
    void shouldReturn404WhenDocumentContentMissing() throws Exception {
        when(documentContentService.loadContent(any(), eq("doc-none")))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "文档不存在或尚未成功入库"));

        mockMvc.perform(get("/ingestion/documents/doc-none/content"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.msg").value("文档不存在或尚未成功入库"));
    }

    @Test
    void shouldPreviewSearchKnowledgeBase() throws Exception {
        when(retrievalPreviewService.search(any(), eq("测试查询"), anyInt()))
                .thenReturn(new RetrievalPreviewResult(
                        List.of(new RetrievalPreviewItem("vector", "a.md", "chunk-0", 0.81, "命中片段")),
                        List.of()));

        mockMvc.perform(get("/ingestion/search").param("q", "测试查询"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].fileName").value("a.md"))
                .andExpect(jsonPath("$.items[0].score").value(0.81))
                .andExpect(jsonPath("$.degradedSources").isEmpty());
    }

    @Test
    void shouldRejectBlankPreviewQuery() throws Exception {
        mockMvc.perform(get("/ingestion/search").param("q", "   "))
                .andExpect(status().isBadRequest());
    }
}
