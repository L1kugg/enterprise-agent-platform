package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.config.properties.AgentRuntimeProperties;
import com.enterprise.iqk.domain.AuditLog;
import com.enterprise.iqk.domain.agent.AgentInvocationLogRecord;
import com.enterprise.iqk.mapper.AgentInvocationLogMapper;
import com.enterprise.iqk.mapper.AuditLogMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 智能体调用日志（agent_invocation_log）记录器：每次智能体会话/调试落一条，
 * 满足 FR-R1-01 可追溯并为 R2.2 看板备数；安全命中额外写审计行 + 计数器。
 * 尽力而为：任何落库失败只记告警，绝不打断聊天主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentInvocationLogRecorder {

    /** 一次智能体调用的定位信息；prompt 只进摘录，不落全文。 */
    public record AgentInvocationContext(
            Long agentId, String tenantId, String userId, String chatId, String channel, String prompt) {
    }

    private final AgentInvocationLogMapper invocationLogMapper;
    private final AuditLogMapper auditLogMapper;
    private final MeterRegistry meterRegistry;
    private final AgentRuntimeProperties properties;

    public void recordSuccess(AgentInvocationContext ctx, String modelName,
                              boolean fallbackApplied, long latencyMs) {
        insert(ctx, "SUCCESS", modelName, fallbackApplied, latencyMs, null, null);
    }

    /** 命中安全拦截：调用日志 BLOCKED + 独立审计行 + 计数器（模型调用从未发生）。 */
    public void recordBlocked(AgentInvocationContext ctx, SafetyMatchResult safety, long latencyMs) {
        insert(ctx, "BLOCKED", null, false, latencyMs,
                safety == null ? null : "guard=" + safety.hitGuardId() + " level=" + safety.hitLevel(), null);
        recordBlockAudit(ctx, safety);
        Counter.builder("agent.runtime.safety.blocked")
                .description("Agent chat inputs blocked by bound safety guards")
                .tag("agentId", String.valueOf(ctx.agentId()))
                .register(meterRegistry)
                .increment();
    }

    public void recordError(AgentInvocationContext ctx, String errorSummary,
                            String modelName, long latencyMs) {
        insert(ctx, "ERROR", modelName, false, latencyMs, null, errorSummary);
    }

    private void insert(AgentInvocationContext ctx, String status, String modelName,
                        boolean fallbackApplied, long latencyMs, String blockDetail, String errorSummary) {
        try {
            LocalDateTime now = LocalDateTime.now();
            invocationLogMapper.insert(AgentInvocationLogRecord.builder()
                    .tenantId(ctx.tenantId())
                    .agentId(ctx.agentId())
                    .userId(nullSafe(ctx.userId()))
                    .chatId(nullSafe(ctx.chatId()))
                    .channel(nullSafe(ctx.channel()).isBlank() ? "platform" : ctx.channel())
                    .status(status)
                    .modelName(modelName)
                    .fallbackApplied(fallbackApplied)
                    .latencyMs(latencyMs)
                    .promptExcerpt(truncate(ctx.prompt(), properties.getInvocationPromptExcerptChars()))
                    .errorSummary(truncate(blockDetail != null ? blockDetail : errorSummary, 512))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        } catch (Exception ex) {
            log.warn("智能体调用日志写入失败（不影响会话）: agentId={}, status={}, reason={}",
                    ctx.agentId(), status, ex.toString());
        }
    }

    private void recordBlockAudit(AgentInvocationContext ctx, SafetyMatchResult safety) {
        try {
            auditLogMapper.insert(AuditLog.builder()
                    .requestId(MDC.get("request_id"))
                    .traceId(MDC.get("trace_id"))
                    .tenantId(ctx.tenantId())
                    .userIdentity(ctx.userId())
                    .method("CHAT")
                    .path("/agent-runtime/safety-block")
                    .statusCode(200)
                    .chatId(ctx.chatId())
                    .extraPayload(safety == null ? "agentId=" + ctx.agentId()
                            : "agentId=%s,guardId=%s,hitLevel=%s,fragmentCount=%d".formatted(
                            ctx.agentId(), safety.hitGuardId(), safety.hitLevel(),
                            safety.matchedFragments() == null ? 0 : safety.matchedFragments().size()))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception ex) {
            log.warn("安全拦截审计写入失败（不影响会话）: agentId={}, reason={}", ctx.agentId(), ex.toString());
        }
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
