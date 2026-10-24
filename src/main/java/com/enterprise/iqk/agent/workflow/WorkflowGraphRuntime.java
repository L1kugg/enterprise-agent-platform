package com.enterprise.iqk.agent.workflow;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.llm.ModelCallGuard;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.retrieval.HybridRetrievalService;
import com.enterprise.iqk.retrieval.HybridRetrievalService.HybridRetrievalResult;
import com.enterprise.iqk.service.TenantCostService;
import com.enterprise.iqk.service.platform.PlatformToolConfirmationService;
import com.enterprise.iqk.service.platform.PlatformWorkflowService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 工作流图运行时（V36）：把 WorkflowStudio 编排的图"运行为任务"——
 * 沿边顺序执行 START/END/HTTP_REQUEST/TOOL_CALL/LLM/KNOWLEDGE_RETRIEVAL/CONDITION 节点，
 * HUMAN_APPROVAL 节点挂起任务（WAITING_APPROVAL + 24h 审批单），审批事件回调继续/终止。
 * 全程复用 AgentWorkflowEngine 留痕（任务/步骤/事件三表可回放）与 DeepResearch 的
 * 异步受理模式（同步落库受理 → 专用线程池 → 队列满 429）。
 * 已知边界：挂起上下文存进程内存，同 JVM 审批后可续；应用重启即由孤儿清扫器收尾置 FAILED。
 */
@Slf4j
@Service
public class WorkflowGraphRuntime {

    private static final int POOL_WORKERS = 2;
    private static final int POOL_QUEUE = 20;
    private static final int MAX_VAR_CHARS = 8000;

