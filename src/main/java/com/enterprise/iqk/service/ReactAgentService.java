package com.enterprise.iqk.service;

import com.enterprise.iqk.agent.harness.AgentAction;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.agent.harness.PlannerActionCatalog;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.service.agent.AgentChatRuntime;
import com.enterprise.iqk.service.agent.AgentPromptMerger;
import com.enterprise.iqk.util.AnswerStreamSupport;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.memory.ChatTurnMemoryRecorder;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.security.UserContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.enterprise.iqk.service.ReactDecisionParser.ReasonDecision;

/**
 * ReAct 主循环服务：reason() 规划（动作白名单与提示词动作列表由 PlannerActionCatalog 从注册表生成）
 * → executeAction() 经 AgentHarnessService 执行 → 观测滚动拼接回上下文，最多 MAX_STEPS 步。
 * finish 时优先取决策自带答案，否则 summarizeAnswer() 用轨迹汇总生成；
 * 规划调用/解析失败走 decisionParser.fallback() 规则兜底；
 * 模型调用统一经 AgentChatRuntime（绑定模型优先、按策略回退档位路由、预算与记账）。
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

    /** 平台级规划器系统提示词基座（智能体角色设定由 AgentPromptMerger 附加，永不覆盖本段） */
    private static final String PLANNER_BASE_SYSTEM =
            "你是严格的 JSON ReAct 规划器，只输出合法 JSON，thought 与 answer 用简体中文。";
    /** 平台级成稿系统提示词基座 */
    private static final String FINAL_BASE_SYSTEM =
            "你是企业级AI助手，请结合轨迹和观察信息给出最终答案。答案正文不要罗列「引用来源/参考文献」清单，来源由界面单独展示。";

    private final AgentHarnessService agentHarnessService;
    private final ModelRouter modelRouter;
    private final MeterRegistry meterRegistry;
    private final ReactDecisionParser decisionParser;
    private final PlannerActionCatalog plannerActionCatalog;
    private final ReactResponseFormatter responseFormatter;
    /** 记忆子系统：召回 short/long/fact 注入规划与成稿（读侧闭环）。 */
    private final MemoryService memoryService;
    /** 对话轮次 short 记忆写入器：成稿后写回，让下一轮召回有内容（写侧闭环）。 */
    private final ChatTurnMemoryRecorder chatTurnMemoryRecorder;
    /** 智能体角色设定合并器：平台基座提示词 + 角色设定（约束不覆盖平台要求）。 */
    private final AgentPromptMerger promptMerger;
    /** 智能体运行时共享组件：装载/拦截/绑定模型调用/回填/调用日志。 */
    private final AgentChatRuntime agentRuntime;
    /** 绑定工具目录（R7.3）：per-agent 生成 tool_call 可用工具清单提示词段。 */
    private final com.enterprise.iqk.service.agent.AgentToolCatalog agentToolCatalog;

    /** 同步对话：跑完 ReAct 循环后一次性返回完整响应（含轨迹、引用与路由信息）。 */
    public ReactChatResponseVO chat(ReactChatRequestVO request) {
        validateRequest(request);
        long startedNs = System.nanoTime();
        String tenantId = currentTenantId();

        // 智能体路径：配置装载 + 会话前安全拦截（首个命中即短路，模型调用不发生）
        AgentChatRuntime.RunState runState = agentRuntime.prepare(request, tenantId);
        if (runState != null && runState.blocked()) {
            return agentRuntime.blockedResponse(request, tenantId, runState, elapsedMs(startedNs));
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
            String memoryUserKey = UserContext.currentUserId(request.getChatId());
            MemoryService.MemoryContextSnapshot memorySnapshot = recallMemory(tenantId, memoryUserKey,
                    request.getPrompt(), runState);

            for (int step = 1; step <= MAX_STEPS; step++) {
                long stepStartNs = System.nanoTime(); // 每步掐表，轨迹记录真实耗时
                ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId,
                        memorySnapshot, runState);
                usedFallback = usedFallback || decision.fallback();

                if ("finish".equals(decision.action())) {
                    AnswerResult answer = StringUtils.hasText(decision.answer())
                            ? new AnswerResult(decision.answer(), false)
                            : summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId,
                                    memorySnapshot, runState);
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
                    trace.add(buildTraceStep(step, decision, observation, stepStartNs));
                    ReactChatResponseVO response = finalizeResponse(request, answer.answer(), trace, routeDecision,
                            usedFallback, memorySnapshot, tenantId, memoryUserKey);
                    return agentRuntime.enrichAndRecord(request, tenantId, runState, response, elapsedMs(startedNs));
                }

                Object observation = executeAction(request, runState, decision.action(), decision.actionInput(), tenantId);
                trace.add(buildTraceStep(step, decision, observation, stepStartNs));

                rollingContext = responseFormatter.appendContext(rollingContext, decision.action(), observation);
            }

            AnswerResult answer = summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId,
                    memorySnapshot, runState);
            ReactChatResponseVO response = finalizeResponse(request, answer.answer(), trace, routeDecision,
                    usedFallback || answer.fallback(), memorySnapshot, tenantId, memoryUserKey);
            return agentRuntime.enrichAndRecord(request, tenantId, runState, response, elapsedMs(startedNs));
        } catch (RuntimeException ex) {
            agentRuntime.recordError(request, tenantId, runState, agentRuntime.safeReason(ex), elapsedMs(startedNs));
            throw ex;
        }
    }

    /** SSE 流式对话：先推 trace 事件，再流式推 token，最后推 done；任何异常转 error 事件。 */
    public Flux<String> stream(ReactChatRequestVO request) {
        long startedNs = System.nanoTime();
        AtomicReference<Long> firstTokenLatencyMsRef = new AtomicReference<>(null);
        AtomicReference<String> outcomeRef = new AtomicReference<>("error");
        // defer 组装在请求线程执行（SecurityContext/租户可用）；外层 onErrorResume 拿不到局部量，用引用回传
        AtomicReference<AgentChatRuntime.RunState> runStateRef = new AtomicReference<>(null);
        AtomicReference<String> tenantIdRef = new AtomicReference<>("");

        return Flux.defer(() -> {
                    validateRequest(request);
                    String tenantId = currentTenantId();
                    tenantIdRef.set(tenantId);

                    // 智能体路径：运行态装载 + 安全拦截（命中转 error 事件，code=SAFETY_BLOCKED）
                    AgentChatRuntime.RunState runState = agentRuntime.prepare(request, tenantId);
                    if (runState != null && runState.blocked()) {
                        agentRuntime.recordBlocked(request, tenantId, runState, elapsedMs(startedNs));
                        outcomeRef.set("blocked");
                        return Flux.just(responseFormatter.formatSse("error", responseFormatter.toJson(
                                Map.of("message", runState.blockMessage(), "code", "SAFETY_BLOCKED"))));
                    }
                    runStateRef.set(runState);

                    ModelRouter.ModelRouteDecision routeDecision = modelRouter.resolve(
                            request.getModelProfile(), "react", request.getChatId(), tenantId);

                    List<ReactTraceStepVO> trace = new ArrayList<>();
                    String rollingContext = "";
                    String directAnswer = "";
                    boolean usedFallback = false;
                    // 记忆召回（尽力而为，循环外一次）：与同步链路同构；智能体路径按 memoryTurns 裁剪
                    String memoryUserKey = UserContext.currentUserId(request.getChatId());
                    MemoryService.MemoryContextSnapshot memorySnapshot = recallMemory(tenantId, memoryUserKey,
                            request.getPrompt(), runState);

                    for (int step = 1; step <= MAX_STEPS; step++) {
                        long stepStartNs = System.nanoTime(); // 每步掐表，轨迹记录真实耗时
                        ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId,
                                memorySnapshot, runState);
                        usedFallback = usedFallback || decision.fallback();
                        if ("finish".equals(decision.action())) {
                            Map<String, Object> observation = new LinkedHashMap<>();
                            observation.put("status", "completed");
                            observation.put("fallback", usedFallback);
                            if (decision.citations() != null && !decision.citations().isEmpty()) {
                                observation.put("citations", decision.citations());
                            }
                            if (decision.evidence() != null && !decision.evidence().isEmpty()) {
                                observation.put("evidence", decision.evidence());
                            }
                            trace.add(buildTraceStep(step, decision, observation, stepStartNs));
                            directAnswer = emptyIfBlank(decision.answer());
                            break;
                        }

                        Object observation = executeAction(request, runState, decision.action(), decision.actionInput(), tenantId);
                        trace.add(buildTraceStep(step, decision, observation, stepStartNs));
                        rollingContext = responseFormatter.appendContext(rollingContext, decision.action(), observation);
                    }

                    boolean responseUsedFallback = usedFallback;

                    Flux<String> traceFlux = Flux.fromIterable(trace)
                            .map(step -> responseFormatter.formatSse("trace", responseFormatter.toJson(step)));
                    StringBuilder answerBuilder = new StringBuilder();

                    String finalSystem = promptMerger.finalSystem(FINAL_BASE_SYSTEM,
                            runState == null ? null : runState.config());
                    Flux<String> answerSourceFlux = StringUtils.hasText(directAnswer)
                            ? AnswerStreamSupport.chunked(directAnswer)
                            : agentRuntime.callStream(finalSystem,
                                    buildFinalPrompt(request, trace, rollingContext, memorySnapshot),
                                    routeDecision, tenantId, "react_final", "react", runState);

                    Flux<String> tokenFlux = answerSourceFlux
                            .map(token -> {
                                if (firstTokenLatencyMsRef.get() == null) {
                                    firstTokenLatencyMsRef.set(elapsedMs(startedNs));
                                }
                                answerBuilder.append(token);
                                return responseFormatter.formatSse("token", responseFormatter.toJson(Map.of("token", token)));
                            });

                    return Flux.concat(traceFlux, tokenFlux)
                            .concatWith(Flux.defer(() -> {
                                if (firstTokenLatencyMsRef.get() == null) {
                                    firstTokenLatencyMsRef.set(elapsedMs(startedNs));
                                }
                                ReactChatResponseVO response = responseFormatter.success(
                                        request.getChatId(), answerBuilder.toString(), trace, routeDecision, responseUsedFallback);
                                // 流式收尾同样回填 memoryUsed 并写回 short 记忆（读写两侧闭环）
                                response.setMemoryUsed(memorySnapshot == null ? List.of() : memorySnapshot.usedLabels());
                                chatTurnMemoryRecorder.recordTurn(tenantId, memoryUserKey, request.getChatId(),
                                        request.getPrompt(), answerBuilder.toString());
                                agentRuntime.enrichAndRecord(request, tenantId, runState, response, elapsedMs(startedNs));
                                outcomeRef.set("success");
                                return Flux.just(responseFormatter.formatSse("done", responseFormatter.toJson(response)));
                            }));
                })
                .onErrorResume(ex -> {
                    agentRuntime.recordError(request, tenantIdRef.get(), runStateRef.get(),
                            agentRuntime.safeReason(ex), elapsedMs(startedNs));
                    String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "回答生成失败，请稍后重试";
                    return Flux.just(responseFormatter.formatSse("error", responseFormatter.toJson(Map.of("message", message))));
                })
                .doFinally(signal -> recordStreamMetrics(startedNs, firstTokenLatencyMsRef.get(), outcomeRef.get()));
    }

    // ── 规划与成稿 ─────────────────────────────────────────────────────────

    /** 单步规划：让模型从白名单选动作输出 JSON 决策；调用或解析失败回退规则兜底。 */
    private ReasonDecision reason(ReactChatRequestVO request,
                                  String rollingContext,
                                  List<ReactTraceStepVO> trace,
                                  ModelRouter.ModelRouteDecision routeDecision,
                                  String tenantId,
                                  MemoryService.MemoryContextSnapshot memorySnapshot,
                                  AgentChatRuntime.RunState runState) {
        // 提示词用中文驱动，模型的 thought/answer 才会用中文输出；JSON 键名与动作名保持英文（解析器依赖）。
        // 绑定工具目录（R7.3）：per-agent 动态段，紧跟动作块之后；未绑定工具时为空串零变化
        String toolCatalogSection = agentToolCatalog.catalogSection(
                runState == null ? null : runState.config().agentId(), tenantId);
        String planningPrompt = """
                你是一个企业智能助手场景的 ReAct 规划器，负责为下一步选择且仅选择一个动作。
                thought（思考）与 answer（回答）必须使用简体中文书写。
                %n
                %s
                %n
                %s
                只返回 JSON，格式如下：
                规则：即使记忆提示知识库无相关内容，也必须先执行一次 rag_search 验证（记忆可能过时）；仅当本次轨迹里的 rag_search 返回空结果或证据明显无关时，不要换关键词重试，直接 finish 并建议用户到「知识库」页上传相关文档。
                如果已知记忆中已包含用户问题的答案（如用户姓名、身份、偏好、之前结论），直接选择 finish 并引用记忆内容作答，不要执行 rag_search。
                如果用户询问你是谁/你能做什么/有什么功能，直接选择 finish 并介绍自己的能力（知识库检索问答RAG、业务数据查询、任务创建、天气查询、深度研究），不要执行 rag_search。
                {
                  "thought": "简短的中文推理",
                  "action": "从上面列表中选一个动作",
                  "action_input": {"key":"value"},
                  "answer": "仅当 action 为 finish 时提供，用中文作答"
                }
                %n
                用户问题：
                %s
                %n
                已知记忆（用户画像 / 历史对话 / 已确认事实，作为背景参考）：
                %s
                %n
                滚动上下文：
                %s
                %n
                已有轨迹：
                %s%n""".formatted(
                plannerActionCatalog.standardActionsBlock(),
                toolCatalogSection,
                request.getPrompt(),
                memoryBlock(memorySnapshot),
                emptyIfBlank(rollingContext),
                responseFormatter.traceSummary(trace)
        );

        try {
            String raw = agentRuntime.call(
                    promptMerger.plannerSystem(PLANNER_BASE_SYSTEM, runState == null ? null : runState.config()),
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

    /** 把决策动作交给 AgentHarnessService（守卫/运行时/消毒/留痕），返回观测 Map；agentId 供平台工具运行时校验绑定 */
    private Map<String, Object> executeAction(ReactChatRequestVO request,
                                              AgentChatRuntime.RunState runState,
                                              String action,
                                              Map<String, Object> actionInput,
                                              String tenantId) {
        return agentHarnessService.execute(new AgentAction(
                action,
                actionInput,
                request.getPrompt(),
                tenantId,
                request.getChatId(),
                request.getModelProfile(),
                "",
                "",
                runState == null ? 0L : runState.config().agentId(),
                false
        )).toMap();
    }

    /** 拼单步轨迹 VO：elapsedMs 用 stepStartNs 掐表换算，覆盖本步规划 + 动作执行全程。 */
    private ReactTraceStepVO buildTraceStep(int step, ReasonDecision decision,
                                            Object observation, long stepStartNs) {
        return ReactTraceStepVO.builder()
                .step(step)
                .thought(decision.thought())
                .action(decision.action())
                .actionInput(decision.actionInput())
                .observation(observation)
                .elapsedMs(elapsedMs(stepStartNs))
                .build();
    }

    /** 汇总最终答案：用轨迹 + 观察上下文再调一次模型；失败或空答案给兜底文案并标记 fallback。 */
    private AnswerResult summarizeAnswer(ReactChatRequestVO request,
                                         List<ReactTraceStepVO> trace,
                                         String rollingContext,
                                         ModelRouter.ModelRouteDecision routeDecision,
                                         String tenantId,
                                         MemoryService.MemoryContextSnapshot memorySnapshot,
                                         AgentChatRuntime.RunState runState) {
        String finalSystem = promptMerger.finalSystem(FINAL_BASE_SYSTEM,
                runState == null ? null : runState.config());
        String finalPrompt = buildFinalPrompt(request, trace, rollingContext, memorySnapshot);
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

    /** 构造汇总提示词：用户问题 + 已知记忆 + ReAct 轨迹 JSON + 观察上下文四段。 */
    private String buildFinalPrompt(ReactChatRequestVO request,
                                    List<ReactTraceStepVO> trace,
                                    String rollingContext,
                                    MemoryService.MemoryContextSnapshot memorySnapshot) {
        return """
                用户问题:
                %s
                %n
                已知记忆:
                %s
                %n
                ReAct轨迹:
                %s
                %n
                观察上下文:
                %s
                %n
                请输出最终中文答案，要求简洁、可执行、结构清晰。
                %n""".formatted(request.getPrompt(), memoryBlock(memorySnapshot),
                responseFormatter.traceSummary(trace), emptyIfBlank(rollingContext));
    }

    /** 记忆召回：未绑定智能体走全量注入；智能体路径 short 层按 memoryTurns 裁剪（一条记忆 = 一轮问答）。
     *  任何失败返回 null，按"无记忆可用"降级，绝不中断 ReAct 主链路。 */
    private MemoryService.MemoryContextSnapshot recallMemory(String tenantId, String userKey,
                                                             String query, AgentChatRuntime.RunState runState) {
        try {
            if (runState == null) {
                return memoryService.buildContext(tenantId, userKey, query);
            }
            return memoryService.buildContext(tenantId, userKey, query, true, runState.config().memoryTurns());
        } catch (Exception ex) {
            log.warn("记忆召回失败（不影响 ReAct 链路）: user={}, reason={}", userKey, ex.toString());
            return null;
        }
    }

    /** 记忆快照转 prompt 段；空快照用 "(none)" 占位，保持提示词结构稳定。 */
    private String memoryBlock(MemoryService.MemoryContextSnapshot snapshot) {
        return snapshot != null && StringUtils.hasText(snapshot.contextText())
                ? snapshot.contextText().trim() : "(none)";
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
        response.setMemoryUsed(memorySnapshot == null ? List.of() : memorySnapshot.usedLabels());
        chatTurnMemoryRecorder.recordTurn(tenantId, memoryUserKey, request.getChatId(), request.getPrompt(), answer);
        return response;
    }

    /** 纳秒起点换算毫秒耗时 */
    private long elapsedMs(long startedNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }

    /** 记录流式链路的总耗时 / 首 token 延迟 / 请求计数（按 outcome 打标）。 */
    private void recordStreamMetrics(long startedNs, Long firstTokenLatencyMs, String outcome) {
        long totalLatencyMs = elapsedMs(startedNs);
        Timer.builder("react.stream.total.latency")
                .description("End-to-end latency for /ai/react/chat/stream")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(totalLatencyMs, TimeUnit.MILLISECONDS);

        if (firstTokenLatencyMs != null) {
            Timer.builder("react.stream.first_token.latency")
                .description("Time-to-first-token latency for /ai/react/chat/stream")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(firstTokenLatencyMs, TimeUnit.MILLISECONDS);
        }

        Counter.builder("react.stream.requests")
                .description("Total streamed ReAct requests")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /** null/空白统一返回空串。 */
    private String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    /** 从 MDC 取归一化租户 ID。 */
    private String currentTenantId() {
        return TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
    }

    /** 校验 prompt 与 chatId 必填，缺失抛 IllegalArgumentException。 */
    private void validateRequest(ReactChatRequestVO request) {
        if (request == null || !StringUtils.hasText(request.getPrompt())) {
            throw new IllegalArgumentException("问题内容不能为空");
        }
        if (!StringUtils.hasText(request.getChatId())) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
    }

    /** 内部答案封装：最终回答 + 是否走了兜底 */
    private record AnswerResult(String answer, boolean fallback) {
    }
}
