package com.enterprise.iqk.agent.workflow;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * 孤儿任务回收器：定时把长时间停留在非终态的 agent_task 置为 FAILED。
 * 兜底两类第一现场收不住的遗留：
 * 1) SSE 断连（Reactor cancel 信号在进程内能处理，但进程重启/强杀时来不及）；
 * 2) 编排层异常路径遗漏终态收尾。
 * 回收是守卫式更新（abandonTask 内部 failIfNotTerminal），不会覆盖已完成的任务。
 */
public class WorkflowTaskReclaimer {

    /** 单次回收扫描的最大任务数，防一次拖走过多连接。 */
    private static final int BATCH_LIMIT = 100;

    private final AgentTaskMapper taskMapper;
    private final AgentWorkflowEngine workflowEngine;
    private final MeterRegistry meterRegistry;

    /** 任务停留在非终态超过该分钟数视为孤儿（updated_at 为准，步骤/状态推进会刷新它）。 */
    @Value("${app.workflow.stale-task-minutes:30}")
    private long staleMinutes;

    /** 每 5 分钟扫一次（间隔可配 app.workflow.reclaim-interval-ms）。 */
    @Scheduled(fixedDelayString = "${app.workflow.reclaim-interval-ms:300000}")
    public void reclaimOrphanTasks() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleMinutes);
            List<AgentTaskRecord> stale = taskMapper.findStaleTasks(cutoff, BATCH_LIMIT);
            if (stale.isEmpty()) {
                return;
            }
            int reclaimed = 0;
            for (AgentTaskRecord task : stale) {
                boolean updated = workflowEngine.abandonTask(task.getTaskId(),
                        "reclaimed: orphaned task (client disconnect or process restart)");
                if (updated) {
                    reclaimed++;
                }
            }
            Counter.builder("agent.workflow.task.reclaimed")
                    .description("Orphaned non-terminal tasks reclaimed to FAILED")
                    .register(meterRegistry)
                    .increment(reclaimed);
            log.warn("reclaimed {} orphan task(s) stale over {} minutes (scanned {})",
                    reclaimed, staleMinutes, stale.size());
        } catch (Exception ex) {
            // 回收自身故障只记日志，绝不影响调度线程
            log.warn("orphan task reclaim failed: reason={}", ex.toString());
        }
    }
}
