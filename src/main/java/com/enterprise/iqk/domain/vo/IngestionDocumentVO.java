package com.enterprise.iqk.domain.vo;

import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库「文档清单」的单条文档视图：一个文档 = 当前租户内一个 chat_id 分组的最新一条入库任务。
 * chunkCount 为最近一次成功入库的向量切片数（尚未成功过则为 null）；
 * fileSize 为最新任务磁盘文件大小，文件缺失时为 null。
 */
@Data
@Builder
public class IngestionDocumentVO {
    private String chatId;
    private String sourceName;
    private String sourceType;
    private IngestionJobStatus status;
    private Integer chunkCount;
    private Long fileSize;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
