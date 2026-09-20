package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
/**
 * 单次队列消费/认领的处理结果：是否被本实例拾取 + 任务到达的状态。
 * picked=false 表示任务未被认领（他人执行中或状态不符），调用方不应 ack。
 */
public class IngestionProcessResult {
    /** 入库任务 ID */
    private String jobId;
    /** 任务状态：成功 SUCCEEDED、可重试 RETRY、终局失败 FAILED；未认领时为 RUNNING */
    private IngestionJobStatus status;
    /** 是否由本次调用成功认领并执行 */
    private boolean picked;
    /** 链路追踪 ID，透传自队列消息或请求 */
    private String traceId;
    /** 失败原因（已截断到 1000 字符），成功时为 null */
    private String errorMessage;
}
