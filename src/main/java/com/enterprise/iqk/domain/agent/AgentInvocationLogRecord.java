package com.enterprise.iqk.domain.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 智能体调用日志：每次智能体会话/调试落一条，满足会话可追溯（FR-R1-01）并作为 R2.2 看板数据源。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_invocation_log")
public class AgentInvocationLogRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long agentId;
    private String userId;
    private String chatId;
    /** platform=真实会话，debug=工作台调试 */
    private String channel;
    /** SUCCESS | BLOCKED | ERROR */
    private String status;
    private String modelName;
    private Boolean fallbackApplied;
    private Long latencyMs;
    private String promptExcerpt;
    private String errorSummary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
