package com.enterprise.iqk.ingestion.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "app.ingestion", name = "queue-backend", havingValue = "db_polling")
/**
 * db_polling 后端的空队列实现：任务发布与消费全部由数据库轮询承担，
 * 队列侧方法均为空操作，仅保证 IngestionService 的依赖注入完整。
 */
public class NoopIngestionQueue implements IngestionQueue {
    @Override
    public void publishJob(String jobId, String traceId) {
        // 空实现
    }

    @Override
    public void publishDlq(String jobId, String traceId, String reason) {
        // 空实现
    }

    @Override
    /** db_polling 模式没有队列可读，恒返回空列表。 */
    public List<IngestionQueueMessage> readBatch(String consumerName, int batchSize, Duration block) {
        return List.of();
    }

    @Override
    public void ack(String consumerName, String recordId) {
        // 空实现
    }

    @Override
    public void ensureConsumerGroup() {
        // 空实现
    }
}
