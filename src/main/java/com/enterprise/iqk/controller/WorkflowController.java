package com.enterprise.iqk.controller;

import com.enterprise.iqk.agent.workflow.AgentWorkflowEngine;
import com.enterprise.iqk.agent.workflow.WorkflowEventVO;
import com.enterprise.iqk.agent.workflow.WorkflowReactAgentService;
import com.enterprise.iqk.agent.workflow.WorkflowTaskVO;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Tag(name = "Agent Workflow", description = "多Agent工作流任务管理")
@RestController
@RequestMapping("/ai/workflow")
@RequiredArgsConstructor
/** 工作流任务 API：ReAct 同步/流式入口，以及任务详情、事件、列表查询（租户隔离）。 */
public class WorkflowController {

    private final WorkflowReactAgentService workflowReactAgentService;
    private final AgentWorkflowEngine workflowEngine;

    @Operation(summary = "同步ReAct工作流")
    @PostMapping("/react/chat")
    /** 同步 ReAct 工作流：阻塞执行，返回完整答案+轨迹 */
    public ResponseEntity<ReactChatResponseVO> reactChat(@RequestBody ReactChatRequestVO request) {
        return ResponseEntity.ok(workflowReactAgentService.chat(request));
    }

    @Operation(summary = "流式ReAct工作流 (SSE)")
    @PostMapping(value = "/react/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    /** 流式 ReAct 工作流（SSE）：trace→token→done 事件流 */
    public Flux<String> reactChatStream(@RequestBody ReactChatRequestVO request) {
        return workflowReactAgentService.stream(request);
    }

    @Operation(summary = "查询工作流任务详情")
    @GetMapping("/tasks/{taskId}")
    /** 查询工作流任务详情（含步骤与事件）；不存在返回 404 */
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        WorkflowTaskVO task = workflowEngine.getTask(TenantContext.currentTenantId(), taskId);
        if (task == null) {
            return ResponseEntity.status(404).body(Map.of("ok", 0, "msg", "任务不存在"));
        }
        return ResponseEntity.ok(task);
    }

    @Operation(summary = "查询工作流任务事件列表")
    @GetMapping("/tasks/{taskId}/events")
    /** 查询工作流任务事件列表（时间升序，可回放） */
    public ResponseEntity<List<WorkflowEventVO>> getTaskEvents(@PathVariable String taskId) {
        return ResponseEntity.ok(workflowEngine.getTaskEvents(TenantContext.currentTenantId(), taskId));
    }

    @Operation(summary = "查询租户工作流任务列表")
    @GetMapping("/tasks")
    /** 分页查询当前租户的工作流任务列表 */
    public ResponseEntity<List<WorkflowTaskVO>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(workflowEngine.listTasks(TenantContext.currentTenantId(), page, pageSize));
    }
}
