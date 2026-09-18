package com.enterprise.iqk.ingestion.queue;

import java.time.Duration;
import java.util.List;

public interface IngestionQueue {
    void publishJob(String jobId, String traceId);

    void publishDlq(String jobId, String traceId, String reason);

    List<IngestionQueueMessage> readBatch(String consumerName, int batchSize, Duration block);

    void ack(String consumerName, String recordId);

    void ensureConsumerGroup();

    /**
     * 认领那些已投递给其他消费者但一直未被 ack 的消息
     * （空闲时间至少为 {@code minIdle}）。后端不支持认领时返回空列表。
     */
    default List<IngestionQueueMessage> claimIdle(String consumerName, Duration minIdle, int maxCount) {
        return List.of();
    }
}
