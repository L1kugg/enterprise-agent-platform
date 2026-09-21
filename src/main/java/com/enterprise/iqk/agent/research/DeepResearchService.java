package com.enterprise.iqk.agent.research;

import com.enterprise.iqk.agent.workflow.AgentWorkflowEngine;
import com.enterprise.iqk.agent.workflow.WorkflowState;
import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.retrieval.HybridRetrievalService;
import com.enterprise.iqk.retrieval.ScoredDocument;
import com.enterprise.iqk.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 深度研究编排层（剧本）：只编排状态转移与步骤顺序，不亲自执行。
 * 流程：拆题（ResearchPlannerAgent，PLANNING）→ 逐子问题混合检索（HybridRetrievalService，
 * SEARCHING→RETRIEVING）→ 汇总成稿（ReportWriterAgent，WRITING→DONE）；
 * 每一步经 AgentWorkflowEngine 留痕，任一步失败则任务置 FAILED 并原样上抛。
 */
public class DeepResearchService {

    private final ResearchPlannerAgent plannerAgent;
    private final ReportWriterAgent writerAgent;
    private final HybridRetrievalService hybridRetrievalService;
    private final AgentWorkflowEngine workflowEngine;
    private final MeterRegistry meterRegistry;
    /** 记忆子系统：召回租户内早前任务结论（task 层读侧），拆题时避免重复已解决的问题。 */
    private final MemoryService memoryService;

    /** 执行完整研究剧本并全程留痕；返回报告全文，失败时置任务 FAILED 后重抛原异常。 */
    public DeepResearchResult executeResearch(ResearchTaskRequest request, String tenantId) {
        long startedNs = System.nanoTime();
        String normalizedTenant = TenantContext.normalize(tenantId);

        var task = workflowEngine.startTask(normalizedTenant, "DEEP_RESEARCH",
                request.getTopic(), request.getModelProfile(), null, null);

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
}
