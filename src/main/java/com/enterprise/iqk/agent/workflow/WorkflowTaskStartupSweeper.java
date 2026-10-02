package com.enterprise.iqk.agent.workflow;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动任务清扫器：进程启动时把上一进程遗留的非终态 agent_task 即时收尾（置 FAILED）。
 * 与 {@link WorkflowTaskReclaimer} 的分工：sweep 管"启动第一现场"——重启遗留不必等
 * 5 分钟周期 + 30 分钟阈值才被回收；Reclaimer 退化为运行期兜底保险丝。
 * 收尾是守卫式更新（abandonTask 内部 failIfNotTerminal），不会覆盖已正常完成的任务。
 * 注意：run 绝不能抛异常——ApplicationRunner 抛出会中止 Spring Boot 启动，
 * 与 BootstrapApiKeyInitializer 的 fail-closed 语义（初始化失败宁可拒绝服务）相反，
 * 清扫失败只记日志、放行启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowTaskStartupSweeper implements ApplicationRunner {

    /** 单批扫描上限。 */
    private static final int SWEEP_LIMIT = 500;

    /** 批次上限：sweep 只负责上一进程的遗留现场，启动窗口内新建的任务交给 Reclaimer 兜底。 */
    private static final int MAX_BATCHES = 20;

    private final AgentTaskMapper taskMapper;
    private final AgentWorkflowEngine workflowEngine;
    private final MeterRegistry meterRegistry;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int swept = 0;
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                List<AgentTaskRecord> tasks = taskMapper.findNonTerminalTasks(SWEEP_LIMIT);
                if (tasks.isEmpty()) {
                    break;
                }
                for (AgentTaskRecord task : tasks) {
                    boolean updated = workflowEngine.abandonTask(task.getTaskId(),
                            "service restarted before completion");
                    if (updated) {
                        swept++;
                    }
                }
            }
            if (swept > 0) {
                Counter.builder("agent.workflow.task.swept")
                        .description("Non-terminal tasks swept to FAILED at startup")
                        .register(meterRegistry)
                        .increment(swept);
                log.warn("startup sweep closed {} leftover non-terminal task(s)", swept);
            }
        } catch (Exception ex) {
            // 清扫自身故障只记日志，绝不阻断应用启动
            log.warn("startup task sweep failed: reason={}", ex.toString());
        }
    }
}
