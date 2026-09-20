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
/**
 * 入库任务的异步消费端，按 queue-backend 选择消费方式：
 * redis_stream 起固定线程池循环消费（读新消息 + 空闲认领兜底，仅终态 ack）；
 * db_polling 由定时器直接轮询数据库认领任务；另有独立定时器驱动到期重试重新入队。
 */
public class IngestionWorker {

    /** db_polling 单轮轮询最多处理的任务数 */
    private static final int DB_POLL_BATCH = 20;

    private final IngestionService ingestionService;
    private final IngestionProperties ingestionProperties;
    private final IngestionQueue ingestionQueue;
    private final IngestionJobMapper ingestionJobMapper;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService workerPool;

    @PostConstruct
    /** 仅 redis_stream 后端启动：确保消费组存在并按 workerCount 拉起消费线程。 */
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

    /** 消费循环：批量读新消息，空则认领空闲 pending；按任务所属租户执行，仅终态才 ack，异常保留 pending 等待重认领。 */
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
    /** 定时把到期的 RETRY 任务重新发布到队列；db_polling 后端由轮询器消化，跳过。 */
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
    /** 停止消费循环并立即关闭线程池。 */
    public void shutdown() {
        running.set(false);
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
    }
}
