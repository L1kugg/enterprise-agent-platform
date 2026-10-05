package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import com.enterprise.iqk.domain.vo.DocumentContentVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentContentServiceTest {

    @TempDir
    Path tempDir;

    private final IngestionService ingestionService = mock(IngestionService.class);
    private final DocumentContentService service = new DocumentContentService(ingestionService);

    private IngestionJob job(IngestionJobStatus status, String filePath) {
        return IngestionJob.builder()
                .jobId("job-1")
                .tenantId("public")
                .chatId("doc-1")
                .sourceName("a.md")
                .sourceType("MD")
                .status(status)
                .filePath(filePath)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /** 磁盘真实存在的占位文件：文件存在性过滤在 parseAndSplit 打桩之前，路径必须可用。 */
    private String existingFile() throws Exception {
        return Files.writeString(tempDir.resolve("a.md"), "dummy").toString();
    }

    @Test
    void buildsOrderedBlocksFromChunks() throws Exception {
        when(ingestionService.listByChatId("public", "doc-1", 100))
                .thenReturn(List.of(job(IngestionJobStatus.SUCCEEDED, existingFile())));
        // parseAndSplit 是包内方法，直接打桩；中间空白切片应被过滤
        when(ingestionService.parseAndSplit(any(IngestionJob.class))).thenReturn(List.of(
                new Document("第一页文本", Map.of("page_number", 1)),
                new Document("  "),
                new Document("第二页文本", Map.of("page_number", 2))));

        DocumentContentVO content = service.loadContent("public", "doc-1");

        assertThat(content.getChatId()).isEqualTo("doc-1");
        assertThat(content.getSourceName()).isEqualTo("a.md");
        assertThat(content.getChunkCount()).isEqualTo(2);
        assertThat(content.isTruncated()).isFalse();
        assertThat(content.getBlocks()).hasSize(2);
        assertThat(content.getBlocks().get(0).getIndex()).isZero();
        assertThat(content.getBlocks().get(0).getPage()).isEqualTo(1);
        assertThat(content.getBlocks().get(0).getText()).isEqualTo("第一页文本");
        assertThat(content.getBlocks().get(1).getIndex()).isEqualTo(1);
        assertThat(content.getBlocks().get(1).getPage()).isEqualTo(2);
    }

    @Test
    void throws404WhenNoSucceededJob() {
        when(ingestionService.listByChatId("public", "doc-1", 100))
                .thenReturn(List.of(job(IngestionJobStatus.FAILED, "unused")));

        assertThatThrownBy(() -> service.loadContent("public", "doc-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> {
                            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                            assertThat(ex.getReason()).contains("文档不存在");
                        });
        verify(ingestionService, never()).parseAndSplit(any());
    }

    @Test
    void throws404WhenFileMissing() {
        when(ingestionService.listByChatId("public", "doc-1", 100))
                .thenReturn(List.of(job(IngestionJobStatus.SUCCEEDED, "/nonexistent/a.md")));

        assertThatThrownBy(() -> service.loadContent("public", "doc-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(ingestionService, never()).parseAndSplit(any());
    }

    @Test
    void translatesParseFailureTo500() throws Exception {
        Path existing = tempDir.resolve("a.md");
        Files.writeString(existing, "dummy");
        when(ingestionService.listByChatId("public", "doc-1", 100))
                .thenReturn(List.of(job(IngestionJobStatus.SUCCEEDED, existing.toString())));
        when(ingestionService.parseAndSplit(any(IngestionJob.class)))
                .thenThrow(new IllegalStateException("source file missing: " + existing));

        assertThatThrownBy(() -> service.loadContent("public", "doc-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> {
                            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                            // 中文提示且不泄露磁盘路径
                            assertThat(ex.getReason()).isEqualTo("文档解析失败，请稍后重试");
                        });
    }

    @Test
    void truncatesOversizedContent() throws Exception {
        when(ingestionService.listByChatId("public", "doc-1", 100))
                .thenReturn(List.of(job(IngestionJobStatus.SUCCEEDED, existingFile())));
        String oversized = "a".repeat(DocumentContentService.MAX_TOTAL_CHARS + 100);
        when(ingestionService.parseAndSplit(any(IngestionJob.class))).thenReturn(List.of(
                new Document(oversized, Map.of("page_number", 1)),
                new Document("超出上限后被丢弃的文本")));

        DocumentContentVO content = service.loadContent("public", "doc-1");

        assertThat(content.isTruncated()).isTrue();
        assertThat(content.getChunkCount()).isEqualTo(1);
        assertThat(content.getBlocks().get(0).getText()).hasSize(DocumentContentService.MAX_TOTAL_CHARS);
    }
}
