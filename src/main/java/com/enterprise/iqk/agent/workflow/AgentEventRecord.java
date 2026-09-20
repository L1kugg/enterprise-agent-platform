package com.enterprise.iqk.agent.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("agent_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * agent_event 表实体：任务/步骤级事件记录（事件溯源）。
 * 引擎层在关键节点追加写入，事后可按时间序回放任务的完整执行过程。
 */
public class AgentEventRecord {
    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 事件业务 ID（evt-uuid） */
    private String eventId;
    /** 所属任务 ID */
    private String taskId;
    /** 关联步骤 ID（任务级事件为 null） */
    private String stepId;
    /** 事件类型（STEP_STARTED / STEP_COMPLETED / STATE_CHANGED / TASK_COMPLETED / TASK_FAILED） */
    private String eventType;
    /** 事件负载 JSON（如状态迁移前后值、耗时等） */
    private String payloadJson;
    /** 产生时间 */
    private LocalDateTime createdAt;
}
