package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.config.properties.AgentRuntimeProperties;
import com.enterprise.iqk.domain.agent.AgentRuntimeConfig;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.llm.ModelCallGuard;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.llm.PlatformChatModelRegistry;
import com.enterprise.iqk.security.UserContext;
import com.enterprise.iqk.service.TenantCostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智能体会话运行时共享组件：ReactAgentService 与 WorkflowReactAgentService 的公共执行环节——
 * 配置装载、会话前安全拦截、绑定模型调用（失败按策略回退档位路由）、响应回填与调用日志。
 * 未绑定智能体（RunState == null）时各方法按"无智能体"透传，原路径行为零变化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentChatRuntime {

    private final AgentRuntimeConfigService agentRuntimeConfigService;
    private final AgentSafetyService agentSafetyService;
    private final PlatformChatModelRegistry chatModelRegistry;
    private final AgentInvocationLogRecorder invocationLogRecorder;
    private final AgentRuntimeProperties agentRuntimeProperties;
    private final ModelCallGuard modelCallGuard;
    private final TenantCostService tenantCostService;
    /** 内部推理专用客户端（无对话记忆组件），档位路由路径使用 */
    private final ChatClient agentChatClient;

    /** 单次智能体会话的运行态：绑定模型解析结果、安全拦截结果、回退标记与轨迹备注（请求内使用）。 */
    public static final class RunState {
        private final AgentRuntimeConfig config;
        private PlatformChatModelRegistry.BoundChatModel boundModel;
        private SafetyMatchResult safetyResult;
        private boolean blocked;
        private final List<String> traceNotes = new ArrayList<>();
        private volatile boolean modelFallback;
        private volatile String usedModelName;
        private volatile String modelSource = "router";

        RunState(AgentRuntimeConfig config) {
            this.config = config;
        }

        public AgentRuntimeConfig config() {
            return config;
        }

        /** 安全拦截是否命中；命中后调用方必须短路，不得发起任务或模型调用。 */
        public boolean blocked() {
            return blocked;
        }

        /** 命中防护的拦截文案（blocked() 为 true 时可用）。 */
        public String blockMessage() {
            return safetyResult == null ? "" : safetyResult.blockMessage();
        }
    }

    /**
     * 装载运行态：未带 agentId 返回 null（原路径）；拦截命中返回 blocked()=true 的占位态（调用方短路）；
     * 配置错误（404/403/400）直接抛出。绑定模型在此预解析，坏配置快速失败。
     */
    public RunState prepare(ReactChatRequestVO request, String tenantId) {
        if (request.getAgentId() == null) {
            return null;
        }
        AgentRuntimeConfig config = agentRuntimeConfigService.loadForChat(tenantId, request.getAgentId(), false);
        SafetyMatchResult safety = agentSafetyService.interceptInput(tenantId, config, request.getPrompt());
        if (safety.blocked()) {
            RunState state = new RunState(config);
            state.safetyResult = safety;
            state.blocked = true;
            return state;
        }
        RunState state = new RunState(config);
        if (config.hasModel()) {
            state.boundModel = chatModelRegistry.resolve(config.modelServiceId(), tenantId);
        }
        return state;
    }

    /** 安全命中短路响应：ok=0 + 拦截文案，trace 为空，全程未发生模型调用，另记 BLOCKED 日志/审计/计数。 */
    public ReactChatResponseVO blockedResponse(ReactChatRequestVO request, String tenantId,
                                               RunState state, long latencyMs) {
        invocationLogRecorder.recordBlocked(invocationContext(request, tenantId), state.safetyResult, latencyMs);
        return ReactChatResponseVO.builder()
                .ok(0)
                .msg(state.blockMessage())
                .chatId(request.getChatId())
                .answer(state.blockMessage())
                .fallback(false)
                .trace(List.of())
                .memoryUsed(List.of())
                .agentId(request.getAgentId())
                .safetyBlocked(true)
                .blockMessage(state.blockMessage())
                .build();
    }

    /** 流式路径安全命中：只记 BLOCKED 日志/审计/计数，error 事件由调用方拼装。 */
    public void recordBlocked(ReactChatRequestVO request, String tenantId, RunState state, long latencyMs) {
        invocationLogRecorder.recordBlocked(invocationContext(request, tenantId), state.safetyResult, latencyMs);
    }

    /** 回填 agentId/模型来源/回退标记与轨迹备注，并记 SUCCESS 调用日志；未绑定智能体时原响应原样返回。 */
    public ReactChatResponseVO enrichAndRecord(ReactChatRequestVO request, String tenantId,
                                               RunState state, ReactChatResponseVO response, long latencyMs) {
        if (state == null) {
            return response;
        }
        response.setAgentId(request.getAgentId());
        response.setSafetyBlocked(false);
        response.setModelName(state.usedModelName);
        response.setModelSource(state.modelSource);
        response.setModelFallback(state.modelFallback);
        if (!state.traceNotes.isEmpty()) {
            String notes = String.join("；", state.traceNotes);
            response.setRouteReason(StringUtils.hasText(response.getRouteReason())
                    ? response.getRouteReason() + "；" + notes : notes);
        }
        invocationLogRecorder.recordSuccess(invocationContext(request, tenantId),
                state.usedModelName, state.modelFallback, latencyMs);
        return response;
    }

    /** 错误路径调用日志（尽力而为）；未绑定智能体时不记。 */
    public void recordError(ReactChatRequestVO request, String tenantId, RunState state,
                            String errorSummary, long latencyMs) {
        if (state == null) {
            return;
        }
        invocationLogRecorder.recordError(invocationContext(request, tenantId), errorSummary,
                state.usedModelName, latencyMs);
    }

    private AgentInvocationLogRecorder.AgentInvocationContext invocationContext(ReactChatRequestVO request,
                                                                                String tenantId) {
        return new AgentInvocationLogRecorder.AgentInvocationContext(
                request.getAgentId(),
                tenantId,
                UserContext.currentUserId(request.getChatId()),
                request.getChatId(),
                StringUtils.hasText(request.getChannel()) ? request.getChannel() : "platform",
                request.getPrompt());
    }

    /** 异常信息收敛为可展示文案：ResponseStatusException 取 reason，其余取 message/类名，限长防爆。 */
    public String safeReason(Throwable ex) {
        String text = ex instanceof ResponseStatusException rse && StringUtils.hasText(rse.getReason())
                ? rse.getReason()
                : (StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName());
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    /** 绑定模型的按请求选项：模型名 + 可选 temperature（未配置则用模型默认）。 */
    private OpenAiChatOptions boundOptions(RunState state) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(state.boundModel.modelName());
        if (state.config.temperature() != null) {
            builder.temperature(state.config.temperature());
        }
        return builder.build();
    }

    /** 按路由决策构造带模型选项的 prompt 骨架（档位路由路径）。 */
    private ChatClient.ChatClientRequestSpec routedPrompt(ModelRouter.ModelRouteDecision decision) {
        return agentChatClient.prompt()
                .options(org.springframework.ai.chat.prompt.ChatOptions.builder().model(decision.model()).build());
    }

    /** 统一同步 LLM 调用：先预算断言，调用后按端点标签记租户用量；绑定模型失败按策略回退档位路由。
     *  routerScenario 是回退路径的熔断场景名（react 服务传 react，workflow 服务传 workflow）。 */
    public String call(String systemPrompt,
                       String userPrompt,
                       ModelRouter.ModelRouteDecision routeDecision,
                       String tenantId,
                       String endpointTag,
                       String routerScenario,
                       RunState state) {
        long inputTokens = tenantCostService.estimateTokens(systemPrompt) + tenantCostService.estimateTokens(userPrompt);
        tenantCostService.assertBudget(tenantId, routeDecision.costTier(), inputTokens, 600);
        String output;
        if (state != null && state.boundModel != null) {
            try {
                output = modelCallGuard.call("agent-chat", () -> state.boundModel.chatClient().prompt()
                        .options(boundOptions(state))
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .content());
                state.modelSource = "bound";
                state.usedModelName = state.boundModel.modelName();
                tenantCostService.recordUsage(tenantId, routeDecision.costTier(), inputTokens,
                        tenantCostService.estimateTokens(output), endpointTag);
                return output;
            } catch (RuntimeException ex) {
                if (!agentRuntimeProperties.isModelFallbackEnabled()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "智能体绑定的模型服务调用失败，已按策略关闭回退：" + safeReason(ex), ex);
                }
                state.modelFallback = true;
                state.traceNotes.add("绑定模型调用失败，已回退到路由模型（" + safeReason(ex) + "）");
                log.warn("智能体绑定模型调用失败，回退路由模型: agentId={}, reason={}",
                        state.config.agentId(), ex.toString());
            }
        }
        output = modelCallGuard.call(routerScenario, () -> routedPrompt(routeDecision)
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content());
        if (state != null) {
            state.modelSource = "router";
            state.usedModelName = routeDecision.model();
        }
        tenantCostService.recordUsage(tenantId, routeDecision.costTier(), inputTokens,
                tenantCostService.estimateTokens(output), endpointTag);
        return output;
    }

    /** 统一流式 LLM 调用：预算断言 + 流结束一次性记账（CAS 防重）。
     *  绑定模型仅在首 token 前失败才回退（首 token 后回退会重复播报已输出内容）。 */
    public Flux<String> callStream(String systemPrompt,
                                   String userPrompt,
                                   ModelRouter.ModelRouteDecision routeDecision,
                                   String tenantId,
                                   String endpointTag,
                                   String routerScenario,
                                   RunState state) {
        long inputTokens = tenantCostService.estimateTokens(systemPrompt) + tenantCostService.estimateTokens(userPrompt);
        tenantCostService.assertBudget(tenantId, routeDecision.costTier(), inputTokens, 600);

        if (state != null && state.boundModel != null) {
            StringBuilder boundCollector = new StringBuilder();
            AtomicBoolean boundUsageRecorded = new AtomicBoolean(false);
            AtomicBoolean firstTokenEmitted = new AtomicBoolean(false);
            OpenAiChatOptions options = boundOptions(state);
            return modelCallGuard.streaming("agent-chat", state.boundModel.chatClient().prompt()
                            .options(options)
                            .system(systemPrompt)
                            .user(userPrompt)
                            .stream()
                            .content())
                    .doOnNext(chunk -> {
                        firstTokenEmitted.set(true);
                        boundCollector.append(emptyIfBlank(chunk));
                    })
                    .doFinally(signalType -> {
                        if (boundUsageRecorded.compareAndSet(false, true)) {
                            tenantCostService.recordUsage(tenantId, routeDecision.costTier(), inputTokens,
                                    tenantCostService.estimateTokens(boundCollector.toString()), endpointTag);
                        }
                    })
                    .onErrorResume(ex -> {
                        if (firstTokenEmitted.get()) {
                            // 已有输出不可回退（会重复播报），交由外层 error 事件收场
                            return Flux.error(ex);
                        }
                        if (!agentRuntimeProperties.isModelFallbackEnabled()) {
                            return Flux.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                    "智能体绑定的模型服务调用失败，已按策略关闭回退：" + safeReason(ex), ex));
                        }
                        state.modelFallback = true;
                        state.traceNotes.add("绑定模型调用失败，已回退到路由模型（" + safeReason(ex) + "）");
                        log.warn("智能体绑定模型流式调用失败，回退路由模型: agentId={}, reason={}",
                                state.config.agentId(), ex.toString());
                        return routerStream(systemPrompt, userPrompt, routeDecision, tenantId,
                                endpointTag, routerScenario, inputTokens, state);
                    });
        }
        return routerStream(systemPrompt, userPrompt, routeDecision, tenantId, endpointTag, routerScenario,
                inputTokens, state);
    }

    /** 档位路由流式路径；预算已由调用方断言，流结束一次性记账。 */
    private Flux<String> routerStream(String systemPrompt,
                                      String userPrompt,
                                      ModelRouter.ModelRouteDecision routeDecision,
                                      String tenantId,
                                      String endpointTag,
                                      String routerScenario,
                                      long inputTokens,
                                      RunState state) {
        StringBuilder outputCollector = new StringBuilder();
        AtomicBoolean usageRecorded = new AtomicBoolean(false);
        return modelCallGuard.streaming(routerScenario, routedPrompt(routeDecision)
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content())
                .doOnNext(chunk -> outputCollector.append(emptyIfBlank(chunk)))
                .doOnSubscribe(subscribe -> {
                    if (state != null) {
                        state.modelSource = "router";
                        state.usedModelName = routeDecision.model();
                    }
                })
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
}
