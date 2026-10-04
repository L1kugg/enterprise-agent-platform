package com.enterprise.iqk.agent.workflow;

import com.enterprise.iqk.agent.harness.AgentAction;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.agent.harness.PlannerActionCatalog;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.MemoryInjectionAdvisor;
import com.enterprise.iqk.util.AnswerStreamSupport;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.security.UserContext;
import com.enterprise.iqk.service.TenantCostService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** ReAct 执行层 Agent：以 AgentWorkflowEngine 留痕驱动 ReAct 循环（reason→harness 执行动作→观测并入滚动上下文，最多 MAX_STEPS 轮），全程可回放；工作流状态由 mapToWorkflowState 按轮次映射，JUDGING/REFLECTING 仅为进度标签而非语义判定。 */
@Service
@RequiredArgsConstructor
public class WorkflowReactAgentService {

    private static final int MAX_STEPS = 6; // ReAct 最大轮次，用尽仍未 finish 则强制总结成稿

    private final AgentWorkflowEngine workflowEngine;
    private final AgentHarnessService agentHarnessService;
    private final PlannerActionCatalog plannerActionCatalog;
    /** 内部推理专用客户端（无对话记忆组件），避免未设 CONVERSATION_ID 时记忆断言失败 */
    private final ChatClient agentChatClient;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ReactChatResponseVO chat(ReactChatRequestVO request) { // 同步 ReAct：跑完循环后一次性返回答案+轨迹+引用；异常置任务 FAILED 后上抛
        validateRequest(request);
        String tenantId = currentTenantId();
        long startedNs = System.nanoTime();

        AgentTaskRecord task = workflowEngine.startTask(
                tenantId, "REACT", request.getPrompt(),
                request.getModelProfile(), request.getChatId(), null);

        ModelRouter.ModelRouteDecision routeDecision = resolveRouteDecision(
                request.getModelProfile(), "react", request.getChatId(), tenantId);

        List<ReactTraceStepVO> trace = new ArrayList<>();
        String rollingContext = "";

        try {
            for (int stepNum = 1; stepNum <= MAX_STEPS; stepNum++) {
                AgentStepRecord stepRecord = workflowEngine.startStep(
                        task.getTaskId(), "planner", stepNum,
                        Map.of("prompt", request.getPrompt(), "rollingContext", rollingContext));

                long stepStartNs = System.nanoTime();
                ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId);

                if ("finish".equals(decision.action())) {
                    String answer = StringUtils.hasText(decision.answer())
                            ? decision.answer()
                            : summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId);
                    Map<String, Object> obs = new LinkedHashMap<>();
                    obs.put("status", "completed");
                    obs.put("citations", decision.citations() != null ? decision.citations() : List.of());
                    obs.put("evidence", decision.evidence() != null ? decision.evidence() : List.of());

                    trace.add(buildTraceStep(stepNum, decision, obs));
                    workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED",
                            Map.of("answer", answer), obs,
                            decision.thought(), "finish", decision.actionInput(),
                            0, 0, elapsedMs(stepStartNs), null);
                    workflowEngine.completeTask(task.getTaskId(), WorkflowState.DONE, answer);
                    workflowEngine.recordTaskMetrics("REACT", "DONE", elapsedMs(startedNs));
                    return success(request.getChatId(), answer, trace, routeDecision, task.getTaskId());
                }

                workflowEngine.transitionStatus(task.getTaskId(),
                        mapToWorkflowState(stepNum), mapToWorkflowState(stepNum + 1));

