package com.enterprise.iqk.ingestion;

import com.enterprise.iqk.config.properties.IngestionProperties;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.ingestion.queue.IngestionQueue;
import com.enterprise.iqk.ingestion.queue.IngestionQueueMessage;
import com.enterprise.iqk.mapper.IngestionJobMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionWorker {

    private static final int DB_POLL_BATCH = 20;

    private final IngestionService ingestionService;
    private final IngestionProperties ingestionProperties;
    private final IngestionQueue ingestionQueue;
    private final IngestionJobMapper ingestionJobMapper;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService workerPool;

    @PostConstruct
    public void start() {
        if (!ingestionProperties.isWorkerEnabled()) {
            return;
        }
        if (!"redis_stream".equalsIgnoreCase(ingestionProperties.getQueueBackend())) {
            return;
        }
        ingestionQueue.ensureConsumerGroup();
        int workers = Math.max(1, ingestionProperties.getWorkerCount());
        workerPool = Executors.newFixedThreadPool(workers);
        for (int i = 0; i < workers; i++) {
            final String consumerName = ingestionProperties.getRedis().getConsumerPrefix() + "-" + i + "-" + UUID.randomUUID();
            workerPool.submit(() -> loopConsume(consumerName));
        }
        log.info("Started redis stream ingestion workers: {}", workers);
    }

    private void loopConsume(String consumerName) {
        while (running.get()) {
            try {
                List<IngestionQueueMessage> records = ingestionQueue.readBatch(
                        consumerName,
                        ingestionProperties.getRedis().getReadBatchSize(),
                        Duration.ofMillis(Math.max(500, ingestionProperties.getPollIntervalMs()))
                );
                if (records.isEmpty()) {
                    // 认领因 worker 崩溃或过慢而滞留在 pending 状态的消息，
                    // 避免任务永远停在 RUNNING；由 app.ingestion.redis.claim-idle-ms 控制。
                    records = ingestionQueue.claimIdle(
                            consumerName,
                            Duration.ofMillis(ingestionProperties.getRedis().getClaimIdleMs()),
                            ingestionProperties.getRedis().getReadBatchSize()
                    );
                }
                if (records.isEmpty()) {
                    continue;
                }
                for (IngestionQueueMessage msg : records) {
                    try {
                        // 从任务记录本身读取租户：该线程没有 MDC，
                        // 且 SQL 现在要求以任务所属租户的身份认领记录。
                        IngestionJob job = ingestionJobMapper.findByJobId(msg.getJobId());
                        String ownerTenant = job == null ? null : job.getTenantId();
                        IngestionProcessResult processed = ingestionService.processQueuedJob(
                                msg.getJobId(), ownerTenant, msg.getTraceId());
                        if (processed.isPicked()) {
                            // 只有任务进入终态才 ack；否则保留 pending 状态，
                            // 让空闲认领逻辑重试，而不是丢失瞬时失败。
                            ingestionQueue.ack(consumerName, msg.getRecordId());
                        }
                    } catch (RuntimeException ex) {
                        log.error("Ingestion worker failed for job {}; leaving message pending for reclaim: {}",
                                msg.getJobId(), ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                log.error("Redis ingestion worker failed for consumer {}", consumerName, ex);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.ingestion.poll-interval-ms:2000}")
    public void enqueueRetryJobs() {
        if (!ingestionProperties.isWorkerEnabled()) {
            return;
        }
        if ("db_polling".equalsIgnoreCase(ingestionProperties.getQueueBackend())) {
            return;
        }
        int enqueued = ingestionService.enqueueReadyRetries(50);
        if (enqueued > 0) {
            log.info("Re-enqueued retry jobs: {}", enqueued);
        }
    }

    /**
     * db_polling 后端的消费者：没有它，已提交的任务会永远停在 PENDING，
     * 除非管理员手动调用 POST /ingestion/jobs/process。
     */
    @Scheduled(fixedDelayString = "${app.ingestion.poll-interval-ms:2000}")
    public void pollDatabaseJobs() {
        if (!ingestionProperties.isWorkerEnabled()) {
            return;
        }
        if (!"db_polling".equalsIgnoreCase(ingestionProperties.getQueueBackend())) {
            return;
        }
        int processed = 0;
        while (processed < DB_POLL_BATCH) {
            IngestionJob job = ingestionJobMapper.findNextReadyJob(LocalDateTime.now());
            if (job == null) {
                break;
            }
            try {
                if (!ingestionService.processQueuedJob(job.getJobId(), job.getTenantId(), job.getTraceId()).isPicked()) {
                    break;
                }
            } catch (Exception ex) {
                log.error("db_polling ingestion failed for job {}", job.getJobId(), ex);
                break;
            }
            processed++;
        }
        if (processed > 0) {
            log.info("Processed db_polling ingestion jobs: {}", processed);
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
    }
}
