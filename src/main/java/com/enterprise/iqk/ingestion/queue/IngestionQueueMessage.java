package com.enterprise.iqk.ingestion.queue;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
/**
 * 队列消息载体：只携带定位与追踪信息，业务状态一律以数据库任务记录为准。
 */
public class IngestionQueueMessage {
    /** 队列内记录 ID（如 Redis Stream 的 entry ID），用于 ack 与空闲认领 */
    private String recordId;
    /** 入库任务 ID */
    private String jobId;
    /** 链路追踪 ID */
    private String traceId;
}