                Object observation = executeAction(request, decision.action(), decision.actionInput(), tenantId,
                        task.getTaskId(), stepRecord.getStepId());
                trace.add(buildTraceStep(stepNum, decision, observation));
                workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED",
                        null, observation,
                        decision.thought(), decision.action(), decision.actionInput(),
                        0, 0, elapsedMs(stepStartNs), null);

                rollingContext = appendContext(rollingContext, decision.action(), observation);
            }

            String answer = summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId);
            workflowEngine.completeTask(task.getTaskId(), WorkflowState.DONE, answer);
            workflowEngine.recordTaskMetrics("REACT", "DONE", elapsedMs(startedNs));
            return success(request.getChatId(), answer, trace, routeDecision, task.getTaskId());

        } catch (RuntimeException e) {
            workflowEngine.failTask(task.getTaskId(), e.getMessage());
            workflowEngine.recordTaskMetrics("REACT", "FAILED", elapsedMs(startedNs));
            throw e;
        }
    }

    public Flux<String> stream(ReactChatRequestVO request) { // 真流式 ReAct（SSE）：每步完成即发 trace 帧，再流式发 token，最后发 done；断连取消时任务守卫式置 FAILED 防孤儿
        long startedNs = System.nanoTime();
        AtomicReference<Long> firstTokenMs = new AtomicReference<>(null);
        AtomicReference<String> outcomeRef = new AtomicReference<>("error");
        AtomicReference<String> taskIdRef = new AtomicReference<>("");

        return Flux.defer(() -> {
                    validateRequest(request);
                    String tenantId = currentTenantId();

                    AgentTaskRecord task = workflowEngine.startTask(
                            tenantId, "REACT_STREAM", request.getPrompt(),
                            request.getModelProfile(), request.getChatId(), null);
                    taskIdRef.set(task.getTaskId());

                    StreamState state = new StreamState(request, task,
                            resolveRouteDecision(request.getModelProfile(), "react", request.getChatId(), tenantId),
                            tenantId, new ArrayList<>(), new AtomicReference<>(""),
                            new AtomicReference<>(""), firstTokenMs, outcomeRef, startedNs);
                    return stepFlux(state, 1).concatWith(finalAnswerFlux(state));
                })
                .onErrorResume(ex -> {
                    String message = StringUtils.hasText(ex.getMessage())
                            ? ex.getMessage() : "回答生成失败，请稍后重试";
                    // 将任务标记为 FAILED，避免失败的流式请求把工作流记录
                    // 遗留在非终态成为孤儿。
                    String failedTaskId = taskIdRef.get();
                    if (StringUtils.hasText(failedTaskId)) {
                        workflowEngine.failTask(failedTaskId, message);
                        workflowEngine.recordTaskMetrics("REACT_STREAM", "FAILED", elapsedMs(startedNs));
                    }
                    return Flux.just(formatSse("error", toJson(Map.of("message", message))));
                })
                .doFinally(signal -> {
                    // Reactor 的 cancel 不是 error：断连时 onErrorResume 不触发，
                    // 尾部 completeTask 帧也不会被订阅，任务会永久停在非终态 ——
                    // 在此显式收尾（守卫式：恰与正常完成竞态时不覆盖 DONE）。
                    if (signal == SignalType.CANCEL) {
                        outcomeRef.set("cancelled");
                        String taskId = taskIdRef.get();
                        if (StringUtils.hasText(taskId)) {
                            workflowEngine.abandonTask(taskId, "client disconnected (stream cancelled)");
                        }
                    }
                    recordStreamMetrics(startedNs, firstTokenMs.get(), outcomeRef.get());
                });
    }

    /** 递归单步流：每完成一轮 ReAct（reason→动作执行）立即向下游发一条 trace 帧，循环不再整体阻塞在 defer 里跑完才发首帧。 */
    private Flux<String> stepFlux(StreamState state, int stepNum) {
        return Flux.defer(() -> {
            if (stepNum > MAX_STEPS) {
                return Flux.<String>empty(); // 轮次用尽，交由 finalAnswerFlux 强制总结成稿
            }
            AgentStepRecord stepRecord = workflowEngine.startStep(
                    state.task().getTaskId(), "planner", stepNum,
                    Map.of("prompt", state.request().getPrompt()));

            long stepStartNs = System.nanoTime();
            ReasonDecision decision = reason(state.request(), state.rollingContext().get(),
                    state.trace(), state.routeDecision(), state.tenantId());

            if ("finish".equals(decision.action())) {
                Map<String, Object> obs = new LinkedHashMap<>();
                obs.put("status", "completed");
                ReactTraceStepVO stepVo = buildTraceStep(stepNum, decision, obs);
                state.trace().add(stepVo);
                state.directAnswer().set(emptyIfBlank(decision.answer()));
                workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED",
                        Map.of("answer", state.directAnswer().get()), obs,
                        decision.thought(), "finish", decision.actionInput(),
                        0, 0, elapsedMs(stepStartNs), null);
                return Flux.just(formatSse("trace", toJson(stepVo)));
            }

            Object observation = executeAction(state.request(), decision.action(), decision.actionInput(),
                    state.tenantId(), state.task().getTaskId(), stepRecord.getStepId());
            ReactTraceStepVO stepVo = buildTraceStep(stepNum, decision, observation);
            state.trace().add(stepVo);
            workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED",
                    null, observation,
                    decision.thought(), decision.action(), decision.actionInput(),
                    0, 0, elapsedMs(stepStartNs), null);
            state.rollingContext().set(
                    appendContext(state.rollingContext().get(), decision.action(), observation));
            return Flux.just(formatSse("trace", toJson(stepVo)))
                    .concatWith(stepFlux(state, stepNum + 1));
        });
    }

    /** 成稿流：finish 直答则原样发 token，否则流式调用模型；末尾发 done 帧并置任务 DONE。 */
    private Flux<String> finalAnswerFlux(StreamState state) {
        StringBuilder answerBuilder = new StringBuilder();
        return Flux.<String>defer(() -> {
                    String direct = state.directAnswer().get();
                    return StringUtils.hasText(direct) ? AnswerStreamSupport.chunked(direct)
                            : callModelStream("你是企业级AI助手，请结合轨迹和观察信息给出最终答案。",
                            buildFinalPrompt(state.request(), state.trace(), state.rollingContext().get()),
                            state.routeDecision(), state.tenantId(), "react_final");
                })
                .map(token -> {
                    if (state.firstTokenMs().get() == null) {
                        state.firstTokenMs().set(elapsedMs(state.startedNs()));
                    }
                    answerBuilder.append(token);
                    return formatSse("token", toJson(Map.of("token", token)));
                })
                .concatWith(Flux.defer(() -> {
                    if (state.firstTokenMs().get() == null) {
                        state.firstTokenMs().set(elapsedMs(state.startedNs())); // 零 token 兜底计时
                    }
                    String answer = answerBuilder.toString();
                    ReactChatResponseVO response = success(state.request().getChatId(), answer,
                            state.trace(), state.routeDecision(), state.task().getTaskId());
                    workflowEngine.completeTask(state.task().getTaskId(), WorkflowState.DONE, answer);
                    state.outcomeRef().set("success");
                    return Flux.just(formatSse("done", toJson(response)));
                }));
    }

    /** 流式链路共享状态：递归 flux 之间传递的可变载体。 */
    private record StreamState(ReactChatRequestVO request, AgentTaskRecord task,
                               ModelRouter.ModelRouteDecision routeDecision, String tenantId,
                               List<ReactTraceStepVO> trace,
                               AtomicReference<String> rollingContext,
                               AtomicReference<String> directAnswer,
                               AtomicReference<Long> firstTokenMs,
                               AtomicReference<String> outcomeRef,
                               long startedNs) {
    }

    private ReasonDecision reason(ReactChatRequestVO request,
                                  String rollingContext,
                                  List<ReactTraceStepVO> trace,
                                  ModelRouter.ModelRouteDecision routeDecision,
                                  String tenantId) {
        // 提示词用中文驱动，模型 thought/answer 才会用中文；JSON 键名与动作名保持英文，解析逻辑依赖它们。
        String planningPrompt = """
                你是一个教育助手场景的 ReAct 规划器，为下一步选择且仅选择一个动作。
                thought（思考）与 answer（回答）必须使用简体中文书写。
                %s
                只返回 JSON：{"thought": "简短的中文推理", "action": "动作名", "action_input": {"key":"value"}, "answer": "仅 finish 时提供，用中文作答"}
                用户问题：
                %s
                滚动上下文：
                %s
                已有轨迹：%s""".formatted(plannerActionCatalog.workflowActionsSection(), request.getPrompt(), emptyIfBlank(rollingContext), toJson(trace));

        try {
            String raw = callModel("你是严格的 JSON ReAct 规划器，只输出合法 JSON，thought 与 answer 用简体中文。",
                    planningPrompt, routeDecision, tenantId, "react_planner");
            return parseDecision(raw);
        } catch (RuntimeException ex) {
            ReactPlannerFallbacks.ReasonFallback fallback = ReactPlannerFallbacks.fallbackDecision(request.getPrompt());
            return new ReasonDecision(fallback.thought(), fallback.action(), fallback.actionInput(),
                    fallback.answer(), fallback.citations(), fallback.evidence());
        }
    }

    private Map<String, Object> executeAction(ReactChatRequestVO request,
                                              String action,
                                              Map<String, Object> actionInput,
                                              String tenantId,
                                              String taskId,
                                              String stepId) {
        return agentHarnessService.execute(new AgentAction(
                action,
                actionInput,
                request.getPrompt(),
                tenantId,
                request.getChatId(),
                request.getModelProfile(),
                taskId,
                stepId
        )).toMap();
    }

    private String summarizeAnswer(ReactChatRequestVO request, List<ReactTraceStepVO> trace,
                                    String rollingContext, ModelRouter.ModelRouteDecision routeDecision,
                                    String tenantId) {
        String finalPrompt = buildFinalPrompt(request, trace, rollingContext);
        try {
            String answer = callModel("你是企业级AI助手，请结合轨迹和观察信息给出最终答案。",
                    finalPrompt, routeDecision, tenantId, "react_final");
            if (StringUtils.hasText(answer)) {
                return answer;
            }
        } catch (RuntimeException ignored) {
            // 下方的确定性兜底保证工作流响应仍可用。
        }
        return "当前未能生成最终答案，请稍后重试。";
    }

    private String buildFinalPrompt(ReactChatRequestVO request,
                                     List<ReactTraceStepVO> trace, String rollingContext) {
        return "用户问题:%n%s%n%nReAct轨迹:%n%s%n%n观察上下文:%n%s%n%n请输出最终中文答案，要求简洁、可执行、结构清晰。%n".formatted(request.getPrompt(), toJson(trace), emptyIfBlank(rollingContext));
    }

    private ReactTraceStepVO buildTraceStep(int step, ReasonDecision d, Object obs) {
        return ReactTraceStepVO.builder()
                .step(step).thought(d.thought()).action(d.action())
                .actionInput(d.actionInput()).observation(obs).build();
    }

    private ReactChatResponseVO success(String chatId, String answer,
                                         List<ReactTraceStepVO> trace,
                                         ModelRouter.ModelRouteDecision routeDecision,
                                         String taskId) {
        List<String> citations = extractTraceStrings(trace, "citations");
        List<String> evidence = extractTraceStrings(trace, "evidence");
        return ReactChatResponseVO.builder()
                .ok(1).msg("ok").chatId(chatId)
                .answer(attachCitationFooter(answer, citations))
                .citations(citations).evidence(evidence)
                .routeProfile(routeDecision == null ? "" : routeDecision.profile())
                .routeReason(routeDecision == null ? "" : routeDecision.reason())
                .routeCostTier(routeDecision == null ? "" : routeDecision.costTier())
                .experimentKey(routeDecision == null ? "" : routeDecision.experimentKey())
                .experimentVariant(routeDecision == null ? "" : routeDecision.experimentVariant())
                .experimentBucket(routeDecision == null ? null : routeDecision.experimentBucket())
                .trace(trace)
                .build();
    }

    private ModelRouter.ModelRouteDecision resolveRouteDecision(String profile, String endpoint,
                                                                 String subjectKey, String tenantId) {
        return modelRouter.resolve(profile, endpoint, tenantId, subjectKey);
    }

    private String callModel(String system, String user, ModelRouter.ModelRouteDecision decision,
                              String tenantId, String endpointTag) {
        long inputTokens = tenantCostService.estimateTokens(system)
                + tenantCostService.estimateTokens(user);
        tenantCostService.assertBudget(tenantId, decision.costTier(), inputTokens, 600);
        String output = agentChatClient.prompt()
                .options(ChatOptions.builder().model(decision.model()).build())
                // 记忆注入：认证主体为 user 键（匿名时空键不注入），advisor 组装期插"已知记忆"system 消息
                .advisors(a -> a.param(MemoryInjectionAdvisor.MEMORY_TENANT_KEY, tenantId).param(MemoryInjectionAdvisor.MEMORY_USER_KEY, UserContext.currentUserId("")))
                .system(system).user(user).call().content();
        long outputTokens = tenantCostService.estimateTokens(output);
        tenantCostService.recordUsage(tenantId, decision.costTier(), inputTokens, outputTokens, endpointTag);
        return output;
    }

    private Flux<String> callModelStream(String system, String user,
                                          ModelRouter.ModelRouteDecision decision,
                                          String tenantId, String endpointTag) {
        long inputTokens = tenantCostService.estimateTokens(system)
                + tenantCostService.estimateTokens(user);
        tenantCostService.assertBudget(tenantId, decision.costTier(), inputTokens, 600);
        StringBuilder collector = new StringBuilder();
        AtomicBoolean recorded = new AtomicBoolean(false);
        return agentChatClient.prompt()
                .options(ChatOptions.builder().model(decision.model()).build())
                .advisors(a -> a.param(MemoryInjectionAdvisor.MEMORY_TENANT_KEY, tenantId).param(MemoryInjectionAdvisor.MEMORY_USER_KEY, UserContext.currentUserId("")))
                .system(system).user(user).stream().content()
                .doOnNext(collector::append)
                .doFinally(sig -> {
                    if (!recorded.compareAndSet(false, true)) return;
                    long out = tenantCostService.estimateTokens(collector.toString());
                    tenantCostService.recordUsage(tenantId, decision.costTier(), inputTokens, out, endpointTag);
                });
    }

    private WorkflowState mapToWorkflowState(int step) { // 轮次→状态映射：1→SEARCHING、2→RETRIEVING、3→JUDGING、4→REFLECTING、其余→WRITING
        return switch (step) {
            case 1 -> WorkflowState.SEARCHING;
            case 2 -> WorkflowState.RETRIEVING;
            case 3 -> WorkflowState.JUDGING;
            case 4 -> WorkflowState.REFLECTING;
            default -> WorkflowState.WRITING;
        };
    }

    private ReasonDecision parseDecision(String raw) {
        String json = extractJson(raw);
        if (!StringUtils.hasText(json)) {
            return new ReasonDecision("规划输出解析失败，直接结束作答。", "finish",
                    Collections.emptyMap(), emptyIfBlank(raw), List.of(), List.of());
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String action = ReactPlannerFallbacks.normalizeAction(node.path("action").asText("finish"));
            Map<String, Object> input = objectMapper.convertValue(
                    node.path("action_input"), new TypeReference<Map<String, Object>>() {});
            if (input == null) {
                input = Collections.emptyMap();
            }
            if (!plannerActionCatalog.isPlannerAction(action)) {
                action = "finish";
            }
            return new ReasonDecision(node.path("thought").asText(""),
                    action, input, node.path("answer").asText(""), List.of(), List.of());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new ReasonDecision("Parse failed.", "finish",
                    Collections.emptyMap(), emptyIfBlank(raw), List.of(), List.of());
        }
    }

    private String extractJson(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start < 0 || end <= start) ? "" : raw.substring(start, end + 1);
    }

    private List<String> extractTraceStrings(List<ReactTraceStepVO> trace, String key) {
        if (trace == null || trace.isEmpty()) return List.of();
        Set<String> values = new LinkedHashSet<>();
        for (ReactTraceStepVO step : trace) {
            if (!(step.getObservation() instanceof Map<?, ?> obs)) continue;
            Object raw = obs.get(key);
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    String s = emptyIfBlank(String.valueOf(item));
                    if (StringUtils.hasText(s)) values.add(s);
                }
            }
        }
        return List.copyOf(values);
    }

    private String attachCitationFooter(String answer, List<String> citations) {
        if (citations == null || citations.isEmpty()) return emptyIfBlank(answer);
        if (emptyIfBlank(answer).contains("引用来源")) return answer;
        StringBuilder sb = new StringBuilder(emptyIfBlank(answer).trim());
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("引用来源:\n");
        for (int i = 0; i < citations.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(citations.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatSse(String event, String data) { return "event: " + event + "\ndata: " + data + "\n\n"; }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "{\"message\":\"serialization_failed\"}"; }
    }

    private String appendContext(String origin, String action, Object observation) {
        StringBuilder sb = new StringBuilder(emptyIfBlank(origin));
        if (sb.length() > 0) sb.append("\n");
        sb.append("action=").append(action).append(", observation=").append(toJson(observation));
        return sb.toString();
    }

    private void recordStreamMetrics(long startedNs, Long firstTokenMs, String outcome) {
        long total = elapsedMs(startedNs);
        Timer.builder("react.stream.total.latency").tag("outcome", outcome)
                .publishPercentileHistogram().register(meterRegistry)
                .record(total, TimeUnit.MILLISECONDS);
        if (firstTokenMs != null) {
            Timer.builder("react.stream.first_token.latency").tag("outcome", outcome)
                    .publishPercentileHistogram().register(meterRegistry)
                    .record(firstTokenMs, TimeUnit.MILLISECONDS);
        }
        Counter.builder("react.stream.requests").tag("outcome", outcome)
                .register(meterRegistry).increment();
    }

    private long elapsedMs(long startedNs) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs); }
    private String emptyIfBlank(String v) { return StringUtils.hasText(v) ? v : ""; }
    private String currentTenantId() { return TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE)); }
    private void validateRequest(ReactChatRequestVO r) {
        if (r == null || !StringUtils.hasText(r.getPrompt())) throw new IllegalArgumentException("问题内容不能为空");
        if (!StringUtils.hasText(r.getChatId())) throw new IllegalArgumentException("会话 ID 不能为空");
    }

    private record ReasonDecision(String thought, String action,
                                   Map<String, Object> actionInput, String answer,
                                   List<String> citations, List<String> evidence) {}
}
