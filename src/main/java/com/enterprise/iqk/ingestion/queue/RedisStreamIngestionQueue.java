package com.enterprise.iqk.ingestion.queue;

import com.enterprise.iqk.config.properties.IngestionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ingestion", name = "queue-backend", havingValue = "redis_stream")
@RequiredArgsConstructor
/**
 * Redis Stream 后端的队列实现：XADD 发布、XREADGROUP 消费、XACK 确认。
 * 通过扫描 pending 列表 + XCLAIM 认领空闲超时消息，实现 worker 崩溃后的恢复；
 * 消费组在应用启动时幂等创建（BUSYGROUP 视为已存在）。
 */
public class RedisStreamIngestionQueue implements IngestionQueue {

    private final StringRedisTemplate redisTemplate;
    private final IngestionProperties ingestionProperties;

    @Override
    /** XADD 写入任务流。 */
    public void publishJob(String jobId, String traceId) {
        Map<String, String> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("traceId", traceId == null ? "" : traceId);
        body.put("publishedAt", String.valueOf(Instant.now().toEpochMilli()));
        redisTemplate.opsForStream().add(ingestionProperties.getRedis().getStreamKey(), body);
    }

    @Override
    /** XADD 写入死信流。 */
    public void publishDlq(String jobId, String traceId, String reason) {
        Map<String, String> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("traceId", traceId == null ? "" : traceId);
        body.put("reason", reason == null ? "" : reason);
        body.put("publishedAt", String.valueOf(Instant.now().toEpochMilli()));
        redisTemplate.opsForStream().add(ingestionProperties.getRedis().getDlqStreamKey(), body);
    }

    @Override
    /** XREADGROUP 以 lastConsumed 偏移批量读取新消息；无消息返回空列表。 */
    public List<IngestionQueueMessage> readBatch(String consumerName, int batchSize, Duration block) {
        StreamReadOptions options = StreamReadOptions.empty()
                .count(Math.max(1, batchSize))
                .block(block == null ? Duration.ofSeconds(2) : block);
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(
                        Consumer.from(ingestionProperties.getRedis().getConsumerGroup(), consumerName),
                        options,
                        StreamOffset.create(ingestionProperties.getRedis().getStreamKey(), ReadOffset.lastConsumed())
                );
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        return records.stream().map(record -> IngestionQueueMessage.builder()
                .recordId(record.getId().getValue())
                .jobId(valueAsString(record.getValue().get("jobId")))
                .traceId(valueAsString(record.getValue().get("traceId")))
                .build()).toList();
    }

    @Override
    /** XACK 确认指定记录；空 recordId 直接忽略。 */
    public void ack(String consumerName, String recordId) {
        if (!StringUtils.hasText(recordId)) {
            return;
        }
        redisTemplate.opsForStream().acknowledge(
                ingestionProperties.getRedis().getStreamKey(),
                ingestionProperties.getRedis().getConsumerGroup(),
                RecordId.of(recordId)
        );
    }

    @Override
    /** 扫描 pending 列表并 XCLAIM 认领空闲超过 minIdle 的消息，防止 worker 崩溃后消息永久滞留。 */
    public List<IngestionQueueMessage> claimIdle(String consumerName, Duration minIdle, int maxCount) {
        if (!StringUtils.hasText(consumerName) || minIdle == null || minIdle.isZero() || minIdle.isNegative()) {
            return Collections.emptyList();
        }
        String streamKey = ingestionProperties.getRedis().getStreamKey();
        String group = ingestionProperties.getRedis().getConsumerGroup();
        PendingMessagesSummary summary = redisTemplate.opsForStream().pending(streamKey, group);
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return Collections.emptyList();
        }
        PendingMessages pending = redisTemplate.opsForStream()
                .pending(streamKey, group, Range.unbounded(), Math.max(1, maxCount));
        if (pending == null || pending.isEmpty()) {
            return Collections.emptyList();
        }
        List<RecordId> idleIds = new ArrayList<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0) {
                idleIds.add(message.getId());
            }
        }
        if (idleIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                .claim(streamKey, group, consumerName, minIdle, idleIds.toArray(RecordId[]::new));
        if (claimed == null || claimed.isEmpty()) {
            return Collections.emptyList();
        }
        return claimed.stream().map(record -> IngestionQueueMessage.builder()
                .recordId(record.getId().getValue())
                .jobId(valueAsString(record.getValue().get("jobId")))
                .traceId(valueAsString(record.getValue().get("traceId")))
                .build()).toList();
    }

    @Override
    /** 幂等创建 stream 与消费组：先补一条初始化消息确保 stream 存在，BUSYGROUP 静默放过。 */
    public void ensureConsumerGroup() {
        String streamKey = ingestionProperties.getRedis().getStreamKey();
        String group = ingestionProperties.getRedis().getConsumerGroup();
        try {
            // 确保 stream 存在
            redisTemplate.opsForStream().add(streamKey, Map.of("init", "1"));
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), group);
            log.info("Created redis stream consumer group: {}", group);
        } catch (Exception e) {
            if (!e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group create skipped: {}", e.getMessage());
            }
        }
    }

    /** null 安全字符串化，null 归一为空串。 */
    private String valueAsString(Object val) {
        return val == null ? "" : String.valueOf(val);
    }
}
