package com.enterprise.iqk.ingestion.queue;

import com.enterprise.iqk.config.properties.IngestionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ingestion", name = "queue-backend", havingValue = "rabbitmq")
/**
 * RabbitMQ 后端的队列实现：只承担发布（任务流 + 死信流）。
 * 消费由 RabbitMqIngestionListener 的 @RabbitListener 容器驱动并自动 ack，
 * 因此 readBatch/ack/ensureConsumerGroup 均为不适用的空实现。
 */
public class RabbitMqIngestionQueue implements IngestionQueue {

    private final RabbitTemplate rabbitTemplate;
    private final IngestionProperties ingestionProperties;

    @Override
    /** 将任务消息发到业务交换机/路由键。 */
    public void publishJob(String jobId, String traceId) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("traceId", traceId == null ? "" : traceId);
        body.put("publishedAt", Instant.now().toEpochMilli());
        rabbitTemplate.convertAndSend(
                ingestionProperties.getRabbit().getExchange(),
                ingestionProperties.getRabbit().getRoutingKey(),
                body
        );
    }

    @Override
    /** 将终局失败的任务发到死信交换机/路由键。 */
    public void publishDlq(String jobId, String traceId, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("traceId", traceId == null ? "" : traceId);
        body.put("reason", reason == null ? "" : reason);
        body.put("publishedAt", Instant.now().toEpochMilli());
        rabbitTemplate.convertAndSend(
                ingestionProperties.getRabbit().getDlqExchange(),
                ingestionProperties.getRabbit().getDlqRoutingKey(),
                body
        );
    }

    @Override
    /** 拉取接口不适用：消费由监听器容器驱动，恒返回空列表。 */
    public List<IngestionQueueMessage> readBatch(String consumerName, int batchSize, Duration block) {
        return List.of();
    }

    @Override
    public void ack(String consumerName, String recordId) {
        // RabbitMQ 监听器会自动完成 ack。
    }

    @Override
    public void ensureConsumerGroup() {
        // RabbitMQ 无需此操作
    }
}
