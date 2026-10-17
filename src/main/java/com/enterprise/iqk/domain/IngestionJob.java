package com.enterprise.iqk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("ingestion_job")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobId;
    private String tenantId;
    private String chatId;
    /** 归属知识库资产 ID（platform_asset.id，FR-R5-01）；NULL = 未归属，仅租户级检索可见 */
    private Long knowledgeBaseId;
    private String sourceType;
    private String sourceName;
    private String filePath;
    private String idempotencyKey;
    private IngestionJobStatus status;
    private String traceId;
    private Integer attemptCount;
    private Integer maxRetries;
    private Integer chunkCount;
    private String errorMessage;
    private LocalDateTime nextRetryAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
