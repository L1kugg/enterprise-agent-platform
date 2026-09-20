package com.enterprise.iqk.service;

import com.enterprise.iqk.agent.harness.AgentAction;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.security.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
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
 * ReAct 主循环服务：reason() 规划（动作白名单硬编码，不含 mcp_call）
 * → executeAction() 经 AgentHarnessService 执行 → 观测滚动拼接回上下文，最多 MAX_STEPS 步。
 * finish 时优先取决策自带答案，否则 summarizeAnswer() 用轨迹汇总生成；
 * 规划调用/解析失败走 decisionParser.fallback() 规则兜底；
 * callModel/callModelStream 统一预算断言与租户用量记账。
 */
@Service
@RequiredArgsConstructor
public class ReactAgentService {
    /** ReAct 循环步数上限，超出后强制汇总收尾 */
    private static final int MAX_STEPS = 4;

    private final AgentHarnessService agentHarnessService;
    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;
    private final MeterRegistry meterRegistry;
    private final ReactDecisionParser decisionParser;
    private final ReactResponseFormatter responseFormatter;

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

        for (int step = 1; step <= MAX_STEPS; step++) {
            ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId);
            usedFallback = usedFallback || decision.fallback();

            if ("finish".equals(decision.action())) {
                AnswerResult answer = StringUtils.hasText(decision.answer())
                        ? new AnswerResult(decision.answer(), false)
                        : summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId);
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
                return responseFormatter.success(request.getChatId(), answer.answer(), trace, routeDecision, usedFallback);
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

        AnswerResult answer = summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId);
        return responseFormatter.success(request.getChatId(), answer.answer(), trace, routeDecision, usedFallback || answer.fallback());
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

                    for (int step = 1; step <= MAX_STEPS; step++) {
                        ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId);
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
                            ? Flux.just(directAnswer)
                            : callModelStream(
                            "你是企业级AI助手，请结合轨迹和观察信息给出最终答案。",
                            buildFinalPrompt(request, trace, rollingContext),
                            routeDecision,
                            tenantId,
                            "react_final"
                    );

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
                                outcomeRef.set("success");
                                return Flux.just(responseFormatter.formatSse("done", responseFormatter.toJson(response)));
                            }));
                })
                .onErrorResume(ex -> {
                    String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "stream failed";
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
                                  String tenantId) {
        String planningPrompt = """
                You are a ReAct planner for an education assistant.
                You must choose exactly one action for the next step.
                %n
                Allowed actions:
                - query_school
                - query_course
                - add_course_reservation
                - rag_search
                - finish
                %n
                Return JSON only:
                {
                  "thought": "short reasoning",
                  "action": "one action from list",
                  "action_input": {"key":"value"},
                  "answer": "only provide when action is finish"
                }
                %n
                User question:
                %s
                %n
                Rolling context:
                %s
                %n
                Existing trace:
                %s%n""".formatted(
                request.getPrompt(),
                emptyIfBlank(rollingContext),
                responseFormatter.toJson(trace)
        );

        try {
            String raw = callModel(
                    "You are strict JSON ReAct planner. Return valid JSON only.",
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
                                         String tenantId) {
        String finalPrompt = buildFinalPrompt(request, trace, rollingContext);
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

    /** 构造汇总提示词：用户问题 + ReAct 轨迹 JSON + 观察上下文三段。 */
    private String buildFinalPrompt(ReactChatRequestVO request,
                                    List<ReactTraceStepVO> trace,
                                    String rollingContext) {
        return """
                用户问题:
                %s
                %n
                ReAct轨迹:
                %s
                %n
                观察上下文:
                %s
                %n
                请输出最终中文答案，要求简洁、可执行、结构清晰。
                %n""".formatted(request.getPrompt(), responseFormatter.toJson(trace), emptyIfBlank(rollingContext));
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
        return chatClient.prompt()
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
            throw new IllegalArgumentException("prompt is required");
        }
        if (!StringUtils.hasText(request.getChatId())) {
            throw new IllegalArgumentException("chatId is required");
        }
    }

    /** 内部答案封装：最终回答 + 是否走了兜底 */
    private record AnswerResult(String answer, boolean fallback) {
    }
}
