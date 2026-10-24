package com.enterprise.iqk.service;

import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.ChatTurnMemoryRecorder;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.service.ReactDecisionParser.ReasonDecision;
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
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ReAct 主循环服务（对话链路）：reason() 规划（动作白名单与提示词动作列表由 PlannerActionCatalog
 * 从注册表生成）→ executeAction() 经 AgentHarnessService 执行 → 观测滚动拼接回上下文，最多 MAX_STEPS 步。
 * finish 时优先取决策自带答案，否则 summarizeAnswer() 用轨迹汇总生成；
 * 规划调用/解析失败走 decisionParser.fallback() 规则兜底；
 * 模型调用统一经 AgentChatRuntime（绑定模型优先、按策略回退档位路由、预算与记账）。
 *
 * 与留痕链路（agent.workflow.WorkflowReactAgentService）共用提示词（ReActPlannerPrompts）、
 * 决策解析（ReactDecisionParser）与执行支撑（ReActSupport）；本类只保留对话链路的编排差异：
 * 无任务留痕、fallback 观测与 short 记忆写回。步数预算独立配置（本类 MAX_STEPS=4，
 * 留痕链路走 app.agent-workflow.react-max-steps）。
 *
 * 智能体路径（请求带 agentId，FR-R1-01..06）：运行态装载与会话前安全拦截在 AgentChatRuntime.prepare
 * （命中短路，不发生任何模型调用）→ 记忆按 memoryTurns 裁剪注入 → 规划/成稿系统提示词合并智能体
 * 角色设定 → 绑定模型服务客户端 → 响应回填 + 落 agent_invocation_log。
 * agentId 为空时走原路径，行为不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReactAgentService {
    /** ReAct 循环步数上限，超出后强制汇总收尾 */
    private static final int MAX_STEPS = 4;

    private final ModelRouter modelRouter;
    private final ReactDecisionParser decisionParser;
    private final ReactResponseFormatter responseFormatter;
    /** 对话轮次 short 记忆写入器：成稿后写回，让下一轮召回有内容（写侧闭环）。 */
    private final ChatTurnMemoryRecorder chatTurnMemoryRecorder;
    /** 智能体角色设定合并器：平台基座提示词 + 角色设定（约束不覆盖平台要求）。 */
    private final AgentPromptMerger promptMerger;
    /** 智能体运行时共享组件：装载/拦截/绑定模型调用/回填/调用日志。 */
    private final AgentChatRuntime agentRuntime;
    /** 两条 ReAct 链路共用的提示词组装。 */
    private final ReActPlannerPrompts plannerPrompts;
    /** 两条 ReAct 链路共用的执行支撑（记忆/动作/轨迹/指标）。 */
    private final ReActSupport reactSupport;

    /** 同步对话：跑完 ReAct 循环后一次性返回完整响应（含轨迹、引用与路由信息）。 */
    public ReactChatResponseVO chat(ReactChatRequestVO request) {
        reactSupport.validateRequest(request);
        long startedNs = System.nanoTime();
        String tenantId = reactSupport.currentTenantId();

        // 智能体路径：配置装载 + 会话前安全拦截（首个命中即短路，模型调用不发生）
        AgentChatRuntime.RunState runState = agentRuntime.prepare(request, tenantId);
        if (runState != null && runState.blocked()) {
            return agentRuntime.blockedResponse(request, tenantId, runState, reactSupport.elapsedMs(startedNs));
        }

        try {
            ModelRouter.ModelRouteDecision routeDecision = modelRouter.resolve(
                    request.getModelProfile(), "react", request.getChatId(), tenantId);

            List<ReactTraceStepVO> trace = new ArrayList<>();
            String rollingContext = "";
            boolean usedFallback = false;
            // 记忆召回（尽力而为，循环外一次）：planner 不挂 ChatMemory，
            // short/long/fact 全量注入 —— 会话前情、用户画像与租户可信事实
            // 都会影响动作选择与最终措辞。user 键取认证主体，匿名回落 chatId。
            // 智能体路径按 memoryTurns 裁剪 short 层（FR-R1-05）。
            String memoryUserKey = reactSupport.memoryUserKey(request.getChatId());
            MemoryService.MemoryContextSnapshot memorySnapshot = reactSupport.recallMemory(
                    tenantId, memoryUserKey, request.getPrompt(), runState);
            String knownMemory = reactSupport.memoryBlock(memorySnapshot);

            for (int step = 1; step <= MAX_STEPS; step++) {
                long stepStartNs = System.nanoTime(); // 每步掐表，轨迹记录真实耗时
                ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId,
                        knownMemory, runState);
                usedFallback = usedFallback || decision.fallback();

                if ("finish".equals(decision.action())) {
                    AnswerResult answer = StringUtils.hasText(decision.answer())
                            ? new AnswerResult(decision.answer(), false)
                            : summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId,
                                    knownMemory, runState);
                    usedFallback = usedFallback || answer.fallback();
                    Map<String, Object> observation = new LinkedHashMap<>();
                    observation.put("status", "completed");
                    observation.put("fallback", usedFallback);
                    if (decision.citations() != null && !decision.citations().isEmpty()) {
                        observation.put("citations", decision.citations());
                    }
                    if (decision.evidence() != null && !decision.evidence().isEmpty()) {
                        observation.put("evidence", decision.evidence());
                    }
                    trace.add(reactSupport.buildTraceStep(step, decision, observation,
                            reactSupport.elapsedMs(stepStartNs)));
                    ReactChatResponseVO response = finalizeResponse(request, answer.answer(), trace, routeDecision,
                            usedFallback, memorySnapshot, tenantId, memoryUserKey);
                    return agentRuntime.enrichAndRecord(request, tenantId, runState, response,
                            reactSupport.elapsedMs(startedNs));
                }

                Object observation = reactSupport.executeAction(request, runState, decision.action(),
                        decision.actionInput(), tenantId, "", "");
                trace.add(reactSupport.buildTraceStep(step, decision, observation,
                        reactSupport.elapsedMs(stepStartNs)));

                rollingContext = responseFormatter.appendContext(rollingContext, decision.action(), observation);
            }

            AnswerResult answer = summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId,
                    knownMemory, runState);
            ReactChatResponseVO response = finalizeResponse(request, answer.answer(), trace, routeDecision,
                    usedFallback || answer.fallback(), memorySnapshot, tenantId, memoryUserKey);
            return agentRuntime.enrichAndRecord(request, tenantId, runState, response,
                    reactSupport.elapsedMs(startedNs));
        } catch (RuntimeException ex) {
            agentRuntime.recordError(request, tenantId, runState, agentRuntime.safeReason(ex),
                    reactSupport.elapsedMs(startedNs));
            throw ex;
        }
    }

    /**
     * SSE 真流式对话：递归 stepFlux 每完成一轮（reason→动作执行）立即推一条 trace 帧，
     * 再流式推 token，最后推 done；任何异常转 error 事件。首帧不再等整个循环跑完。
     */
    public Flux<String> stream(ReactChatRequestVO request) {
        long startedNs = System.nanoTime();
        AtomicReference<Long> firstTokenLatencyMsRef = new AtomicReference<>(null);
        AtomicReference<String> outcomeRef = new AtomicReference<>("error");
        // defer 组装在请求线程执行（SecurityContext/租户可用）；外层 onErrorResume 拿不到局部量，用引用回传
        AtomicReference<AgentChatRuntime.RunState> runStateRef = new AtomicReference<>(null);
        AtomicReference<String> tenantIdRef = new AtomicReference<>("");

        return Flux.defer(() -> {
                    reactSupport.validateRequest(request);
                    String tenantId = reactSupport.currentTenantId();
                    tenantIdRef.set(tenantId);

                    // 智能体路径：运行态装载 + 安全拦截（命中转 error 事件，code=SAFETY_BLOCKED）
                    AgentChatRuntime.RunState runState = agentRuntime.prepare(request, tenantId);
                    if (runState != null && runState.blocked()) {
                        agentRuntime.recordBlocked(request, tenantId, runState, reactSupport.elapsedMs(startedNs));
                        outcomeRef.set("blocked");
                        return Flux.just(responseFormatter.formatSse("error", responseFormatter.toJson(
                                Map.of("message", runState.blockMessage(), "code", "SAFETY_BLOCKED"))));
                    }
                    runStateRef.set(runState);

                    ModelRouter.ModelRouteDecision routeDecision = modelRouter.resolve(
                            request.getModelProfile(), "react", request.getChatId(), tenantId);
                    // 记忆召回（尽力而为，循环外一次）：与同步链路同构；智能体路径按 memoryTurns 裁剪
                    String memoryUserKey = reactSupport.memoryUserKey(request.getChatId());
                    MemoryService.MemoryContextSnapshot memorySnapshot = reactSupport.recallMemory(
                            tenantId, memoryUserKey, request.getPrompt(), runState);

                    StreamState state = new StreamState(request, routeDecision, tenantId, memoryUserKey,
                            memorySnapshot, runState, startedNs, firstTokenLatencyMsRef, outcomeRef,
                            new ArrayList<>(), new AtomicReference<>(""), new AtomicReference<>(""),
                            new AtomicReference<>(false));
                    // 步骤链（阻塞的规划/工具执行）调度到 boundedElastic：订阅立即返回、
                    // SSE emitter 随之初始化，trace 帧随每步完成实时落线（否则帧会在订阅期间
                    // 同步跑完被桥接层聚合到一起），且不再占用 Tomcat 请求线程跑长任务。
                    // defer 内的 prepare/路由/记忆仍在本请求线程执行（依赖 MDC/SecurityContext）。
                    return stepFlux(state, 1).concatWith(finalAnswerFlux(state))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .onErrorResume(ex -> {
                    agentRuntime.recordError(request, tenantIdRef.get(), runStateRef.get(),
                            agentRuntime.safeReason(ex), reactSupport.elapsedMs(startedNs));
                    String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "回答生成失败，请稍后重试";
                    return Flux.just(responseFormatter.formatSse("error", responseFormatter.toJson(Map.of("message", message))));
                })
                .doFinally(signal -> reactSupport.recordStreamMetrics(
                        startedNs, firstTokenLatencyMsRef.get(), outcomeRef.get()));
    }

    // ── 流式循环 ───────────────────────────────────────────────────────────

    /** 流式链路共享状态：递归 flux 之间传递的可变载体（单订阅顺序执行，引用保证跨线程可见）。 */
    private record StreamState(ReactChatRequestVO request,
                               ModelRouter.ModelRouteDecision routeDecision,
                               String tenantId,
                               String memoryUserKey,
                               MemoryService.MemoryContextSnapshot memorySnapshot,
                               AgentChatRuntime.RunState runState,
                               long startedNs,
                               AtomicReference<Long> firstTokenMs,
                               AtomicReference<String> outcomeRef,
                               List<ReactTraceStepVO> trace,
                               AtomicReference<String> rollingContext,
                               AtomicReference<String> directAnswer,
                               AtomicReference<Boolean> usedFallback) {
    }

    /** 递归单步流：reason→（finish 则记直答，否则执行动作并滚动上下文）→ 立即发 trace 帧 → 续下一步。 */
    private Flux<String> stepFlux(StreamState state, int stepNum) {
        return Flux.defer(() -> {
            if (stepNum > MAX_STEPS) {
                return Flux.<String>empty(); // 轮次用尽，交由 finalAnswerFlux 强制总结成稿
            }
            long stepStartNs = System.nanoTime();
            ReasonDecision decision = reason(state.request(), state.rollingContext().get(), state.trace(),
                    state.routeDecision(), state.tenantId(),
                    reactSupport.memoryBlock(state.memorySnapshot()), state.runState());
            if (decision.fallback()) {
                state.usedFallback().set(true);
            }

            Map<String, Object> observation;
            if ("finish".equals(decision.action())) {
                observation = new LinkedHashMap<>();
                observation.put("status", "completed");
                observation.put("fallback", state.usedFallback().get());
                if (decision.citations() != null && !decision.citations().isEmpty()) {
                    observation.put("citations", decision.citations());
                }
                if (decision.evidence() != null && !decision.evidence().isEmpty()) {
                    observation.put("evidence", decision.evidence());
                }
                state.directAnswer().set(ReActSupport.emptyIfBlank(decision.answer()));
            } else {
                observation = reactSupport.executeAction(state.request(), state.runState(), decision.action(),
                        decision.actionInput(), state.tenantId(), "", "");
                state.rollingContext().set(responseFormatter.appendContext(
                        state.rollingContext().get(), decision.action(), observation));
            }

            ReactTraceStepVO stepVo = reactSupport.buildTraceStep(stepNum, decision, observation,
                    reactSupport.elapsedMs(stepStartNs));
            state.trace().add(stepVo);
            Flux<String> next = "finish".equals(decision.action())
                    ? Flux.empty()
                    : stepFlux(state, stepNum + 1);
            return Flux.just(responseFormatter.formatSse("trace", responseFormatter.toJson(stepVo)))
                    .concatWith(next);
        });
    }

    /** 成稿流：finish 直答则原样发 token，否则流式调用模型；末尾发 done 帧并写回 short 记忆。 */
    private Flux<String> finalAnswerFlux(StreamState state) {
        StringBuilder answerBuilder = new StringBuilder();
        return Flux.<String>defer(() -> {
                    String direct = state.directAnswer().get();
                    String finalSystem = promptMerger.finalSystem(ReActPlannerPrompts.FINAL_BASE_SYSTEM,
                            state.runState() == null ? null : state.runState().config());
                    return StringUtils.hasText(direct)
                            ? AnswerStreamSupport.chunked(direct)
                            : agentRuntime.callStream(finalSystem,
                                    plannerPrompts.finalPrompt(state.request(), state.trace(),
                                            state.rollingContext().get(),
                                            reactSupport.memoryBlock(state.memorySnapshot())),
                                    state.routeDecision(), state.tenantId(), "react_final", "react",
                                    state.runState());
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
                    ReactChatResponseVO response = responseFormatter.success(state.request().getChatId(), answer,
                            state.trace(), state.routeDecision(), state.usedFallback().get());
                    // 流式收尾同样回填 memoryUsed 并写回 short 记忆（读写两侧闭环）
                    response.setMemoryUsed(reactSupport.memoryUsedLabels(state.memorySnapshot()));
                    chatTurnMemoryRecorder.recordTurn(state.tenantId(), state.memoryUserKey(),
                            state.request().getChatId(), state.request().getPrompt(), answer);
                    agentRuntime.enrichAndRecord(state.request(), state.tenantId(), state.runState(), response,
                            reactSupport.elapsedMs(state.startedNs()));
                    state.outcomeRef().set("success");
                    return Flux.just(responseFormatter.formatSse("done", responseFormatter.toJson(response)));
                }));
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
        String planningPrompt = plannerPrompts.planningPrompt(request, rollingContext, trace,
                knownMemory, tenantId, runState);
        try {
            String raw = agentRuntime.call(
                    promptMerger.plannerSystem(ReActPlannerPrompts.PLANNER_BASE_SYSTEM,
                            runState == null ? null : runState.config()),
                    planningPrompt,
                    routeDecision,
                    tenantId,
                    "react_planner",
                    "react",
                    runState
            );
            return decisionParser.parse(raw);
        } catch (RuntimeException ex) {
            return decisionParser.fallback(request.getPrompt());
        }
    }

    /** 汇总最终答案：用轨迹 + 观察上下文再调一次模型；失败或空答案给兜底文案并标记 fallback。 */
    private AnswerResult summarizeAnswer(ReactChatRequestVO request,
                                         List<ReactTraceStepVO> trace,
                                         String rollingContext,
                                         ModelRouter.ModelRouteDecision routeDecision,
                                         String tenantId,
                                         String knownMemory,
                                         AgentChatRuntime.RunState runState) {
        String finalSystem = promptMerger.finalSystem(ReActPlannerPrompts.FINAL_BASE_SYSTEM,
                runState == null ? null : runState.config());
        String finalPrompt = plannerPrompts.finalPrompt(request, trace, rollingContext, knownMemory);
        try {
            String answer = agentRuntime.call(finalSystem, finalPrompt, routeDecision, tenantId,
                    "react_final", "react", runState);
            if (StringUtils.hasText(answer)) {
                return new AnswerResult(answer, false);
            }
        } catch (RuntimeException ignored) {
            // 走下方兜底逻辑
        }
        return new AnswerResult("当前未能生成最终答案，请稍后重试。", true);
    }

    /** 成稿收尾：回填 memoryUsed 观测标签，并把本轮问答尽力而为写回 short 记忆（读写两侧闭环，user 键 = userKey）。 */
    private ReactChatResponseVO finalizeResponse(ReactChatRequestVO request,
                                                 String answer,
                                                 List<ReactTraceStepVO> trace,
                                                 ModelRouter.ModelRouteDecision routeDecision,
                                                 boolean fallback,
                                                 MemoryService.MemoryContextSnapshot memorySnapshot,
                                                 String tenantId,
                                                 String memoryUserKey) {
        ReactChatResponseVO response = responseFormatter.success(request.getChatId(), answer, trace, routeDecision, fallback);
        response.setMemoryUsed(reactSupport.memoryUsedLabels(memorySnapshot));
        chatTurnMemoryRecorder.recordTurn(tenantId, memoryUserKey, request.getChatId(), request.getPrompt(), answer);
        return response;
    }

    /** 内部答案封装：最终回答 + 是否走了兜底 */
    private record AnswerResult(String answer, boolean fallback) {
    }
}
