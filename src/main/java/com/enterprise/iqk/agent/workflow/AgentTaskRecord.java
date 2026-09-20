package com.enterprise.iqk.agent.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("agent_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * agent_task 表实体：一次工作流任务的全量留痕（谁发起、什么输入、走到哪步、最终产出）。
 * status 列存 WorkflowState 名，由引擎层负责写入与守卫；
 * finalOutput 在 DONE 时为结论/报告、FAILED 时为错误信息。
 */
public class AgentTaskRecord {
    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 任务业务 ID（task-uuid，对外暴露的标识） */
    private String taskId;
    /** 归属租户 ID */
    private String tenantId;
    /** 任务类型（REACT / REACT_STREAM / DEEP_RESEARCH） */
    private String type;
    /** 当前状态（WorkflowState 枚举名） */
    private String status;
    /** 用户原始输入（问题或研究主题） */
    private String userInput;
    /** 最终输出（DONE 为答案/报告，FAILED 为错误信息） */
    private String finalOutput;
    /** 模型档位（缺省 balanced） */
    private String modelProfile;
    /** 关联会话 ID */
    private String chatId;
    /** 关联会话流 ID */
    private String sessionId;
    /** 扩展元数据 JSON */
    private String metadataJson;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
