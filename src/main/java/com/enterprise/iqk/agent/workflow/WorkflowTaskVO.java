package com.enterprise.iqk.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/** 工作流任务详情 VO：AgentTaskRecord 的对外投影，聚合步骤列表与事件列表（均升序）。 */
public class WorkflowTaskVO {
    private String taskId;
    private String tenantId;
    private String type;
    private String status;
    private String userInput;
    private String finalOutput;
    private String modelProfile;
    private String chatId;
    private String sessionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<WorkflowStepVO> steps;
    private List<WorkflowEventVO> events;
}
