package com.enterprise.iqk.controller;

import com.enterprise.iqk.agent.workflow.WorkflowGraphRuntime;
import com.enterprise.iqk.domain.vo.Result;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.util.ConversationIdHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 工作流图任务运行入口（V36）：把上线的工作流"运行为任务"，异步执行可审批可回放。
 * 与 /platform/workflows/{id}/test-run（同步试运行、ADMIN）互补：本端点面向业务运行，
 * 权限与会话写侧同口径（PERM_CHAT_WRITE），任务详情复用 /ai/workflow/tasks/{taskId}。
 */
@RestController
@RequestMapping("/ai/workflows")
@RequiredArgsConstructor
public class WorkflowRunController {

    private final WorkflowGraphRuntime workflowGraphRuntime;

    /** 受理一次运行：同步落库受理即返回 taskId（详情/事件走 /ai/workflow/tasks），输入 Map 作为初始变量。 */
    @PostMapping("/{id}/run")
    public Result run(@PathVariable("id") Long id,
                      @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = body == null ? Map.of() : new java.util.LinkedHashMap<>(body);
        Object chatIdValue = input.remove("chatId");
        String chatId = chatIdValue instanceof String text && !text.isBlank()
                ? text
                : ConversationIdHelper.build("workflow", UUID.randomUUID().toString()
                        .replace("-", "").substring(0, 12));
        String taskId = workflowGraphRuntime.run(id, TenantContext.currentTenantId(), chatId, input);
        Result result = Result.ok();
        result.setData(Map.of("taskId", taskId, "chatId", chatId));
        return result;
    }
}
