package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.domain.agent.AgentRuntimeConfig;
import com.enterprise.iqk.domain.vo.AgentDebugResponseVO;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.ReactAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 智能体调试（FR-R2.1）：工作台右栏用已保存配置真跑一次会话。
 *  复用 ReactAgentService.chat 主链路（channel=debug，调用日志可区分来源），
 *  草稿可调（loadForChat debugMode=true），任何异常归一为 ERROR + 可展示文案，不让面板白屏。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentDebugService {

    private final AgentRuntimeConfigService agentRuntimeConfigService;
    private final ReactAgentService reactAgentService;

    public AgentDebugResponseVO debug(Long agentId, String message, String chatIdHint) {
        if (!StringUtils.hasText(message) || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "调试消息不能为空");
        }
        String tenantId = TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
        // 先走一次配置装载：草稿可调 + 校验错误在这里就暴露（返回 4xx 而不是 ERROR 面板）
        AgentRuntimeConfig config = agentRuntimeConfigService.loadForChat(tenantId, agentId, true);

        ReactChatRequestVO request = new ReactChatRequestVO();
        request.setPrompt(message);
        request.setChatId(StringUtils.hasText(chatIdHint) ? chatIdHint : "debug-" + UUID.randomUUID());
        request.setAgentId(agentId);
        request.setChannel("debug");

        long startedNs = System.nanoTime();
        try {
            ReactChatResponseVO response = reactAgentService.chat(request);
            return toResponse(config, response, elapsedMs(startedNs));
        } catch (RuntimeException ex) {
            log.warn("智能体调试执行失败: agentId={}, reason={}", agentId, ex.toString());
            return AgentDebugResponseVO.builder()
                    .status("ERROR")
                    .error(displayMessage(ex))
                    .latencyMs(elapsedMs(startedNs))
                    .safetyResult(AgentDebugResponseVO.SafetyResult.builder()
                            .checked(true).blocked(false).build())
                    .memorySummary(memorySummary(config, List.of()))
                    .steps(List.of())
                    .build();
        }
    }

    private AgentDebugResponseVO toResponse(AgentRuntimeConfig config, ReactChatResponseVO response, long latencyMs) {
        boolean blocked = Boolean.TRUE.equals(response.getSafetyBlocked());
        List<AgentDebugResponseVO.DebugStep> steps = response.getTrace() == null ? List.of()
                : response.getTrace().stream()
                        .map(this::toStep)
                        .toList();
        return AgentDebugResponseVO.builder()
                .status(blocked ? "BLOCKED" : "SUCCESS")
                .answer(response.getAnswer())
                .latencyMs(latencyMs)
                .modelSummary(AgentDebugResponseVO.ModelSummary.builder()
                        .modelName(response.getModelName())
                        .source(response.getModelSource())
                        .fallbackApplied(response.getModelFallback())
                        .routeProfile(response.getRouteProfile())
                        .routeReason(response.getRouteReason())
                        .routeCostTier(response.getRouteCostTier())
                        .build())
                .safetyResult(AgentDebugResponseVO.SafetyResult.builder()
                        .checked(true)
                        .blocked(blocked)
                        .blockMessage(response.getBlockMessage())
                        .build())
                .memorySummary(memorySummary(config, response.getMemoryUsed()))
                .steps(steps)
                .build();
    }

    private AgentDebugResponseVO.DebugStep toStep(ReactTraceStepVO step) {
        Object status = step.getObservation() instanceof Map<?, ?> observation
                ? observation.get("status")
                : null;
        return AgentDebugResponseVO.DebugStep.builder()
                .step(step.getStep())
                .action(step.getAction())
                .elapsedMs(step.getElapsedMs())
                .status(status == null ? "completed" : String.valueOf(status))
                .build();
    }

    private AgentDebugResponseVO.MemorySummary memorySummary(AgentRuntimeConfig config, List<String> injected) {
        return AgentDebugResponseVO.MemorySummary.builder()
                .memoryTurns(config.memoryTurns())
                .injectedLabels(injected == null ? List.of() : injected)
                .build();
    }

    /** 面板展示文案：业务错误取 reason，其余取 message，限长防爆。 */
    private String displayMessage(Throwable ex) {
        String text = ex instanceof ResponseStatusException rse && StringUtils.hasText(rse.getReason())
                ? rse.getReason()
                : (StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName());
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    private long elapsedMs(long startedNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }
}
