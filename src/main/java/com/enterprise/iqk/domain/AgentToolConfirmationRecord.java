package com.enterprise.iqk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 平台工具人工确认任务（R7.3）：高危工具（executionConfirm=true）被 tool_call 调用时
 * 挂起一条 PENDING 记录，用户审批后原子转移状态并真实执行。
 */
@TableName("agent_tool_confirmation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolConfirmationRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long agentId;
    private String chatId;
    /** 工作流审批节点关联的任务/步骤（非空 = 审批结果回调图运行时；聊天工具审批为空） */
    private String taskId;
    private String stepId;
    private Long toolAssetId;
    private String toolName;
    /** 模型给出的调用参数快照（审批后原样执行） */
    private String argumentsJson;
    /** PENDING | APPROVED | EXECUTED | FAILED | CANCELLED | EXPIRED */
    private String status;
    private String resultSummary;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;
    private LocalDateTime expiresAt;
}
