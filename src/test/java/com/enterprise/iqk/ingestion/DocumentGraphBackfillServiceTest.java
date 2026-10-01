package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import com.enterprise.iqk.graph.GraphExtractionService;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentGraphBackfillServiceTest {

    @TempDir
    Path tempDir;

    private final IngestionJobMapper jobMapper = mock(IngestionJobMapper.class);
    private final IngestionService ingestionService = mock(IngestionService.class);
    private final GraphExtractionService graphExtractionService = mock(GraphExtractionService.class);
    private final DocumentGraphBackfillService service = new DocumentGraphBackfillService(
            jobMapper, ingestionService, graphExtractionService);

    private IngestionJob job(String chatId, IngestionJobStatus status, String filePath) {
        return IngestionJob.builder()
                .jobId("job-1")
                .tenantId("public")
                .chatId(chatId)
                .status(status)
                .filePath(filePath)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void returnsEmptyWhenNoSucceededJob() {
        when(jobMapper.findLatestByChatId("public", "chat-1", 100)).thenReturn(List.of(
                job("chat-1", IngestionJobStatus.FAILED, "/tmp/a.pdf")));

        assertThat(service.rebuildForChat("public", "chat-1")).isEmpty();
        verify(graphExtractionService, never()).extractAndStore(anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void returnsEmptyWhenSourceFileMissing() {
        when(jobMapper.findLatestByChatId("public", "chat-1", 100))
                .thenReturn(List.of(job("chat-1", IngestionJobStatus.SUCCEEDED, "/nonexistent/a.pdf")));

        assertThat(service.rebuildForChat("public", "chat-1")).isEmpty();
        verify(graphExtractionService, never()).extractAndStore(anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void rebuildsGraphFromDiskPdfChunks() throws Exception {
        Path existing = tempDir.resolve("a.pdf");
        java.nio.file.Files.writeString(existing, "dummy pdf bytes");
        when(jobMapper.findLatestByChatId("public", "chat-1", 100))
                .thenReturn(List.of(job("chat-1", IngestionJobStatus.SUCCEEDED, existing.toString())));
        // parseAndSplit 是包内方法，直接打桩返回切片
        when(ingestionService.parseAndSplit(any(IngestionJob.class)))
                .thenReturn(List.of(new Document("高温天气会引发热射病"), new Document("  ")));
        GraphExtractionService.GraphExtractionResult extracted =
                new GraphExtractionService.GraphExtractionResult(2, 1, 3, null);
        when(graphExtractionService.extractAndStore(eq("public"), eq("chat-1"), eq("job-1"), anyList()))
                .thenReturn(extracted);

        Optional<GraphExtractionService.GraphExtractionResult> result = service.rebuildForChat("public", "chat-1");

        assertThat(result).contains(extracted);
        // 空白切片在透传前被过滤掉
        verify(graphExtractionService).extractAndStore(
                eq("public"), eq("chat-1"), eq("job-1"), eq(List.of("高温天气会引发热射病")));
    }
}
