package com.enterprise.iqk.agent.research;

import com.enterprise.iqk.agent.workflow.AgentTaskRecord;
import com.enterprise.iqk.agent.workflow.AgentWorkflowEngine;
import com.enterprise.iqk.agent.workflow.WorkflowState;
import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.retrieval.HybridRetrievalService;
import com.enterprise.iqk.retrieval.ScoredDocument;
import com.enterprise.iqk.security.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
/**
 * 深度研究编排层（剧本）：只编排状态转移与步骤顺序，不亲自执行。
 * 流程：拆题（ResearchPlannerAgent，PLANNING）→ 逐子问题混合检索（HybridRetrievalService，
 * SEARCHING→RETRIEVING）→ 汇总成稿（ReportWriterAgent，WRITING→DONE）；
 * 每一步经 AgentWorkflowEngine 留痕，任一步失败则任务置 FAILED 并原样上抛。
 * 执行模型：createResearch 同步落库任务后把剧本提交到专用后台线程池（毫秒级受理返回 202），
 * HTTP 请求不随研究时长挂住；队列有界，打满即拒绝（429）。租户 ID 在提交线程上归一化后
 * 闭包传入，后台线程不触碰 TenantContext（照 IngestionWorker 惯例）。
 */
public class DeepResearchService {

    private final ResearchPlannerAgent plannerAgent;
    private final ReportWriterAgent writerAgent;
    private final HybridRetrievalService hybridRetrievalService;
    private final AgentWorkflowEngine workflowEngine;
    private final MeterRegistry meterRegistry;
    /** 记忆子系统：召回租户内早前任务结论（task 层读侧），拆题时避免重复已解决的问题。 */
    private final MemoryService memoryService;

    /** 研究执行专用池：默认 3 工人 + 20 队列，任务耗时分钟级，容量按并发受理上限配置。 */
    private final ThreadPoolExecutor researchExecutor;
    private final int queueCapacity;

    public DeepResearchService(ResearchPlannerAgent plannerAgent,
                               ReportWriterAgent writerAgent,
                               HybridRetrievalService hybridRetrievalService,
                               AgentWorkflowEngine workflowEngine,
                               MeterRegistry meterRegistry,
                               MemoryService memoryService,
                               @Value("${app.research.worker-count:3}") int workerCount,
                               @Value("${app.research.queue-capacity:20}") int queueCapacity) {
        this.plannerAgent = plannerAgent;
        this.writerAgent = writerAgent;
        this.hybridRetrievalService = hybridRetrievalService;
        this.workflowEngine = workflowEngine;
        this.meterRegistry = meterRegistry;
        this.memoryService = memoryService;
        this.queueCapacity = Math.max(1, queueCapacity);
        this.researchExecutor = new ThreadPoolExecutor(Math.max(1, workerCount), Math.max(1, workerCount),
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(this.queueCapacity), runnable -> {
            Thread thread = new Thread(runnable, "deep-research-worker");
            thread.setDaemon(true);
            return thread;
        });
        Gauge.builder("research.pool.active", researchExecutor, ThreadPoolExecutor::getActiveCount)
                .description("Active deep research worker threads")
                .register(meterRegistry);
        Gauge.builder("research.pool.queued", researchExecutor, e -> e.getQueue().size())
                .description("Queued deep research tasks")
                .register(meterRegistry);
    }

