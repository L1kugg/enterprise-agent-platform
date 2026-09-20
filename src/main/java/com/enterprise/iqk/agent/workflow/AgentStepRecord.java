package com.enterprise.iqk.agent.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("agent_step")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * agent_step 表实体：任务内一个步骤（一轮 ReAct 或一个编排阶段）的执行留痕。
 * 由引擎层 startStep 创建（RUNNING）并 completeStep 回填结果，
 * thought/action/observation 三元组支撑复杂任务的审计与回放。
 */
public class AgentStepRecord {
    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 步骤业务 ID（step-uuid） */
    private String stepId;
    /** 所属任务 ID */
    private String taskId;
    /** 执行 Agent 名（planner / ResearchPlanner / RagResearchAgent / ReportWriter） */
    private String agentName;
    /** 步骤状态（RUNNING / COMPLETED / FAILED） */
    private String status;
    /** 步骤序号（任务内递增） */
    private Integer stepOrder;
    /** 输入参数 JSON */
    private String inputJson;
    /** 输出结果 JSON */
    private String outputJson;
    /** ReAct 思考内容 */
    private String thought;
    /** ReAct 动作名 */
    private String action;
    /** ReAct 动作参数 JSON */
    private String actionInputJson;
    /** 动作执行观测结果 JSON */
    private String observationJson;
    /** 该步骤使用的模型档位 */
    private String modelProfile;
    /** 输入 token 数 */
    private Long inputTokens;
    /** 输出 token 数 */
    private Long outputTokens;
    /** 执行耗时（毫秒） */
    private Long latencyMs;
    /** 失败原因（成功为 null） */
    private String errorMessage;
    /** 开始时间 */
    private LocalDateTime startedAt;
    /** 结束时间 */
    private LocalDateTime endedAt;
}
