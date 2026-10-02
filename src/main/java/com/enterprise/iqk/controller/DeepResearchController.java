package com.enterprise.iqk.controller;

import com.enterprise.iqk.agent.research.DeepResearchService;
import com.enterprise.iqk.agent.research.ResearchQueueFullException;
import com.enterprise.iqk.agent.research.ResearchTaskRequest;
import com.enterprise.iqk.agent.workflow.AgentWorkflowEngine;
import com.enterprise.iqk.agent.workflow.WorkflowEventVO;
import com.enterprise.iqk.agent.workflow.WorkflowTaskVO;
import com.enterprise.iqk.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Deep Research", description = "多Agent深度研究API")
@RestController
@RequestMapping("/ai/research")
@RequiredArgsConstructor
/** 深度研究 API：创建任务（异步受理 202）、任务详情轮询、事件流与报告查询（租户隔离）。 */
public class DeepResearchController {

    private final DeepResearchService deepResearchService;
    private final AgentWorkflowEngine workflowEngine;

    @Operation(summary = "创建深度研究任务（异步受理）")
    @PostMapping("/tasks")
    /**
     * 创建深度研究任务并立即返回 202 + 任务号（report 为空，状态 PLANNING）；
     * 研究在后台执行，进度经 GET /tasks/{taskId} 轮询，报告经 GET /tasks/{taskId}/report 获取。
     * 队列打满返回 429。
     */
    public ResponseEntity<?> createResearch(@RequestBody ResearchTaskRequest request) {
        try {
            DeepResearchService.DeepResearchResult result = deepResearchService.createResearch(
                    request, TenantContext.currentTenantId());
            return ResponseEntity.accepted().body(result);
        } catch (ResearchQueueFullException e) {
            return ResponseEntity.status(429).body(Map.of("ok", 0, "msg", e.getMessage()));
        }
    }

    @Operation(summary = "查询研究任务详情")
    @GetMapping("/tasks/{taskId}")
    /** 查询研究任务详情（含步骤与事件）；不存在返回 404 */
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        WorkflowTaskVO task = workflowEngine.getTask(TenantContext.currentTenantId(), taskId);
        if (task == null) {
            return ResponseEntity.status(404).body(Map.of("ok", 0, "msg", "task not found"));
        }
        return ResponseEntity.ok(task);
    }

    @Operation(summary = "查询研究任务事件流")
    @GetMapping("/tasks/{taskId}/events")
    /** 查询研究任务事件流（时间升序，事件溯源回放） */
    public ResponseEntity<List<WorkflowEventVO>> getEvents(@PathVariable String taskId) {
        return ResponseEntity.ok(workflowEngine.getTaskEvents(TenantContext.currentTenantId(), taskId));
    }

    @Operation(summary = "查询研究任务报告")
    @GetMapping("/tasks/{taskId}/report")
    /** 查询研究任务最终报告；任务不存在返回 404 */
    public ResponseEntity<?> getReport(@PathVariable String taskId) {
        WorkflowTaskVO task = workflowEngine.getTask(TenantContext.currentTenantId(), taskId);
        if (task == null) {
            return ResponseEntity.status(404).body(Map.of("ok", 0, "msg", "task not found"));
        }
        return ResponseEntity.ok(Map.of("taskId", task.getTaskId(), "report", task.getFinalOutput()));
    }
}
