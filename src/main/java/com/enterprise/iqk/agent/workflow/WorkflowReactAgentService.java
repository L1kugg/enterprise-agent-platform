package com.enterprise.iqk.agent.workflow;

import com.enterprise.iqk.agent.harness.AgentAction;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.agent.harness.PlannerActionCatalog;
import com.enterprise.iqk.config.properties.AgentWorkflowProperties;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.security.UserContext;
import com.enterprise.iqk.service.ReactResponseFormatter;
import com.enterprise.iqk.service.agent.AgentChatRuntime;
import com.enterprise.iqk.service.agent.AgentPromptMerger;
import com.enterprise.iqk.util.AnswerStreamSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** ReAct 执行层 Agent：留痕驱动 reason→动作→观测循环，步数可配、状态由实际动作推导，全程可回放。
 *  智能体路径（请求带 agentId）与 ReactAgentService 同构：运行态装载与安全拦截在 AgentChatRuntime.prepare
 *  （置于 startTask 之前，拦截命中不产生任务记录）→ memoryTurns 裁剪记忆 → 提示词合并 → 绑定模型优先/
 *  按策略回退（AgentChatRuntime）→ 调用日志。agentId 为空时走原路径，行为不变。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowReactAgentService {

    /** 平台级规划器系统提示词基座（智能体角色设定由 AgentPromptMerger 附加） */
    private static final String PLANNER_BASE_SYSTEM =
            "你是严格的 JSON ReAct 规划器，只输出合法 JSON，thought 与 answer 用简体中文。";
    /** 平台级成稿系统提示词基座 */
    private static final String FINAL_BASE_SYSTEM =
            "你是企业级AI助手，请结合轨迹和观察信息给出最终答案。答案正文不要罗列「引用来源/参考文献」清单，来源由界面单独展示。";

    private final AgentWorkflowEngine workflowEngine;
    private final AgentHarnessService agentHarnessService;
    private final PlannerActionCatalog plannerActionCatalog;
    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final AgentWorkflowProperties agentWorkflowProperties;
    private final MemoryService memoryService;
    private final ReactResponseFormatter responseFormatter;
    /** 智能体角色设定合并器：平台基座提示词 + 角色设定（约束不覆盖平台要求）。 */
    private final AgentPromptMerger promptMerger;
    /** 智能体运行时共享组件：装载/拦截/绑定模型调用/回填/调用日志。 */
    private final AgentChatRuntime agentRuntime;
    /** 规划提示词组装（含 per-agent 绑定工具目录，R7.3）。 */
    private final WorkflowPlannerPrompts plannerPrompts;

    /** 同步 ReAct：跑完循环后一次性返回答案+轨迹+引用；异常置任务 FAILED 后上抛。 */
    public ReactChatResponseVO chat(ReactChatRequestVO request) {
        validateRequest(request);
        String tenantId = currentTenantId();
        long startedNs = System.nanoTime();

        // 智能体路径：配置装载与安全拦截都置于 startTask 之前——拦截/配置错误不产生孤儿任务
        AgentChatRuntime.RunState runState = agentRuntime.prepare(request, tenantId);
        if (runState != null && runState.blocked()) {
            return agentRuntime.blockedResponse(request, tenantId, runState, elapsedMs(startedNs));
        }

        AgentTaskRecord task = workflowEngine.startTask(
                tenantId, "REACT", request.getPrompt(),
                request.getModelProfile(), request.getChatId(), null);

        ModelRouter.ModelRouteDecision routeDecision = modelRouter.resolve(
                request.getModelProfile(), "react", request.getChatId(), tenantId);

        List<ReactTraceStepVO> trace = new ArrayList<>();
        String rollingContext = "";
        String memoryUserKey = UserContext.currentUserId(request.getChatId());
        MemoryService.MemoryContextSnapshot memorySnapshot =
                recallMemory(tenantId, memoryUserKey, request.getPrompt(), runState);
        String knownMemory = memoryBlock(memorySnapshot);

        try {
            WorkflowState currentState = WorkflowState.PLANNING;
            for (int stepNum = 1; stepNum <= maxSteps(); stepNum++) {
                AgentStepRecord stepRecord = workflowEngine.startStep(
                        task.getTaskId(), "planner", stepNum,
                        Map.of("prompt", request.getPrompt(), "rollingContext", rollingContext));

                long stepStartNs = System.nanoTime();
                ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId,
                        knownMemory, runState);

                if ("finish".equals(decision.action())) {
                    String answer = StringUtils.hasText(decision.answer()) ? decision.answer()
                            : summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId, runState);
                    Map<String, Object> obs = new LinkedHashMap<>();
                    obs.put("status", "completed");
                    obs.put("citations", decision.citations() != null ? decision.citations() : List.of());
                    obs.put("evidence", decision.evidence() != null ? decision.evidence() : List.of());

                    trace.add(buildTraceStep(stepNum, decision, obs, elapsedMs(stepStartNs)));
                    workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED",
                            Map.of("answer", answer), obs, decision.thought(), "finish",
                            decision.actionInput(), 0, 0, elapsedMs(stepStartNs), null);
                    workflowEngine.transitionStatus(task.getTaskId(), currentState, WorkflowState.WRITING);
                    workflowEngine.completeTask(task.getTaskId(), WorkflowState.DONE, answer);
                    workflowEngine.recordTaskMetrics("REACT", "DONE", elapsedMs(startedNs));
                    ReactChatResponseVO response = withMemory(
                            responseFormatter.success(request.getChatId(), answer, trace, routeDecision, false),
                            memorySnapshot);
                    return agentRuntime.enrichAndRecord(request, tenantId, runState, response, elapsedMs(startedNs));
                }

                WorkflowState nextState = WorkflowState.forAction(decision.action());
                workflowEngine.transitionStatus(task.getTaskId(), currentState, nextState);
                currentState = nextState;

                Object observation = executeAction(request, runState, decision.action(), decision.actionInput(), tenantId,
                        task.getTaskId(), stepRecord.getStepId());
                trace.add(buildTraceStep(stepNum, decision, observation, elapsedMs(stepStartNs)));
                workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED", null, observation,
                        decision.thought(), decision.action(), decision.actionInput(),
                        0, 0, elapsedMs(stepStartNs), null);

                rollingContext = responseFormatter.appendContext(rollingContext, decision.action(), observation);
            }

            String answer = summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId, runState);
            workflowEngine.transitionStatus(task.getTaskId(), currentState, WorkflowState.WRITING);
            workflowEngine.completeTask(task.getTaskId(), WorkflowState.DONE, answer);
            workflowEngine.recordTaskMetrics("REACT", "DONE", elapsedMs(startedNs));
            ReactChatResponseVO response = withMemory(
                    responseFormatter.success(request.getChatId(), answer, trace, routeDecision, false),
                    memorySnapshot);
            return agentRuntime.enrichAndRecord(request, tenantId, runState, response, elapsedMs(startedNs));

        } catch (RuntimeException e) {
            agentRuntime.recordError(request, tenantId, runState, agentRuntime.safeReason(e), elapsedMs(startedNs));
            workflowEngine.failTask(task.getTaskId(), e.getMessage());
            workflowEngine.recordTaskMetrics("REACT", "FAILED", elapsedMs(startedNs));
            throw e;
        }
    }

    /** 真流式 ReAct（SSE）：每步完成即发 trace 帧，再流式发 token，最后发 done；断连取消时任务守卫式收尾防孤儿。 */
    public Flux<String> stream(ReactChatRequestVO request) {
        long startedNs = System.nanoTime();
        AtomicReference<Long> firstTokenMs = new AtomicReference<>(null);
        AtomicReference<String> outcomeRef = new AtomicReference<>("error");
        AtomicReference<String> taskIdRef = new AtomicReference<>("");
        // defer 组装在请求线程执行；外层 onErrorResume 拿不到 defer 内局部量，用引用回传
        AtomicReference<AgentChatRuntime.RunState> runStateRef = new AtomicReference<>(null);
        AtomicReference<String> tenantIdRef = new AtomicReference<>("");

        return Flux.defer(() -> {
                    validateRequest(request);
                    String tenantId = currentTenantId();
                    tenantIdRef.set(tenantId);

                    // 智能体路径：拦截命中直接转 error 事件（code=SAFETY_BLOCKED），startTask 之前短路
                    AgentChatRuntime.RunState runState = agentRuntime.prepare(request, tenantId);
                    if (runState != null && runState.blocked()) {
                        agentRuntime.recordBlocked(request, tenantId, runState, elapsedMs(startedNs));
                        outcomeRef.set("blocked");
                        return Flux.just(responseFormatter.formatSse("error", responseFormatter.toJson(Map.of(
                                "message", runState.blockMessage(), "code", "SAFETY_BLOCKED"))));
                    }
                    runStateRef.set(runState);

                    AgentTaskRecord task = workflowEngine.startTask(
                            tenantId, "REACT_STREAM", request.getPrompt(),
                            request.getModelProfile(), request.getChatId(), null);
                    taskIdRef.set(task.getTaskId());

                    String knownMemory = memoryBlock(recallMemory(tenantId,
                            UserContext.currentUserId(request.getChatId()), request.getPrompt(), runState));
                    StreamState state = new StreamState(request, task, modelRouter.resolve(
                            request.getModelProfile(), "react", request.getChatId(), tenantId),
                            tenantId, new ArrayList<>(), new AtomicReference<>(""), new AtomicReference<>(""),
                            firstTokenMs, outcomeRef, startedNs, new AtomicReference<>(WorkflowState.PLANNING),
                            knownMemory, runState);
                    return stepFlux(state, 1).concatWith(finalAnswerFlux(state));
                })
                .onErrorResume(ex -> {
                    String message = StringUtils.hasText(ex.getMessage())
                            ? ex.getMessage() : "回答生成失败，请稍后重试";
                    agentRuntime.recordError(request, tenantIdRef.get(), runStateRef.get(),
                            agentRuntime.safeReason(ex), elapsedMs(startedNs));
                    // 将任务标记为 FAILED，避免失败的流式请求把工作流记录
                    // 遗留在非终态成为孤儿。
                    String failedTaskId = taskIdRef.get();
                    if (StringUtils.hasText(failedTaskId)) {
                        workflowEngine.failTask(failedTaskId, message);
                        workflowEngine.recordTaskMetrics("REACT_STREAM", "FAILED", elapsedMs(startedNs));
                    }
                    return Flux.just(responseFormatter.formatSse("error",
                            responseFormatter.toJson(Map.of("message", message))));
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

    // ── 规划与成稿 ─────────────────────────────────────────────────────────

    /** 递归单步流：每完成一轮 ReAct（reason→动作执行）立即向下游发一条 trace 帧，循环不再整体阻塞在 defer 里跑完才发首帧。 */
    private Flux<String> stepFlux(StreamState state, int stepNum) {
        return Flux.defer(() -> {
            if (stepNum > maxSteps()) {
                return Flux.<String>empty(); // 轮次用尽，交由 finalAnswerFlux 强制总结成稿
            }
            AgentStepRecord stepRecord = workflowEngine.startStep(
                    state.task().getTaskId(), "planner", stepNum,
                    Map.of("prompt", state.request().getPrompt()));

            long stepStartNs = System.nanoTime();
            ReasonDecision decision = reason(state.request(), state.rollingContext().get(),
                    state.trace(), state.routeDecision(), state.tenantId(), state.knownMemory(),
                    state.runState());

            if ("finish".equals(decision.action())) {
                Map<String, Object> obs = new LinkedHashMap<>();
                obs.put("status", "completed");
                ReactTraceStepVO stepVo = buildTraceStep(stepNum, decision, obs, elapsedMs(stepStartNs));
                state.trace().add(stepVo);
                state.directAnswer().set(emptyIfBlank(decision.answer()));
                workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED",
                        Map.of("answer", state.directAnswer().get()), obs, decision.thought(),
                        "finish", decision.actionInput(), 0, 0, elapsedMs(stepStartNs), null);
                transitionWorkflowState(state, WorkflowState.WRITING);
                return Flux.just(responseFormatter.formatSse("trace", responseFormatter.toJson(stepVo)));
            }

            transitionWorkflowState(state, WorkflowState.forAction(decision.action()));
            Object observation = executeAction(state.request(), state.runState(), decision.action(), decision.actionInput(),
                    state.tenantId(), state.task().getTaskId(), stepRecord.getStepId());
            ReactTraceStepVO stepVo = buildTraceStep(stepNum, decision, observation, elapsedMs(stepStartNs));
            state.trace().add(stepVo);
            workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED", null, observation,
                    decision.thought(), decision.action(), decision.actionInput(),
                    0, 0, elapsedMs(stepStartNs), null);
            state.rollingContext().set(
                    responseFormatter.appendContext(state.rollingContext().get(), decision.action(), observation));
            return Flux.just(responseFormatter.formatSse("trace", responseFormatter.toJson(stepVo)))
                    .concatWith(stepFlux(state, stepNum + 1));
        });
    }

    /** 成稿流：finish 直答则原样发 token，否则流式调用模型；末尾发 done 帧并置任务 DONE。 */
    private Flux<String> finalAnswerFlux(StreamState state) {
        StringBuilder answerBuilder = new StringBuilder();
        return Flux.<String>defer(() -> {
                    String direct = state.directAnswer().get();
                    String finalSystem = promptMerger.finalSystem(FINAL_BASE_SYSTEM,
                            state.runState() == null ? null : state.runState().config());
                    return StringUtils.hasText(direct) ? AnswerStreamSupport.chunked(direct)
                            : agentRuntime.callStream(finalSystem,
                            buildFinalPrompt(state.request(), state.trace(), state.rollingContext().get()),
                            state.routeDecision(), state.tenantId(), "react_final", "workflow", state.runState());
                })
                .map(token -> {
                    if (state.firstTokenMs().get() == null) {
                        state.firstTokenMs().set(elapsedMs(state.startedNs()));
                    }
                    answerBuilder.append(token);
                    return responseFormatter.formatSse("token", responseFormatter.toJson(Map.of("token", token)));
                })
                .concatWith(Flux.defer(() -> {
                    if (state.firstTokenMs().get() == null) {
                        state.firstTokenMs().set(elapsedMs(state.startedNs())); // 零 token 兜底计时
                    }
                    String answer = answerBuilder.toString();
                    ReactChatResponseVO response = responseFormatter.success(state.request().getChatId(),
                            answer, state.trace(), state.routeDecision(), false);
                    workflowEngine.completeTask(state.task().getTaskId(), WorkflowState.DONE, answer);
                    agentRuntime.enrichAndRecord(state.request(), state.tenantId(), state.runState(), response,
                            elapsedMs(state.startedNs()));
                    state.outcomeRef().set("success");
                    return Flux.just(responseFormatter.formatSse("done", responseFormatter.toJson(response)));
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
                               long startedNs,
                               AtomicReference<WorkflowState> workflowState,
                               String knownMemory,
                               AgentChatRuntime.RunState runState) {
    }

    /** 单步规划：让模型从白名单选动作输出 JSON 决策；调用或解析失败回退规则兜底。 */
    private ReasonDecision reason(ReactChatRequestVO request,
                                  String rollingContext,
                                  List<ReactTraceStepVO> trace,
                                  ModelRouter.ModelRouteDecision routeDecision,
                                  String tenantId,
                                  String knownMemory,
                                  AgentChatRuntime.RunState runState) {
        String planningPrompt = plannerPrompts.planningPrompt(
                request, rollingContext, trace, knownMemory, tenantId, runState);

        try {
            String raw = agentRuntime.call(
                    promptMerger.plannerSystem(PLANNER_BASE_SYSTEM, runState == null ? null : runState.config()),
                    planningPrompt, routeDecision, tenantId, "react_planner", "workflow", runState);
            return parseDecision(raw);
        } catch (RuntimeException ex) {
            ReactPlannerFallbacks.ReasonFallback fallback = ReactPlannerFallbacks.fallbackDecision(request.getPrompt());
            return new ReasonDecision(fallback.thought(), fallback.action(), fallback.actionInput(),
                    fallback.answer(), fallback.citations(), fallback.evidence());
        }
    }

    private Map<String, Object> executeAction(ReactChatRequestVO request, AgentChatRuntime.RunState runState,
                                              String action, Map<String, Object> actionInput, String tenantId,
                                              String taskId, String stepId) {
        return agentHarnessService.execute(new AgentAction(action, actionInput, request.getPrompt(),
                tenantId, request.getChatId(), request.getModelProfile(), taskId, stepId,
                runState == null || runState.config().agentId() == null
                        ? 0L : runState.config().agentId(), false)).toMap();
    }

    /** 汇总最终答案：用轨迹 + 观察上下文再调一次模型；失败给兜底文案。 */
    private String summarizeAnswer(ReactChatRequestVO request, List<ReactTraceStepVO> trace,
                                    String rollingContext, ModelRouter.ModelRouteDecision routeDecision,
                                    String tenantId, AgentChatRuntime.RunState runState) {
        String finalSystem = promptMerger.finalSystem(FINAL_BASE_SYSTEM,
                runState == null ? null : runState.config());
        String finalPrompt = buildFinalPrompt(request, trace, rollingContext);
        try {
            String answer = agentRuntime.call(finalSystem, finalPrompt, routeDecision, tenantId,
                    "react_final", "workflow", runState);
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
        return "用户问题:%n%s%n%nReAct轨迹:%n%s%n%n观察上下文:%n%s%n%n请输出最终中文答案，要求简洁、可执行、结构清晰。%n"
                .formatted(request.getPrompt(), responseFormatter.traceSummary(trace), emptyIfBlank(rollingContext));
    }

    /** 拼单步轨迹 VO：elapsedMs 用 stepStartNs 掐表换算，覆盖本步规划 + 动作执行全程。 */
    private ReactTraceStepVO buildTraceStep(int step, ReasonDecision d, Object obs, long elapsedMs) {
        return ReactTraceStepVO.builder().step(step).thought(d.thought()).action(d.action())
                .actionInput(d.actionInput()).observation(obs).elapsedMs(elapsedMs).build();
    }

    /** 流式路径的状态推进：CAS 更新本地引用并留痕，避免并发帧重复回退状态 */
    private void transitionWorkflowState(StreamState state, WorkflowState target) {
        WorkflowState current = state.workflowState().getAndSet(target);
        workflowEngine.transitionStatus(state.task().getTaskId(), current, target);
    }

    /** 记忆召回（读侧）：未绑定智能体走全量注入；智能体路径 short 层按 memoryTurns 裁剪。
     *  失败返回 null 按无记忆降级，绝不中断工作流主链路。 */
    private MemoryService.MemoryContextSnapshot recallMemory(String tenantId, String userKey,
                                                             String query, AgentChatRuntime.RunState runState) {
        try {
            if (runState == null) {
                return memoryService.buildContext(tenantId, userKey, query);
            }
            return memoryService.buildContext(tenantId, userKey, query, true, runState.config().memoryTurns());
        } catch (Exception ex) {
            log.warn("记忆召回失败（不影响工作流引擎）: user={}, reason={}", userKey, ex.toString());
            return null;
        }
    }

    /** 记忆快照转 prompt 段；空快照用 "(none)" 占位，保持提示词结构稳定。 */
    private String memoryBlock(MemoryService.MemoryContextSnapshot snapshot) {
        return snapshot != null && StringUtils.hasText(snapshot.contextText())
                ? snapshot.contextText().trim() : "(none)";
    }

    /** 回填 memoryUsed 观测标签（快照为空时给空列表）。 */
    private ReactChatResponseVO withMemory(ReactChatResponseVO r, MemoryService.MemoryContextSnapshot s) {
        r.setMemoryUsed(s == null ? List.of() : s.usedLabels());
        return r;
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

    private int maxSteps() {
        return Math.max(1, agentWorkflowProperties.getReactMaxSteps());
    }

    /** 纳秒起点换算毫秒耗时 */
    private long elapsedMs(long startedNs) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs); }

    /** 记录流式链路的总耗时 / 首 token 延迟 / 请求计数（按 outcome 打标）。 */
    private void recordStreamMetrics(long startedNs, Long firstTokenMs, String outcome) {
        Timer.builder("react.stream.total.latency").tag("outcome", outcome)
                .publishPercentileHistogram().register(meterRegistry)
                .record(elapsedMs(startedNs), TimeUnit.MILLISECONDS);
        if (firstTokenMs != null) {
            Timer.builder("react.stream.first_token.latency").tag("outcome", outcome)
                    .publishPercentileHistogram().register(meterRegistry)
                    .record(firstTokenMs, TimeUnit.MILLISECONDS);
        }
        Counter.builder("react.stream.requests").tag("outcome", outcome)
                .register(meterRegistry).increment();
    }

    /** null/空白统一返回空串。 */
    private String emptyIfBlank(String v) { return StringUtils.hasText(v) ? v : ""; }

    /** 从 MDC 取归一化租户 ID。 */
    private String currentTenantId() { return TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE)); }

    /** 校验 prompt 与 chatId 必填，缺失抛 IllegalArgumentException。 */
    private void validateRequest(ReactChatRequestVO r) {
        if (r == null || !StringUtils.hasText(r.getPrompt())) throw new IllegalArgumentException("问题内容不能为空");
        if (!StringUtils.hasText(r.getChatId())) throw new IllegalArgumentException("会话 ID 不能为空");
    }

    private record ReasonDecision(String thought, String action,
                                   Map<String, Object> actionInput, String answer,
                                   List<String> citations, List<String> evidence) {}
}
