package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.agent.workflow.AgentWorkflowEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 动作事件记录器：把动作的开始/完成/失败写进工作流事件流（workflow_event 表）。
 * 仅当动作携带 taskId + stepId（即由工作流编排发起）才落库；
 * 事件载荷先经消毒器脱敏截断，绝不把原始输入/全量观测写进日志。
 */
@Component
@RequiredArgsConstructor
public class HarnessEventRecorder {
    private final AgentWorkflowEngine workflowEngine;
    private final ActionSchemaRegistry schemaRegistry;
    private final HarnessPayloadSanitizer payloadSanitizer;

    /** 记录 ACTION_STARTED 事件（动作名/来源/风险等级/脱敏后的输入） */
    public void started(AgentAction action, String source) {
        if (!shouldRecord(action)) {
            return;
        }
        ActionSchema schema = schemaRegistry.find(action.action()).orElse(null);
        emit(action, "ACTION_STARTED", Map.of(
                "action", action.action(),
                "source", source,
                "riskLevel", schema == null ? "unknown" : schema.riskLevel(),
                "actionInput", payloadSanitizer.sanitizeActionInput(action, schema)
        ));
    }

    /** 记录 ACTION_COMPLETED / ACTION_FAILED 事件（观测仅保留摘要，不落全量载荷） */
    public void completed(AgentAction action, AgentObservation observation) {
        if (!shouldRecord(action)) {
            return;
        }
        emit(action, observation.successful() ? "ACTION_COMPLETED" : "ACTION_FAILED", Map.of(
                "action", action.action(),
                "source", observation.source(),
                "status", observation.status(),
                "latencyMs", observation.latencyMs(),
                "observation", payloadSanitizer.summarizeObservation(observation)
        ));
    }

    /** 透传给工作流引擎发事件（异步，失败不影响动作执行） */
    private void emit(AgentAction action, String eventType, Map<String, Object> payload) {
        workflowEngine.emitEvent(action.taskId(), action.stepId(), eventType, payload);
    }

    /** 只有工作流上下文内的动作（taskId、stepId 均有值）才记录事件 */
    private boolean shouldRecord(AgentAction action) {
        return action != null
                && StringUtils.hasText(action.taskId())
                && StringUtils.hasText(action.stepId());
    }
}
