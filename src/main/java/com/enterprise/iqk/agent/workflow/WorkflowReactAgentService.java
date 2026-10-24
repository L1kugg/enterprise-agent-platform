package com.enterprise.iqk.agent.workflow;

import com.enterprise.iqk.config.properties.AgentWorkflowProperties;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.service.ReactDecisionParser;
import com.enterprise.iqk.service.ReactDecisionParser.ReasonDecision;
import com.enterprise.iqk.service.ReactResponseFormatter;
import com.enterprise.iqk.service.agent.AgentChatRuntime;
import com.enterprise.iqk.service.agent.AgentPromptMerger;
import com.enterprise.iqk.service.agent.ReActPlannerPrompts;
import com.enterprise.iqk.service.agent.ReActSupport;
import com.enterprise.iqk.util.AnswerStreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** ReAct 执行层 Agent：留痕驱动 reason→动作→观测循环，步数可配、状态由实际动作推导，全程可回放。
 *  与对话链路（ReactAgentService）共用提示词（ReActPlannerPrompts）、决策解析（ReactDecisionParser）
 *  与执行支撑（ReActSupport）；本类只保留留痕链路的编排差异：任务/步骤持久化、状态机迁移与
 *  断连收尾。智能体路径（请求带 agentId）与 ReactAgentService 同构：运行态装载与安全拦截在
 *  AgentChatRuntime.prepare（置于 startTask 之前，拦截命中不产生任务记录）→ memoryTurns 裁剪记忆
 *  → 提示词合并 → 绑定模型优先/按策略回退（AgentChatRuntime）→ 调用日志。agentId 为空时走原路径。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowReactAgentService {

    private final AgentWorkflowEngine workflowEngine;
    private final AgentWorkflowProperties agentWorkflowProperties;
    private final ModelRouter modelRouter;
    private final ReactDecisionParser decisionParser;
    private final ReactResponseFormatter responseFormatter;
    /** 智能体角色设定合并器：平台基座提示词 + 角色设定（约束不覆盖平台要求）。 */
    private final AgentPromptMerger promptMerger;
    /** 智能体运行时共享组件：装载/拦截/绑定模型调用/回填/调用日志。 */
    private final AgentChatRuntime agentRuntime;
    /** 两条 ReAct 链路共用的提示词组装。 */
    private final ReActPlannerPrompts plannerPrompts;
    /** 两条 ReAct 链路共用的执行支撑（记忆/动作/轨迹/指标）。 */
    private final ReActSupport reactSupport;

    /** 同步 ReAct：跑完循环后一次性返回答案+轨迹+引用；异常置任务 FAILED 后上抛。 */
    public ReactChatResponseVO chat(ReactChatRequestVO request) {
        reactSupport.validateRequest(request);
        String tenantId = reactSupport.currentTenantId();
        long startedNs = System.nanoTime();

        // 智能体路径：配置装载与安全拦截都置于 startTask 之前——拦截/配置错误不产生孤儿任务
        AgentChatRuntime.RunState runState = agentRuntime.prepare(request, tenantId);
        if (runState != null && runState.blocked()) {
            return agentRuntime.blockedResponse(request, tenantId, runState, reactSupport.elapsedMs(startedNs));
        }

        AgentTaskRecord task = workflowEngine.startTask(
                tenantId, "REACT", request.getPrompt(),
                request.getModelProfile(), request.getChatId(), null);

        ModelRouter.ModelRouteDecision routeDecision = modelRouter.resolve(
                request.getModelProfile(), "react", request.getChatId(), tenantId);

        List<ReactTraceStepVO> trace = new ArrayList<>();
        String rollingContext = "";
        String memoryUserKey = reactSupport.memoryUserKey(request.getChatId());
        MemoryService.MemoryContextSnapshot memorySnapshot =
                reactSupport.recallMemory(tenantId, memoryUserKey, request.getPrompt(), runState);
        String knownMemory = reactSupport.memoryBlock(memorySnapshot);
        boolean usedFallback = false;

        try {
            WorkflowState currentState = WorkflowState.PLANNING;
            for (int stepNum = 1; stepNum <= maxSteps(runState); stepNum++) {
                AgentStepRecord stepRecord = workflowEngine.startStep(
                        task.getTaskId(), "planner", stepNum,
                        Map.of("prompt", request.getPrompt(), "rollingContext", rollingContext));

                long stepStartNs = System.nanoTime();
                ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId,
                        knownMemory, runState);
                usedFallback = usedFallback || decision.fallback();

                if ("finish".equals(decision.action())) {
                    String answer = StringUtils.hasText(decision.answer()) ? decision.answer()
                            : summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId,
                                    knownMemory, runState, null);
                    Map<String, Object> obs = new LinkedHashMap<>();
                    obs.put("status", "completed");
                    obs.put("citations", decision.citations() != null ? decision.citations() : List.of());
                    obs.put("evidence", decision.evidence() != null ? decision.evidence() : List.of());

                    trace.add(reactSupport.buildTraceStep(stepNum, decision, obs,
                            reactSupport.elapsedMs(stepStartNs)));
                    workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED",
                            Map.of("answer", answer), obs, decision.thought(), "finish",
                            decision.actionInput(), 0, 0, reactSupport.elapsedMs(stepStartNs), null);
                    workflowEngine.transitionStatus(task.getTaskId(), currentState, WorkflowState.WRITING);
                    workflowEngine.completeTask(task.getTaskId(), WorkflowState.DONE, answer);
                    workflowEngine.recordTaskMetrics("REACT", "DONE", reactSupport.elapsedMs(startedNs));
                    ReactChatResponseVO response = responseFormatter.success(request.getChatId(), answer,
                            trace, routeDecision, usedFallback);
                    response.setMemoryUsed(reactSupport.memoryUsedLabels(memorySnapshot));
                    return agentRuntime.enrichAndRecord(request, tenantId, runState, response,
                            reactSupport.elapsedMs(startedNs));
                }

                WorkflowState nextState = WorkflowState.forAction(decision.action());
                workflowEngine.transitionStatus(task.getTaskId(), currentState, nextState);
                currentState = nextState;

                Object observation = reactSupport.executeAction(request, runState, decision.action(),
                        decision.actionInput(), tenantId, task.getTaskId(), stepRecord.getStepId());
                trace.add(reactSupport.buildTraceStep(stepNum, decision, observation,
                        reactSupport.elapsedMs(stepStartNs)));
                workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED", null, observation,
                        decision.thought(), decision.action(), decision.actionInput(),
                        0, 0, reactSupport.elapsedMs(stepStartNs), null);

                rollingContext = responseFormatter.appendContext(rollingContext, decision.action(), observation);
            }

            String answer = summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId,
                    knownMemory, runState, ReActPlannerPrompts.STEPS_EXHAUSTED_NOTE.formatted());
            workflowEngine.transitionStatus(task.getTaskId(), currentState, WorkflowState.WRITING);
            workflowEngine.completeTask(task.getTaskId(), WorkflowState.DONE, answer);
            workflowEngine.recordTaskMetrics("REACT", "DONE", reactSupport.elapsedMs(startedNs));
            ReactChatResponseVO response = responseFormatter.success(request.getChatId(), answer,
                    trace, routeDecision, usedFallback);
            response.setMemoryUsed(reactSupport.memoryUsedLabels(memorySnapshot));
            return agentRuntime.enrichAndRecord(request, tenantId, runState, response,
                    reactSupport.elapsedMs(startedNs));

        } catch (RuntimeException e) {
            agentRuntime.recordError(request, tenantId, runState, agentRuntime.safeReason(e),
                    reactSupport.elapsedMs(startedNs));
            workflowEngine.failTask(task.getTaskId(), e.getMessage());
            workflowEngine.recordTaskMetrics("REACT", "FAILED", reactSupport.elapsedMs(startedNs));
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
                    reactSupport.validateRequest(request);
                    String tenantId = reactSupport.currentTenantId();
                    tenantIdRef.set(tenantId);

                    // 智能体路径：拦截命中直接转 error 事件（code=SAFETY_BLOCKED），startTask 之前短路
                    AgentChatRuntime.RunState runState = agentRuntime.prepare(request, tenantId);
                    if (runState != null && runState.blocked()) {
                        agentRuntime.recordBlocked(request, tenantId, runState, reactSupport.elapsedMs(startedNs));
                        outcomeRef.set("blocked");
                        return Flux.just(responseFormatter.formatSse("error", responseFormatter.toJson(Map.of(
                                "message", runState.blockMessage(), "code", "SAFETY_BLOCKED"))));
                    }
                    runStateRef.set(runState);

                    AgentTaskRecord task = workflowEngine.startTask(
                            tenantId, "REACT_STREAM", request.getPrompt(),
                            request.getModelProfile(), request.getChatId(), null);
                    taskIdRef.set(task.getTaskId());

                    MemoryService.MemoryContextSnapshot memorySnapshot = reactSupport.recallMemory(tenantId,
                            reactSupport.memoryUserKey(request.getChatId()), request.getPrompt(), runState);
                    StreamState state = new StreamState(request, task, modelRouter.resolve(
                            request.getModelProfile(), "react", request.getChatId(), tenantId),
                            tenantId, new ArrayList<>(), new AtomicReference<>(""), new AtomicReference<>(""),
                            firstTokenMs, outcomeRef, startedNs, new AtomicReference<>(WorkflowState.PLANNING),
                            reactSupport.memoryBlock(memorySnapshot), memorySnapshot,
                            new AtomicReference<>(false), new AtomicReference<>(false), runState);
                    // 步骤链（阻塞的规划/工具执行）调度到 boundedElastic：订阅立即返回、
                    // SSE emitter 随之初始化，trace 帧随每步完成实时落线（否则帧会在订阅期间
                    // 同步跑完被桥接层聚合到一起），且不再占用 Tomcat 请求线程跑长任务。
                    // defer 内的 prepare/startTask 仍在本请求线程执行（依赖 MDC/SecurityContext）。
                    return stepFlux(state, 1).concatWith(finalAnswerFlux(state))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .onErrorResume(ex -> {
                    String message = StringUtils.hasText(ex.getMessage())
                            ? ex.getMessage() : "回答生成失败，请稍后重试";
                    agentRuntime.recordError(request, tenantIdRef.get(), runStateRef.get(),
                            agentRuntime.safeReason(ex), reactSupport.elapsedMs(startedNs));
                    // 将任务标记为 FAILED，避免失败的流式请求把工作流记录
                    // 遗留在非终态成为孤儿。
                    String failedTaskId = taskIdRef.get();
                    if (StringUtils.hasText(failedTaskId)) {
                        workflowEngine.failTask(failedTaskId, message);
                        workflowEngine.recordTaskMetrics("REACT_STREAM", "FAILED", reactSupport.elapsedMs(startedNs));
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
                    reactSupport.recordStreamMetrics(startedNs, firstTokenMs.get(), outcomeRef.get());
                });
    }

    // ── 流式循环 ───────────────────────────────────────────────────────────

    /** 递归单步流：每完成一轮 ReAct（reason→动作执行）立即向下游发一条 trace 帧，循环不再整体阻塞在 defer 里跑完才发首帧。 */
    private Flux<String> stepFlux(StreamState state, int stepNum) {
        return Flux.defer(() -> {
            if (stepNum > maxSteps(state.runState())) {
                state.stepsExhausted().set(true);
                return Flux.<String>empty(); // 轮次用尽，交由 finalAnswerFlux 强制总结成稿
            }
            AgentStepRecord stepRecord = workflowEngine.startStep(
                    state.task().getTaskId(), "planner", stepNum,
                    Map.of("prompt", state.request().getPrompt()));

            long stepStartNs = System.nanoTime();
            ReasonDecision decision = reason(state.request(), state.rollingContext().get(),
                    state.trace(), state.routeDecision(), state.tenantId(), state.knownMemory(),
                    state.runState());
            if (decision.fallback()) {
                state.usedFallback().set(true);
            }

            if ("finish".equals(decision.action())) {
                Map<String, Object> obs = new LinkedHashMap<>();
                obs.put("status", "completed");
                ReactTraceStepVO stepVo = reactSupport.buildTraceStep(stepNum, decision, obs,
                        reactSupport.elapsedMs(stepStartNs));
                state.trace().add(stepVo);
                state.directAnswer().set(ReActSupport.emptyIfBlank(decision.answer()));
                workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED",
                        Map.of("answer", state.directAnswer().get()), obs, decision.thought(),
                        "finish", decision.actionInput(), 0, 0, reactSupport.elapsedMs(stepStartNs), null);
                transitionWorkflowState(state, WorkflowState.WRITING);
                return Flux.just(responseFormatter.formatSse("trace", responseFormatter.toJson(stepVo)));
            }

            transitionWorkflowState(state, WorkflowState.forAction(decision.action()));
            Object observation = reactSupport.executeAction(state.request(), state.runState(), decision.action(),
                    decision.actionInput(), state.tenantId(), state.task().getTaskId(), stepRecord.getStepId());
            ReactTraceStepVO stepVo = reactSupport.buildTraceStep(stepNum, decision, observation,
                    reactSupport.elapsedMs(stepStartNs));
            state.trace().add(stepVo);
            workflowEngine.completeStep(stepRecord.getStepId(), "COMPLETED", null, observation,
                    decision.thought(), decision.action(), decision.actionInput(),
                    0, 0, reactSupport.elapsedMs(stepStartNs), null);
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
                    String finalSystem = promptMerger.finalSystem(ReActPlannerPrompts.FINAL_BASE_SYSTEM,
                            state.runState() == null ? null : state.runState().config());
                    String exhaustedNote = state.stepsExhausted().get()
                            ? ReActPlannerPrompts.STEPS_EXHAUSTED_NOTE.formatted() : "";
                    return StringUtils.hasText(direct) ? AnswerStreamSupport.chunked(direct)
                            : agentRuntime.callStream(finalSystem,
                            plannerPrompts.finalPrompt(state.request(), state.trace(),
                                    state.rollingContext().get(), state.knownMemory(), "") + exhaustedNote,
                            state.routeDecision(), state.tenantId(), "react_final", "workflow", state.runState());
                })
                .map(token -> {
                    if (state.firstTokenMs().get() == null) {
                        state.firstTokenMs().set(reactSupport.elapsedMs(state.startedNs()));
                    }
                    answerBuilder.append(token);
                    return responseFormatter.formatSse("token", responseFormatter.toJson(Map.of("token", token)));
                })
                .concatWith(Flux.defer(() -> {
                    if (state.firstTokenMs().get() == null) {
                        state.firstTokenMs().set(reactSupport.elapsedMs(state.startedNs())); // 零 token 兜底计时
                    }
                    String answer = answerBuilder.toString();
                    ReactChatResponseVO response = responseFormatter.success(state.request().getChatId(),
                            answer, state.trace(), state.routeDecision(), state.usedFallback().get());
                    response.setMemoryUsed(reactSupport.memoryUsedLabels(state.memorySnapshot()));
                    workflowEngine.completeTask(state.task().getTaskId(), WorkflowState.DONE, answer);
                    agentRuntime.enrichAndRecord(state.request(), state.tenantId(), state.runState(), response,
                            reactSupport.elapsedMs(state.startedNs()));
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
                               MemoryService.MemoryContextSnapshot memorySnapshot,
                               AtomicReference<Boolean> usedFallback,
                               AtomicReference<Boolean> stepsExhausted,
                               AgentChatRuntime.RunState runState) {
    }

    // ── 规划与成稿 ─────────────────────────────────────────────────────────

    /** 单步规划：让模型从白名单选动作输出 JSON 决策；调用或解析失败回退规则兜底。 */
    private ReasonDecision reason(ReactChatRequestVO request,
                                  String rollingContext,
                                  List<ReactTraceStepVO> trace,
                                  ModelRouter.ModelRouteDecision routeDecision,
                                  String tenantId,
                                  String knownMemory,
                                  AgentChatRuntime.RunState runState) {
        String planningPrompt = plannerPrompts.planningPrompt(
                request, rollingContext, trace, knownMemory, tenantId, runState, "");

        try {
            String raw = agentRuntime.call(
                    promptMerger.plannerSystem(ReActPlannerPrompts.PLANNER_BASE_SYSTEM,
                            runState == null ? null : runState.config()),
                    planningPrompt, routeDecision, tenantId, "react_planner", "workflow", runState);
            return decisionParser.parse(raw);
        } catch (RuntimeException ex) {
            return decisionParser.fallback(request.getPrompt());
        }
    }

    /** 汇总最终答案：用轨迹 + 观察上下文再调一次模型；失败给兜底文案。appendNote 追加在提示词尾（步数用尽说明）。 */
    private String summarizeAnswer(ReactChatRequestVO request, List<ReactTraceStepVO> trace,
                                    String rollingContext, ModelRouter.ModelRouteDecision routeDecision,
                                    String tenantId, String knownMemory, AgentChatRuntime.RunState runState,
                                    String appendNote) {
        String finalSystem = promptMerger.finalSystem(ReActPlannerPrompts.FINAL_BASE_SYSTEM,
                runState == null ? null : runState.config());
        String finalPrompt = plannerPrompts.finalPrompt(request, trace, rollingContext, knownMemory, "")
                + (appendNote == null ? "" : appendNote);
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

    /** 流式路径的状态推进：CAS 更新本地引用并留痕，避免并发帧重复回退状态 */
    private void transitionWorkflowState(StreamState state, WorkflowState target) {
        WorkflowState current = state.workflowState().getAndSet(target);
        workflowEngine.transitionStatus(state.task().getTaskId(), current, target);
    }

    /** 智能体配置优先的步数上限：config.maxSteps 有值用之，否则走 app.workflow.react-max-steps。 */
    private int maxSteps(AgentChatRuntime.RunState runState) {
        Integer configured = runState == null || runState.config() == null
                ? null : runState.config().maxSteps();
        return configured != null ? Math.max(1, configured) : Math.max(1, agentWorkflowProperties.getReactMaxSteps());
    }
}
