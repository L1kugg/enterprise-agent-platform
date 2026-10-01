package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import com.enterprise.iqk.ingestion.IngestionService;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AdminController.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.ApiKeyOrJwtAuthFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.RateLimitFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.AuditLogFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.HttpMetricsFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = com.enterprise.iqk.security.RequestContextFilter.class)
})
@AutoConfigureMockMvc(addFilters = false)
class AdminControllerWebMvcTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IngestionJobMapper ingestionJobMapper;
    @MockBean
    private IngestionService ingestionService;

    @TempDir
    Path tempDir;

    @Test
    void shouldListDocumentsPagedAsAdmin() throws Exception {
        Path file = tempDir.resolve("a.pdf");
        Files.write(file, new byte[4]);
        when(ingestionJobMapper.countLatestPerChatCrossTenant(null)).thenReturn(1L);
        when(ingestionJobMapper.findLatestPerChatCrossTenant(null, 0L, 20)).thenReturn(List.of(
                IngestionJob.builder()
                        .tenantId("u-alice")
                        .chatId("doc-1")
                        .sourceName("a.pdf")
                        .status(IngestionJobStatus.SUCCEEDED)
                        .attemptCount(1)
                        .filePath(file.toString())
                        .createdAt(LocalDateTime.now())
                        .build()));

        mockMvc.perform(get("/admin/documents?page=1&pageSize=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.items[0].tenantId").value("u-alice"))
                .andExpect(jsonPath("$.items[0].sourceName").value("a.pdf"))
                .andExpect(jsonPath("$.items[0].fileSize").value(4));
    }

    @Test
    void shouldReturnNullFileSizeWhenFileMissing() throws Exception {
        when(ingestionJobMapper.countLatestPerChatCrossTenant(null)).thenReturn(1L);
        when(ingestionJobMapper.findLatestPerChatCrossTenant(null, 0L, 20)).thenReturn(List.of(
                IngestionJob.builder()
                        .tenantId("u-alice")
                        .chatId("doc-2")
                        .sourceName("gone.pdf")
                        .status(IngestionJobStatus.SUCCEEDED)
                        .filePath("/nonexistent/gone.pdf")
                        .createdAt(LocalDateTime.now())
                        .build()));

        mockMvc.perform(get("/admin/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].fileSize").value(nullValue()));
    }

    @Test
    void shouldClampPaginationParamsAndBlankSearch() throws Exception {
        when(ingestionJobMapper.countLatestPerChatCrossTenant(null)).thenReturn(5L);
        when(ingestionJobMapper.findLatestPerChatCrossTenant(eq(null), eq(0L), eq(100)))
                .thenReturn(List.of());

        mockMvc.perform(get("/admin/documents?page=0&pageSize=9999&search="))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items").isEmpty());

        ArgumentCaptor<Long> offset = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> pageSize = ArgumentCaptor.forClass(Integer.class);
        verify(ingestionJobMapper).findLatestPerChatCrossTenant(eq(null), offset.capture(), pageSize.capture());
        assertEquals(0L, offset.getValue());
        assertEquals(100, pageSize.getValue());
    }

    @Test
    void shouldDeleteDocumentAcrossTenants() throws Exception {
        when(ingestionService.deleteDocumentByChat("u-alice", "doc-1")).thenReturn(List.of("a.pdf"));

        mockMvc.perform(delete("/admin/documents/u-alice/doc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.msg", startsWith("deleted:")));

        verify(ingestionService).deleteDocumentByChat(eq("u-alice"), eq("doc-1"));
    }

    @Test
    void shouldReturnOkWithNoDocumentDeletedMsg() throws Exception {
        when(ingestionService.deleteDocumentByChat("u-alice", "doc-x")).thenReturn(List.of());

        mockMvc.perform(delete("/admin/documents/u-alice/doc-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1))
                .andExpect(jsonPath("$.msg").value("no document deleted"));
    }
}
