package com.enterprise.iqk.service.platform;

import com.enterprise.iqk.domain.AgentToolConfirmationRecord;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.AgentToolConfirmationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 平台工具人工确认服务（R7.3"确认任务创建、审批、继续执行"）：
 * 创建（tool_call 命中 executionConfirm=true 时挂起）→ 用户审批（CAS 转移防并发双审）→
 * 审批通过后**重验绑定与启用态**再真实执行——挂起到审批之间工具可能被解绑/停用，
 * 以审批时刻的状态为准，不让过期授权溜进执行。过期采用惰性收尾（读取时置 EXPIRED）。
 * 审批人身份为当前租户内认证用户（与会话写权限同界，FR-R12-03 二次确认语义）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformToolConfirmationService {

    /** 待确认任务有效期：超时未审自动失效（防历史挂起被误批）。 */
    static final java.time.Duration PENDING_TTL = java.time.Duration.ofMinutes(10);
    /** 工作流审批节点有效期：企业审批是异步的（主管可能几小时后才批），不能套用 10 分钟会话 TTL。 */
    static final java.time.Duration WORKFLOW_TTL = java.time.Duration.ofHours(24);
    /** 结果摘要落库/回显截断上限。 */
    private static final int MAX_SUMMARY_CHARS = 4000;

    private final AgentToolConfirmationMapper confirmationMapper;
    private final PlatformToolInvoker toolInvoker;
    private final ToolInvocationService toolInvocationService;
    private final ObjectMapper objectMapper;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** 创建待确认任务（运行时挂起点）：参数快照原样存 JSON，审批后原样执行。 */
    public AgentToolConfirmationRecord create(Long agentId, String tenantId, String chatId,
                                              PlatformAsset tool, Map<String, Object> arguments) {
        LocalDateTime now = LocalDateTime.now();
        AgentToolConfirmationRecord record = AgentToolConfirmationRecord.builder()
                .tenantId(tenantId)
                .agentId(agentId)
                .chatId(chatId)
                .toolAssetId(tool.getId())
                .toolName(tool.getName())
                .argumentsJson(writeArguments(arguments))
                .status("PENDING")
                .createdAt(now)
                .expiresAt(now.plus(PENDING_TTL))
                .build();
        confirmationMapper.insert(record);
        return record;
    }

    /**
     * 工作流 HUMAN_APPROVAL 节点的审批任务：无工具执行语义，审批结果经事件回调图运行时继续/终止。
     * TTL 拉长到 24 小时（企业审批是异步的）。
     */
    public AgentToolConfirmationRecord createForWorkflow(String tenantId, String chatId, String taskId,
                                                         String stepId, String nodeName, String message) {
        LocalDateTime now = LocalDateTime.now();
        AgentToolConfirmationRecord record = AgentToolConfirmationRecord.builder()
                .tenantId(tenantId)
                .chatId(chatId)
                .taskId(taskId)
                .stepId(stepId)
                .toolName(nodeName)
                .argumentsJson(writeArguments(Map.of("message", message == null ? "" : message)))
                .status("PENDING")
                .createdAt(now)
                .expiresAt(now.plus(WORKFLOW_TTL))
                .build();
        confirmationMapper.insert(record);
        return record;
    }

    /** 审批并执行：CAS 抢到 PENDING → APPROVED 后重验绑定，执行结果/失败都落库收尾。 */
    public Map<String, Object> approve(Long id, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        confirmationMapper.expireOverdue(tenantId, now);
        AgentToolConfirmationRecord record = require(id, tenantId);
        if (confirmationMapper.casApprove(id, tenantId, now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "确认任务当前状态为 " + record.getStatus() + "，无法审批（可能已过期/已取消/已执行）");
        }
        // 工作流审批节点：无工具执行语义，审批通过即发布事件让图运行时继续
        if (StringUtils.hasText(record.getTaskId())) {
            confirmationMapper.casFinish(id, "EXECUTED",
                    toJson(Map.of("workflowApproved", true)), null, LocalDateTime.now());
            eventPublisher.publishEvent(new com.enterprise.iqk.agent.workflow.WorkflowApprovalEvent(
                    record.getTaskId(), record.getStepId(), true));
            return Map.of("status", "EXECUTED", "workflowResumed", true);
        }
        try {
            PlatformAsset tool = toolInvoker.findBoundTool(record.getAgentId(), tenantId, record.getToolName());
            // 审批时刻重验：绑定被移除或工具停用则拒绝执行（防过期授权）
            if (tool == null || !tool.getId().equals(record.getToolAssetId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "工具「" + record.getToolName() + "」已不在该智能体的绑定列表内，无法执行");
            }
            Map<String, Object> result = toolInvoker.invoke(tool,
                    toolInvocationService.parseToolConfig(tool), readArguments(record));
            confirmationMapper.casFinish(id, "EXECUTED", truncate(toJson(result)), null, LocalDateTime.now());
            return Map.of("status", "EXECUTED", "result", result);
        } catch (ResponseStatusException ex) {
            confirmationMapper.casFinish(id, "FAILED", null, safeReason(ex), LocalDateTime.now());
            throw ex;
        } catch (Exception ex) {
            String reason = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
            confirmationMapper.casFinish(id, "FAILED", null, reason, LocalDateTime.now());
            log.warn("确认工具执行失败: confirmationId={}, tool={}, reason={}",
                    id, record.getToolName(), ex.toString());
            return Map.of("status", "FAILED", "message", reason);
        }
    }

    /** 取消：CAS PENDING → CANCELLED；非 PENDING 报冲突。工作流审批取消会通知图运行时终止任务。 */
    public Map<String, Object> cancel(Long id, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        confirmationMapper.expireOverdue(tenantId, now);
        AgentToolConfirmationRecord record = require(id, tenantId);
        if (confirmationMapper.casCancel(id, tenantId, now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "确认任务不在待确认状态，无法取消");
        }
        if (StringUtils.hasText(record.getTaskId())) {
            eventPublisher.publishEvent(new com.enterprise.iqk.agent.workflow.WorkflowApprovalEvent(
                    record.getTaskId(), record.getStepId(), false));
        }
        return Map.of("status", "CANCELLED");
    }

    /** 读取并惰性过期校验：不存在/跨租户 404。 */
    private AgentToolConfirmationRecord require(Long id, String tenantId) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid id");
        }
        AgentToolConfirmationRecord record = confirmationMapper.findByIdAndTenant(id, tenantId);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "确认任务不存在");
        }
        return record;
    }

    private String writeArguments(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception ex) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArguments(AgentToolConfirmationRecord record) {
        try {
            return objectMapper.readValue(record.getArgumentsJson() == null
                    ? "{}" : record.getArgumentsJson(), Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String truncate(String value) {
        return value != null && value.length() > MAX_SUMMARY_CHARS
                ? value.substring(0, MAX_SUMMARY_CHARS) + "...[truncated]" : value;
    }

    /** 异常信息收敛：ResponseStatusException 取 reason，其余取 message/类名，限长。 */
    private String safeReason(ResponseStatusException ex) {
        String text = StringUtils.hasText(ex.getReason()) ? ex.getReason() : ex.getClass().getSimpleName();
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