    /**
     * 创建研究任务并提交后台执行（毫秒级受理）：同步落库任务（保证客户端首轮轮询必能查到）
     * 后立即返回，报告经 GET /tasks/{taskId}/report 获取。
     * 队列打满时守卫式放弃任务并抛 {@link ResearchQueueFullException}（对外 429）。
     */
    public DeepResearchResult createResearch(ResearchTaskRequest request, String tenantId) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        var task = workflowEngine.startTask(normalizedTenant, "DEEP_RESEARCH",
                request.getTopic(), request.getModelProfile(), null, null);
        long enqueuedAtNs = System.nanoTime();
        try {
            researchExecutor.execute(() -> {
                Timer.builder("research.queue.wait")
                        .description("Deep research queue wait before execution")
                        .register(meterRegistry)
                        .record(System.nanoTime() - enqueuedAtNs, TimeUnit.NANOSECONDS);
                try {
                    executeResearch(task, request, normalizedTenant);
                } catch (Exception ex) {
                    // executeResearch 内部已 failTask 并上抛；这里兜住避免 FutureTask 静默吞异常
                    log.warn("background research task failed: taskId={}, reason={}",
                            task.getTaskId(), ex.toString());
                }
            });
        } catch (RejectedExecutionException ex) {
            workflowEngine.abandonTask(task.getTaskId(), "research queue full: capacity " + queueCapacity);
            Counter.builder("research.task.rejected")
                    .description("Deep research submissions rejected on full queue")
                    .register(meterRegistry)
                    .increment();
            throw new ResearchQueueFullException("深度研究任务队列已满（容量 " + queueCapacity + "），请稍后重试");
        }
        // startTask 返回时任务已转入 PLANNING（受理即可查）；report 由后台跑完后经报告端点获取
        return DeepResearchResult.builder()
                .taskId(task.getTaskId())
                .topic(request.getTopic())
                .report(null)
                .status("PLANNING")
                .build();
    }

    /**
     * 执行完整研究剧本并全程留痕，失败时置任务 FAILED 后重抛原异常。
     * 任务由 createResearch 落库（受理即可查），本方法只推进传入任务的状态与步骤，
     * 不再自行 startTask —— 否则后台会另建一条任务记录，客户端拿到的 taskId 永远停在
     * PLANNING 而真正的报告挂在另一条任务名下（线上踩过的重复落库回归）。
     */
    public DeepResearchResult executeResearch(AgentTaskRecord task, ResearchTaskRequest request, String tenantId) {
        long startedNs = System.nanoTime();
        String normalizedTenant = TenantContext.normalize(tenantId);

        try {
            // 步骤 1：规划 —— 拆解主题
            workflowEngine.transitionStatus(task.getTaskId(), WorkflowState.PLANNING, WorkflowState.SEARCHING);
            var planStep = workflowEngine.startStep(task.getTaskId(), "ResearchPlanner", 1,
                    Map.of("topic", request.getTopic()));
            // 召回租户内最近任务结论注入拆题（task 层记忆的读侧闭环）：
            // 早前研究已得出的结论不重复拆题，尽力而为，失败按无结论降级。
            ResearchPlannerAgent.ResearchPlan plan = plannerAgent.plan(
                    request.getTopic(), recallPriorFindings(normalizedTenant),
                    "research_" + task.getTaskId(), normalizedTenant, request.getModelProfile());
            workflowEngine.completeStep(planStep.getStepId(), "COMPLETED",
                    Map.of("subQuestions", plan.subQuestions(), "strategy", plan.strategy()),
                    plan, null, null, null, 0, 0,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs), null);

            // 步骤 2：针对每个子问题进行搜索与检索
            workflowEngine.transitionStatus(task.getTaskId(), WorkflowState.SEARCHING, WorkflowState.RETRIEVING);
            List<String> findings = new ArrayList<>();
            int stepNum = 2;
            for (String subQ : plan.subQuestions()) {
                var searchStep = workflowEngine.startStep(task.getTaskId(), "RagResearchAgent", stepNum,
                        Map.of("subQuestion", subQ));

                HybridRetrievalService.HybridRetrievalResult retrieval =
                        hybridRetrievalService.retrieve(subQ, normalizedTenant,
                                "research_" + task.getTaskId(), 5);

                StringBuilder finding = new StringBuilder("## " + subQ + "\n\n");
                for (ScoredDocument doc : retrieval.documents()) {
                    String content = doc.getContent() == null ? "" : doc.getContent();
                    finding.append("- [").append(doc.getSourceType()).append("] ")
                            .append(doc.getTitle()).append(": ")
                            .append(content, 0, Math.min(200, content.length()))
                            .append("\n");
                }
                findings.add(finding.toString());

                workflowEngine.completeStep(searchStep.getStepId(), "COMPLETED",
                        Map.of("docsFound", retrieval.documents().size()),
                        Map.of("finding", finding.toString()),
                        null, null, null, 0, 0, 0, null);
                stepNum++;
            }

            // 步骤 3：撰写报告
            workflowEngine.transitionStatus(task.getTaskId(), WorkflowState.RETRIEVING, WorkflowState.WRITING);
            var writeStep = workflowEngine.startStep(task.getTaskId(), "ReportWriter", stepNum,
                    Map.of("topic", request.getTopic()));
            String report = writerAgent.writeReport(request.getTopic(),
                    String.join("\n\n", findings), "research_" + task.getTaskId(), normalizedTenant, request.getModelProfile());
            workflowEngine.completeStep(writeStep.getStepId(), "COMPLETED",
                    Map.of("reportLength", report.length()), report,
                    null, null, null, 0, 0, 0, null);

            workflowEngine.completeTask(task.getTaskId(), WorkflowState.DONE, report);
            workflowEngine.recordTaskMetrics("DEEP_RESEARCH", "DONE",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs));

            return DeepResearchResult.builder()
                    .taskId(task.getTaskId())
                    .topic(request.getTopic())
                    .report(report)
                    .status("DONE")
                    .build();

        } catch (RuntimeException e) {
            log.error("Deep research failed for task {}", task.getTaskId(), e);
            workflowEngine.failTask(task.getTaskId(), e.getMessage());
            workflowEngine.recordTaskMetrics("DEEP_RESEARCH", "FAILED",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs));
            throw e;
        }
    }

    /** 召回租户内最近 5 条任务结论拼成参考文本（每条截 200 字符）；无结论或失败返回空串。 */
    private String recallPriorFindings(String tenantId) {
        try {
            List<MemoryItemRecord> memories = memoryService.queryRecentTaskMemories(tenantId, 5);
            if (memories == null || memories.isEmpty()) {
                return "";
            }
            StringBuilder findings = new StringBuilder();
            for (MemoryItemRecord memory : memories) {
                String content = memory.getContent() == null ? "" : memory.getContent();
                findings.append("- ").append(content, 0, Math.min(200, content.length())).append("\n");
            }
            return findings.toString();
        } catch (Exception ex) {
            log.warn("任务结论召回失败（不影响研究主链路）: reason={}", ex.toString());
            return "";
        }
    }

    @Data
    @Builder
    /** 研究执行结果：任务 ID、主题、报告全文与终态 */
    public static class DeepResearchResult {
        private String taskId;
        private String topic;
        private String report;
        private String status;
    }

    /** 应用关闭时中断在途研究任务并限时等待；漏网未收尾的任务由启动 sweep（WorkflowTaskStartupSweeper）收尾。 */
    @PreDestroy
    void shutdownResearchExecutor() {
        researchExecutor.shutdownNow();
        try {
            if (!researchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("deep research executor did not terminate within 5s; leftover tasks will be swept at next startup");
            }
        } catch (InterruptedException ex) {
            researchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
