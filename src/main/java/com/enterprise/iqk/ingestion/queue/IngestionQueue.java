package com.enterprise.iqk.ingestion.queue;

import java.time.Duration;
import java.util.List;

/**
 * 入库任务队列抽象：统一发布/消费/确认语义，屏蔽 RabbitMQ、Redis Stream、
 * DB 轮询（Noop）等后端差异；实现按 app.ingestion.queue-backend 条件装配。
 * 后端不支持的能力返回空实现而非抛异常，保证 IngestionService 无感切换。
 */
public interface IngestionQueue {
    /** 发布一个待处理任务消息。 */
    void publishJob(String jobId, String traceId);

    /** 任务终局失败后投递死信，便于人工排查。 */
    void publishDlq(String jobId, String traceId, String reason);

    /** 以消费者身份批量读取消息，block 为阻塞等待时长；无消息返回空列表。 */
    List<IngestionQueueMessage> readBatch(String consumerName, int batchSize, Duration block);

    /** 确认一条消息处理完成；后端自动 ack 时为空操作。 */
    void ack(String consumerName, String recordId);

    /** 确保消费组存在（仅 Redis Stream 需要），应用启动时调用一次。 */
    void ensureConsumerGroup();

    /**
     * 认领那些已投递给其他消费者但一直未被 ack 的消息
     * （空闲时间至少为 {@code minIdle}）。后端不支持认领时返回空列表。
     */
    default List<IngestionQueueMessage> claimIdle(String consumerName, Duration minIdle, int maxCount) {
        return List.of();
    }
}
