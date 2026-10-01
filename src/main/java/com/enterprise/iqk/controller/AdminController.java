package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.vo.AdminDocumentVO;
import com.enterprise.iqk.domain.vo.PagedResult;
import com.enterprise.iqk.ingestion.IngestionService;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import com.enterprise.iqk.util.SqlLikeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 管理员跨租户文档总览：按 (tenant_id, chat_id) 分组展示最新一条入库任务，
 * 支持分页/搜索与级联删除（向量→磁盘→任务记录）。仅 ROLE_ADMIN 可访问，
 * URL 级与 @PreAuthorize 双保险；租户信息来自列表数据回传，不取 MDC 当前租户。
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final IngestionJobMapper ingestionJobMapper;
    private final IngestionService ingestionService;

    @GetMapping("/documents")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResult<AdminDocumentVO> listDocuments(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "search", required = false) String search) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        String keyword = StringUtils.hasText(search) ? SqlLikeUtils.escapeForLike(search.trim()) : null;

        long total = ingestionJobMapper.countLatestPerChatCrossTenant(keyword);
        if (total == 0) {
            return new PagedResult<>(Collections.emptyList(), 0, safePage, safePageSize);
        }
        long offset = (long) (safePage - 1) * safePageSize;
        List<AdminDocumentVO> items = ingestionJobMapper
                .findLatestPerChatCrossTenant(keyword, offset, safePageSize)
                .stream()
                .map(this::toVO)
                .toList();
        return new PagedResult<>(items, total, safePage, safePageSize);
    }

    @DeleteMapping("/documents/{tenantId}/{chatId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> deleteDocument(@PathVariable String tenantId, @PathVariable String chatId) {
        List<String> removedFiles = ingestionService.deleteDocumentByChat(tenantId, chatId);
        String msg = removedFiles.isEmpty()
                ? "no document deleted"
                : "deleted: " + String.join(", ", removedFiles);
        return Map.of("ok", 1, "msg", msg);
    }

    private AdminDocumentVO toVO(IngestionJob job) {
        return AdminDocumentVO.builder()
                .tenantId(job.getTenantId())
                .chatId(job.getChatId())
                .sourceName(job.getSourceName())
                .status(job.getStatus())
                .attemptCount(job.getAttemptCount())
                .errorMessage(job.getErrorMessage())
                .fileSize(resolveFileSize(job.getFilePath()))
                .createdAt(job.getCreatedAt())
                .finishedAt(job.getFinishedAt())
                .build();
    }

    /** 磁盘 stat 最新任务的 file_path；文件缺失/路径非法时返回 null，不影响列表。 */
    private static Long resolveFileSize(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return null;
        }
        try {
            return Files.size(Path.of(filePath));
        } catch (IOException | SecurityException | InvalidPathException ex) {
            return null;
        }
    }
}