    private final PlatformAssetMapper assetMapper;
    private final AgentWorkflowEngine workflowEngine;
    private final PlatformWorkflowService workflowService;
    private final HybridRetrievalService hybridRetrievalService;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;
    private final ModelCallGuard modelCallGuard;
    private final PlatformToolConfirmationService confirmationService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ChatClient agentChatClient;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            POOL_WORKERS, POOL_WORKERS, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(POOL_QUEUE),
            runnable -> {
                Thread thread = new Thread(runnable, "workflow-graph");
                thread.setDaemon(true);
                return thread;
            });

    /** 挂起在人工审批节点的运行上下文（taskId → 断点）；审批事件回调后移除并续跑。 */
    private final Map<String, PendingRun> pendingApprovals = new ConcurrentHashMap<>();

    private record PendingRun(Long workflowAssetId, String tenantId, String chatId, JsonNode graph,
                              JsonNode currentNode, Map<String, Object> context, int stepOrder,
                              boolean resumedFromApproval) {
    }

    public WorkflowGraphRuntime(PlatformAssetMapper assetMapper, AgentWorkflowEngine workflowEngine,
                                PlatformWorkflowService workflowService,
                                HybridRetrievalService hybridRetrievalService, ModelRouter modelRouter,
                                TenantCostService tenantCostService, ModelCallGuard modelCallGuard,
                                PlatformToolConfirmationService confirmationService, ObjectMapper objectMapper,
                                MeterRegistry meterRegistry, ChatClient agentChatClient) {
        this.assetMapper = assetMapper;
        this.workflowEngine = workflowEngine;
        this.workflowService = workflowService;
        this.hybridRetrievalService = hybridRetrievalService;
        this.modelRouter = modelRouter;
        this.tenantCostService = tenantCostService;
        this.modelCallGuard = modelCallGuard;
        this.confirmationService = confirmationService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.agentChatClient = agentChatClient;
    }

    /**
     * 受理一次图工作流运行：同步落任务库（受理即可查）后提交线程池异步执行。
     * 工作流必须已上线（ONLINE）；输入 Map 作为初始变量（CONDITION 边条件与模板占位可直接引用）。
     */
    public String run(Long workflowAssetId, String tenantId, String chatId, Map<String, Object> input) {
        PlatformAsset workflow = assetMapper.findByIdAndTypeVisible(workflowAssetId, tenantId, "workflows");
        if (workflow == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工作流不存在");
        }
        if (!"ONLINE".equalsIgnoreCase(workflow.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流未上线，请先在 Studio 上线");
        }
        JsonNode graph = workflowService.validatedGraph(workflow);
        AgentTaskRecord task = workflowEngine.startTask(tenantId, "WORKFLOW_GRAPH",
                workflow.getName(), null, chatId, null);
        Map<String, Object> context = new LinkedHashMap<>(input == null ? Map.of() : input);
        submit(task.getTaskId(), new PendingRun(workflowAssetId, tenantId, chatId, graph,
                workflowService.startNodeOf(graph), context, 1, false));
        return task.getTaskId();
    }

    /** 审批事件回调：通过 → 审批节点落 COMPLETED 并从下一节点继续；拒绝 → 任务置 FAILED。 */
    @EventListener
    public void onWorkflowApproval(WorkflowApprovalEvent event) {
        PendingRun pending = pendingApprovals.remove(event.taskId());
        if (pending == null) {
            log.warn("工作流审批事件无挂起上下文（可能应用重启已被清扫）: taskId={}", event.taskId());
            return;
        }
        if (!event.approved()) {
            workflowEngine.failTask(event.taskId(), "工作流审批被拒绝");
            workflowEngine.recordTaskMetrics("WORKFLOW_GRAPH", "FAILED", 0);
            return;
        }
        workflowEngine.completeStep(event.stepId(), "COMPLETED", Map.of("approved", true),
                Map.of("status", "approved"), null, "HUMAN_APPROVAL", null, 0, 0, 0, null);
        JsonNode next = workflowService.nextNodeAfter(pending.graph(), pending.currentNode(), pending.context());
        submit(event.taskId(), new PendingRun(pending.workflowAssetId(), pending.tenantId(), pending.chatId(),
                pending.graph(), next, pending.context(), pending.stepOrder(), true));
    }

    private void submit(String taskId, PendingRun pending) {
        try {
            executor.execute(() -> executeSafely(taskId, pending));
        } catch (RejectedExecutionException ex) {
            workflowEngine.abandonTask(taskId, "workflow graph run queue full");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "工作流执行队列已满，请稍后重试");
        }
    }

    private void executeSafely(String taskId, PendingRun pending) {
        try {
            execute(taskId, pending);
        } catch (Exception ex) {
            String reason = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
            log.warn("工作流图执行失败: taskId={}, reason={}", taskId, reason);
            workflowEngine.failTask(taskId, reason);
            workflowEngine.recordTaskMetrics("WORKFLOW_GRAPH", "FAILED", 0);
        }
    }

    private void execute(String taskId, PendingRun pending) {
        JsonNode current = pending.currentNode();
        Map<String, Object> context = pending.context();
        String tenantId = pending.tenantId();
        int stepOrder = pending.stepOrder();
        // 审批恢复从挂起态起步（保证状态机迁移边合法：WAITING_APPROVAL → 执行态）
        WorkflowState state = pending.resumedFromApproval() ? WorkflowState.WAITING_APPROVAL : WorkflowState.PLANNING;
        int guard = 0;
        int nodeBudget = pending.graph().path("nodes").size() * 4 + 4;
        while (current != null && guard++ < nodeBudget) {
            String nodeId = current.path("id").asText();
            String nodeType = current.path("type").asText().toUpperCase(Locale.ROOT);
            // CONDITION 纯路由：不落步骤，交给 nextNodeAfter 按边条件选路
            if ("CONDITION".equals(nodeType)) {
                current = workflowService.nextNodeAfter(pending.graph(), current, context);
                continue;
            }
            // HUMAN_APPROVAL：创建 24h 审批单 → 任务挂起 WAITING_APPROVAL → 线程退出等回调
            if ("HUMAN_APPROVAL".equals(nodeType)) {
                hangForApproval(taskId, tenantId, pending, current, stepOrder, state);
                return;
            }

            AgentStepRecord step = workflowEngine.startStep(taskId, nodeType, stepOrder,
                    Map.<String, Object>of("node", nodeId, "parameters", summarize(current.path("parameters"))));
            long stepStartNs = System.nanoTime();
            String error = null;
            try {
                state = advance(taskId, state, nodeType);
                switch (nodeType) {
                    case "START" -> { }
                    case "END" -> {
                        finishSuccessfully(taskId, context, step, stepStartNs);
                        return;
                    }
                    case "HTTP_REQUEST" -> workflowService.executeHttpNode(current, context);
                    case "TOOL_CALL" -> workflowService.executeToolNode(current, context);
                    case "LLM" -> executeLlm(current, context, tenantId);
                    case "KNOWLEDGE_RETRIEVAL" -> executeRetrieval(current, context, tenantId, taskId);
                    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "节点类型不支持运行时执行：" + nodeType);
                }
            } catch (Exception ex) {
                error = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
            }
            long latency = Math.max(1, (System.nanoTime() - stepStartNs) / 1_000_000);
            workflowEngine.completeStep(step.getStepId(), error == null ? "COMPLETED" : "FAILED",
                    Map.of("context", summarize(context)), Map.of("node", nodeId),
                    null, nodeType, null, 0, 0, latency, error);
            if (error != null) {
                throw new IllegalStateException("节点 " + nodeId + " 执行失败：" + error);
            }
            current = workflowService.nextNodeAfter(pending.graph(), current, context);
            stepOrder++;
        }
        workflowEngine.failTask(taskId, "工作流未连接到 END 节点");
    }

    /** 人工审批挂起：创建 24h 审批单（confirmationId 进上下文供前端取用），任务转 WAITING_APPROVAL。 */
    private void hangForApproval(String taskId, String tenantId, PendingRun pending, JsonNode node,
                                 int stepOrder, WorkflowState state) {
        String nodeId = node.path("id").asText();
        JsonNode params = node.path("parameters");
        String message = params.path("message").asText(params.path("description").asText("请审批该工作流步骤"));
        AgentStepRecord step = workflowEngine.startStep(taskId, "HUMAN_APPROVAL", stepOrder,
                Map.of("node", nodeId, "message", message));
        var confirmation = confirmationService.createForWorkflow(
                tenantId, pending.chatId(), taskId, step.getStepId(), node.path("name").asText(nodeId), message);
        pending.context().put(nodeId + ".confirmationId", confirmation.getId());
        // 步骤落 WAITING_APPROVAL + confirmationId（任务详情可查，前端审批入口由此渲染）
        workflowEngine.completeStep(step.getStepId(), "WAITING_APPROVAL",
                Map.of("confirmationId", confirmation.getId()), Map.of("node", nodeId, "message", message),
                null, "HUMAN_APPROVAL", null, 0, 0, 0, null);
        workflowEngine.transitionStatus(taskId, state, WorkflowState.WAITING_APPROVAL);
        pendingApprovals.put(taskId, new PendingRun(pending.workflowAssetId(), tenantId, pending.chatId(),
                pending.graph(), node, pending.context(), stepOrder + 1, false));
        log.info("工作流挂起等待审批: taskId={}, node={}, confirmationId={}", taskId, nodeId, confirmation.getId());
    }

    /** LLM 节点：档位路由 + 预算闸门 + 熔断调用，输出写 {nodeId}.output 与可选 outputKey 变量。 */
    private void executeLlm(JsonNode node, Map<String, Object> context, String tenantId) {
        JsonNode params = node.path("parameters");
        String userPrompt = params.path("userPrompt").asText(params.path("prompt").asText(""));
        if (userPrompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LLM 节点缺少 userPrompt");
        }
        String systemPrompt = params.path("systemPrompt").asText("你是企业工作流中的处理节点，请依据输入完成任务并输出结果。");
        String filledUser = fillTemplate(userPrompt, context);
        String filledSystem = fillTemplate(systemPrompt, context);
        String modelProfile = params.path("modelProfile").asText("balanced");

        ModelRouter.ModelRouteDecision decision = modelRouter.resolve(
                modelProfile, "workflow_graph", tenantId, node.path("id").asText());
        long inputTokens = tenantCostService.estimateTokens(filledUser);
        tenantCostService.assertBudget(tenantId, decision.costTier(), inputTokens, 800);
        String raw = modelCallGuard.call("workflow-graph", () -> agentChatClient.prompt()
                .options(ChatOptions.builder().model(decision.model()).build())
                .system(filledSystem)
                .user(filledUser)
                .call()
                .content());
        tenantCostService.recordUsage(tenantId, null, decision.costTier(), inputTokens,
                tenantCostService.estimateTokens(raw), "workflow_graph");

        String output = truncate(raw, MAX_VAR_CHARS);
        context.put(node.path("id").asText() + ".output", output);
        String outputKey = params.path("outputKey").asText("");
        if (!outputKey.isBlank()) {
            context.put(outputKey, output);
        }
    }

    /** 知识检索节点：四路混合检索，命中文档摘要写 {nodeId}.documents 与可选 outputKey 变量。 */
    private void executeRetrieval(JsonNode node, Map<String, Object> context, String tenantId, String taskId) {
        JsonNode params = node.path("parameters");
        String query = fillTemplate(params.path("query").asText(""), context);
        if (query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "KNOWLEDGE_RETRIEVAL 节点缺少 query");
        }
        int topK = Math.max(1, Math.min(params.path("topK").asInt(5), 20));
        HybridRetrievalResult result = hybridRetrievalService.retrieve(
                query, tenantId, "workflow::" + taskId, topK);
        List<Map<String, Object>> documents = result.documents().stream()
                .limit(topK)
                .map(document -> Map.<String, Object>of(
                        "title", String.valueOf(document.getTitle()),
                        "content", truncate(document.getContent(), 600)))
                .toList();
        context.put(node.path("id").asText() + ".documents", documents);
        String outputKey = params.path("outputKey").asText("");
        if (!outputKey.isBlank()) {
            StringBuilder joined = new StringBuilder();
            for (Map<String, Object> document : documents) {
                joined.append('【').append(document.get("title")).append("】")
                        .append(document.get("content")).append('\n');
            }
            context.put(outputKey, truncate(joined.toString(), MAX_VAR_CHARS));
        }
    }

    private WorkflowState advance(String taskId, WorkflowState current, String nodeType) {
        WorkflowState target = switch (nodeType) {
            case "START" -> WorkflowState.PLANNING;
            case "TOOL_CALL", "HTTP_REQUEST", "KNOWLEDGE_RETRIEVAL" -> WorkflowState.RETRIEVING;
            case "LLM" -> WorkflowState.WRITING;
            default -> WorkflowState.WRITING;
        };
        if (target != current) {
            workflowEngine.transitionStatus(taskId, current, target);
        }
        return target;
    }

    private void finishSuccessfully(String taskId, Map<String, Object> context,
                                    AgentStepRecord step, long stepStartNs) {
        long latency = Math.max(1, (System.nanoTime() - stepStartNs) / 1_000_000);
        String output = summarize(context);
        workflowEngine.completeStep(step.getStepId(), "COMPLETED", Map.of("output", output),
                Map.of("status", "done"), null, "END", null, 0, 0, latency, null);
        workflowEngine.transitionStatus(taskId, WorkflowState.WRITING, WorkflowState.DONE);
        workflowEngine.completeTask(taskId, WorkflowState.DONE, output);
        workflowEngine.recordTaskMetrics("WORKFLOW_GRAPH", "DONE", latency);
    }

    /** 模板占位填充：{{key}} / ${key} → 上下文值（单值截断防上下文爆炸）。 */
    private String fillTemplate(String template, Map<String, Object> context) {
        String result = template;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            String value = truncate(String.valueOf(entry.getValue()), MAX_VAR_CHARS);
            result = result.replace("{{" + entry.getKey() + "}}", value)
                    .replace("${" + entry.getKey() + "}", value);
        }
        return result;
    }

    private String summarize(JsonNode parameters) {
        try {
            return truncate(objectMapper.writeValueAsString(parameters), 2000);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String summarize(Map<String, Object> context) {
        try {
            return truncate(objectMapper.writeValueAsString(context), 4096);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "...[truncated]";
    }
}
