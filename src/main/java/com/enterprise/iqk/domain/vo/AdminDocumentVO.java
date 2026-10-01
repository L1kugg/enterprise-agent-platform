package com.enterprise.iqk.domain.vo;

import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员跨租户文档总览的单条文档视图：一个文档 = (tenantId, chatId) 分组的最新一条入库任务。
 * fileSize 为最新任务磁盘文件大小，文件缺失时为 null。
 */
@Data
@Builder
public class AdminDocumentVO {
    private String tenantId;
    private String chatId;
    private String sourceName;
    private IngestionJobStatus status;
    private Integer attemptCount;
    private String errorMessage;
    private Long fileSize;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
