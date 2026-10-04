package com.enterprise.iqk.service;

import com.enterprise.iqk.agent.harness.AgentAction;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.agent.harness.PlannerActionCatalog;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.enterprise.iqk.service.ReactDecisionParser.ReasonDecision;

/**
 * ReAct 主循环服务：reason() 规划（动作白名单与提示词动作列表由 PlannerActionCatalog 从注册表生成）
 * → executeAction() 经 AgentHarnessService 执行 → 观测滚动拼接回上下文，最多 MAX_STEPS 步。
 * finish 时优先取决策自带答案，否则 summarizeAnswer() 用轨迹汇总生成；
 * 规划调用/解析失败走 decisionParser.fallback() 规则兜底；
 * callModel/callModelStream 统一预算断言与租户用量记账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReactAgentService {
    /** ReAct 循环步数上限，超出后强制汇总收尾 */
    private static final int MAX_STEPS = 4;

    private final AgentHarnessService agentHarnessService;
    /** 内部推理专用客户端（无对话记忆组件），避免未设 CONVERSATION_ID 时记忆断言失败 */
    private final ChatClient agentChatClient;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;
    private final MeterRegistry meterRegistry;
    private final ReactDecisionParser decisionParser;
    private final PlannerActionCatalog plannerActionCatalog;
    private final ReactResponseFormatter responseFormatter;
    /** 记忆子系统：召回 short/long/fact 注入规划与成稿（读侧闭环）。 */
    private final MemoryService memoryService;
    /** 对话轮次 short 记忆写入器：成稿后写回，让下一轮召回有内容（写侧闭环）。 */
    private final ChatTurnMemoryRecorder chatTurnMemoryRecorder;

    /** 同步对话：跑完 ReAct 循环后一次性返回完整响应（含轨迹、引用与路由信息）。 */
    public ReactChatResponseVO chat(ReactChatRequestVO request) {
        validateRequest(request);
        String tenantId = currentTenantId();
        ModelRouter.ModelRouteDecision routeDecision = resolveRouteDecision(
                request.getModelProfile(),
                "react",
                request.getChatId(),
                tenantId
        );

        List<ReactTraceStepVO> trace = new ArrayList<>();
        String rollingContext = "";
        boolean usedFallback = false;
        // 记忆召回（尽力而为，循环外一次）：planner 不挂 ChatMemory，
        // short/long/fact 全量注入 —— 会话前情、用户画像与租户可信事实
        // 都会影响动作选择与最终措辞。user 键取认证主体，匿名回落 chatId。
        String memoryUserKey = UserContext.currentUserId(request.getChatId());
        MemoryService.MemoryContextSnapshot memorySnapshot = recallMemory(tenantId, memoryUserKey);

        for (int step = 1; step <= MAX_STEPS; step++) {
            ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId, memorySnapshot);
            usedFallback = usedFallback || decision.fallback();

            if ("finish".equals(decision.action())) {
                AnswerResult answer = StringUtils.hasText(decision.answer())
                        ? new AnswerResult(decision.answer(), false)
                        : summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId, memorySnapshot);
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
                trace.add(ReactTraceStepVO.builder()
                        .step(step)
                        .thought(decision.thought())
                        .action("finish")
                        .actionInput(decision.actionInput())
                        .observation(observation)
                        .build());
                return finalizeResponse(request, answer.answer(), trace, routeDecision, usedFallback,
                        memorySnapshot, tenantId, memoryUserKey);
            }

            Object observation = executeAction(request, decision.action(), decision.actionInput(), tenantId);
            trace.add(ReactTraceStepVO.builder()
                    .step(step)
                    .thought(decision.thought())
                    .action(decision.action())
                    .actionInput(decision.actionInput())
                    .observation(observation)
                    .build());

            rollingContext = responseFormatter.appendContext(rollingContext, decision.action(), observation);
        }

        AnswerResult answer = summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId, memorySnapshot);
        return finalizeResponse(request, answer.answer(), trace, routeDecision, usedFallback || answer.fallback(),
                memorySnapshot, tenantId, memoryUserKey);
    }

    /** SSE 流式对话：先推 trace 事件，再流式推 token，最后推 done；任何异常转 error 事件。 */
    public Flux<String> stream(ReactChatRequestVO request) {
        long startedNs = System.nanoTime();
        AtomicReference<Long> firstTokenLatencyMsRef = new AtomicReference<>(null);
        AtomicReference<String> outcomeRef = new AtomicReference<>("error");

        return Flux.defer(() -> {
                    validateRequest(request);
                    String tenantId = currentTenantId();
                    ModelRouter.ModelRouteDecision routeDecision = resolveRouteDecision(
                            request.getModelProfile(),
                            "react",
                            request.getChatId(),
                            tenantId
                    );

                    List<ReactTraceStepVO> trace = new ArrayList<>();
                    String rollingContext = "";
                    String directAnswer = "";
                    boolean usedFallback = false;
                    // 记忆召回（尽力而为，循环外一次）：与同步链路同构
                    String memoryUserKey = UserContext.currentUserId(request.getChatId());
                    MemoryService.MemoryContextSnapshot memorySnapshot = recallMemory(tenantId, memoryUserKey);

                    for (int step = 1; step <= MAX_STEPS; step++) {
                        ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId, memorySnapshot);
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
                            trace.add(ReactTraceStepVO.builder()
                                    .step(step)
                                    .thought(decision.thought())
                                    .action("finish")
                                    .actionInput(decision.actionInput())
                                    .observation(observation)
                                    .build());
                            directAnswer = emptyIfBlank(decision.answer());
                            break;
                        }

                        Object observation = executeAction(request, decision.action(), decision.actionInput(), tenantId);
                        trace.add(ReactTraceStepVO.builder()
                                .step(step)
                                .thought(decision.thought())
                                .action(decision.action())
                                .actionInput(decision.actionInput())
                                .observation(observation)
                                .build());
                        rollingContext = responseFormatter.appendContext(rollingContext, decision.action(), observation);
                    }

                    boolean responseUsedFallback = usedFallback;

                    Flux<String> traceFlux = Flux.fromIterable(trace)
                            .map(step -> responseFormatter.formatSse("trace", responseFormatter.toJson(step)));
                    StringBuilder answerBuilder = new StringBuilder();

                    Flux<String> answerSourceFlux = StringUtils.hasText(directAnswer)
                            ? AnswerStreamSupport.chunked(directAnswer)
                            : callModelStream("你是企业级AI助手，请结合轨迹和观察信息给出最终答案。",
                                    buildFinalPrompt(request, trace, rollingContext, memorySnapshot),
                                    routeDecision, tenantId, "react_final");

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
                                outcomeRef.set("success");
                                return Flux.just(responseFormatter.formatSse("done", responseFormatter.toJson(response)));
                            }));
                })
                .onErrorResume(ex -> {
                    String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "回答生成失败，请稍后重试";
                    return Flux.just(responseFormatter.formatSse("error", responseFormatter.toJson(Map.of("message", message))));
                })
                .doFinally(signal -> recordStreamMetrics(startedNs, firstTokenLatencyMsRef.get(), outcomeRef.get()));
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

    /** 单步规划：让模型从白名单选动作输出 JSON 决策；调用或解析失败回退规则兜底。 */
    private ReasonDecision reason(ReactChatRequestVO request,
                                  String rollingContext,
                                  List<ReactTraceStepVO> trace,
                                  ModelRouter.ModelRouteDecision routeDecision,
                                  String tenantId,
                                  MemoryService.MemoryContextSnapshot memorySnapshot) {
        // 提示词用中文驱动，模型的 thought/answer 才会用中文输出；JSON 键名与动作名保持英文（解析器依赖）。
        String planningPrompt = """
                你是一个教育助手场景的 ReAct 规划器，负责为下一步选择且仅选择一个动作。
                thought（思考）与 answer（回答）必须使用简体中文书写。
                %n
                %s
                %n
                只返回 JSON，格式如下：
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
                request.getPrompt(),
                memoryBlock(memorySnapshot),
                emptyIfBlank(rollingContext),
                responseFormatter.toJson(trace)
        );

        try {
            String raw = callModel(
                    "你是严格的 JSON ReAct 规划器，只输出合法 JSON，thought 与 answer 用简体中文。",
                    planningPrompt,
                    routeDecision,
                    tenantId,
                    "react_planner"
            );
            return decisionParser.parse(raw);
        } catch (RuntimeException ex) {
            return decisionParser.fallback(request.getPrompt());
        }
    }

    /** 把决策动作交给 AgentHarnessService（守卫/运行时/消毒/留痕），返回观测 Map。 */
    private Map<String, Object> executeAction(ReactChatRequestVO request,
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
                ""
        )).toMap();
    }

    /** 汇总最终答案：用轨迹 + 观察上下文再调一次模型；失败或空答案给兜底文案并标记 fallback。 */
    private AnswerResult summarizeAnswer(ReactChatRequestVO request,
                                         List<ReactTraceStepVO> trace,
                                         String rollingContext,
                                         ModelRouter.ModelRouteDecision routeDecision,
                                         String tenantId,
                                         MemoryService.MemoryContextSnapshot memorySnapshot) {
        String finalPrompt = buildFinalPrompt(request, trace, rollingContext, memorySnapshot);
        try {
            String answer = callModel(
                    "你是企业级AI助手，请结合轨迹和观察信息给出最终答案。",
                    finalPrompt,
                    routeDecision,
                    tenantId,
                    "react_final"
            );
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
                responseFormatter.toJson(trace), emptyIfBlank(rollingContext));
    }

    /** 记忆召回：任何失败返回 null，按"无记忆可用"降级，绝不中断 ReAct 主链路。 */
    private MemoryService.MemoryContextSnapshot recallMemory(String tenantId, String userKey) {
        try {
            return memoryService.buildContext(tenantId, userKey);
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

    /** 解析本次请求的模型路由决策（场景 react，主体键为 chatId）。 */
    private ModelRouter.ModelRouteDecision resolveRouteDecision(String requestedProfile,
                                                                String endpoint,
                                                                String subjectKey,
                                                                String tenantId) {
        return modelRouter.resolve(requestedProfile, endpoint, tenantId, subjectKey);
    }

    /** 按路由决策构造带模型选项的 prompt 骨架。 */
    private ChatClient.ChatClientRequestSpec routedPrompt(ModelRouter.ModelRouteDecision decision) {
        return agentChatClient.prompt()
                .options(ChatOptions.builder().model(decision.model()).build());
    }

    /** 统一同步 LLM 调用：先预算断言，调用后按端点标签记租户用量。 */
    private String callModel(String systemPrompt,
                             String userPrompt,
                             ModelRouter.ModelRouteDecision routeDecision,
                             String tenantId,
                             String endpointTag) {
        long inputTokens = tenantCostService.estimateTokens(systemPrompt) + tenantCostService.estimateTokens(userPrompt);
        tenantCostService.assertBudget(tenantId, routeDecision.costTier(), inputTokens, 600);
        String output = routedPrompt(routeDecision)
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
        long outputTokens = tenantCostService.estimateTokens(output);
        tenantCostService.recordUsage(tenantId, routeDecision.costTier(), inputTokens, outputTokens, endpointTag);
        return output;
    }

    /** 统一流式 LLM 调用：预算断言 + 流中收集输出，流结束一次性记账（CAS 防重）。 */
    private Flux<String> callModelStream(String systemPrompt,
                                         String userPrompt,
                                         ModelRouter.ModelRouteDecision routeDecision,
                                         String tenantId,
                                         String endpointTag) {
        long inputTokens = tenantCostService.estimateTokens(systemPrompt) + tenantCostService.estimateTokens(userPrompt);
        tenantCostService.assertBudget(tenantId, routeDecision.costTier(), inputTokens, 600);

        StringBuilder outputCollector = new StringBuilder();
        AtomicBoolean usageRecorded = new AtomicBoolean(false);
        return routedPrompt(routeDecision)
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(chunk -> outputCollector.append(emptyIfBlank(chunk)))
                .doFinally(signalType -> {
                    if (!usageRecorded.compareAndSet(false, true)) {
                        return;
                    }
                    long outputTokens = tenantCostService.estimateTokens(outputCollector.toString());
                    tenantCostService.recordUsage(tenantId, routeDecision.costTier(), inputTokens, outputTokens, endpointTag);
                });
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
