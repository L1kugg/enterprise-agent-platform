package com.enterprise.iqk.agent.workflow;

import com.enterprise.iqk.memory.TaskConclusionMemoryRecorder;
import com.enterprise.iqk.security.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 工作流引擎（引擎层）：只管状态机与留痕，零业务智能——
 * 不决定下一步做什么（编排层剧本职责），也不亲自执行动作（执行层 Agent 职责）。
 * 职责：任务/步骤生命周期管理（startTask/startStep/completeStep/completeTask/failTask）、
 * 状态转移守卫与落库、事件溯源（agent_event）、指标上报、任务查询与 VO 转换，
 * 使复杂任务全程可观测、可审计、可回放。
 */
public class AgentWorkflowEngine {

    private final AgentTaskMapper taskMapper;
    private final AgentStepMapper stepMapper;
    private final AgentEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final TaskConclusionMemoryRecorder taskConclusionMemoryRecorder;

    // ── 任务生命周期 ───────────────────────────────────────────

    /** 创建任务并落库（租户 ID 归一化），置 CREATED 后立即转入 PLANNING；modelProfile 缺省 balanced。 */
    public AgentTaskRecord startTask(String tenantId, String type, String userInput,
                                     String modelProfile, String chatId, String sessionId) {
        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        AgentTaskRecord task = AgentTaskRecord.builder()
                .taskId(taskId)
                .tenantId(TenantContext.normalize(tenantId))
                .type(type)
                .status(WorkflowState.CREATED.name())
                .userInput(userInput)
                .modelProfile(StringUtils.hasText(modelProfile) ? modelProfile : "balanced")
                .chatId(chatId)
                .sessionId(sessionId)
                .createdAt(now)
                .updatedAt(now)
                .build();
        taskMapper.insert(task);
        transitionStatus(taskId, WorkflowState.CREATED, WorkflowState.PLANNING);
        return task;
    }

    /** 以 RUNNING 状态落库一个步骤，输入快照存 inputJson，并发 STEP_STARTED 事件。 */
    public AgentStepRecord startStep(String taskId, String agentName, int stepOrder,
                                      Map<String, Object> input) {
        String stepId = "step-" + UUID.randomUUID().toString().replace("-", "");
        AgentStepRecord step = AgentStepRecord.builder()
                .stepId(stepId)
                .taskId(taskId)
                .agentName(agentName)
                .status("RUNNING")
                .stepOrder(stepOrder)
                .inputJson(toJson(input))
                .startedAt(LocalDateTime.now())
                .build();
        stepMapper.insert(step);
        emitEvent(taskId, stepId, "STEP_STARTED", Map.of("agentName", agentName, "stepOrder", stepOrder));
        return step;
    }

    /** 回写步骤结果（状态/输出/观测/token/耗时/错误），并补记 ReAct 的 thought/action/actionInput 留痕。 */
    public void completeStep(String stepId, String status, Map<String, Object> output,
                              Object observation, String thought, String action,
                              Map<String, Object> actionInput,
                              long inputTokens, long outputTokens, long latencyMs,
                              String errorMessage) {
        stepMapper.completeStep(stepId, status,
                toJson(output), toJson(observation),
                inputTokens, outputTokens, latencyMs, errorMessage);

        AgentStepRecord step = stepMapper.findByStepId(stepId);
        if (StringUtils.hasText(thought) && step != null) {
            step.setThought(thought);
            step.setAction(action);
            step.setActionInputJson(toJson(actionInput));
            stepMapper.updateById(step);
        }
        String taskId = step == null ? "unknown" : step.getTaskId();
        emitEvent(taskId, stepId, "STEP_COMPLETED",
                Map.of("status", status, "latencyMs", latencyMs));
    }

    /**
     * 任务终态落库并发 TASK_COMPLETED 事件；仅 finalStatus==DONE 时触发任务结论记忆写入，FAILED 不写。
     * 收尾 SQL 带非终态守卫：更新 0 行说明任务已被并发路径收尾（取消/回收/另一分支），
     * 此时告警跳过——不发事件、不写记忆，绝不覆盖并发方已写入的终态。
     */
    public void completeTask(String taskId, WorkflowState finalStatus, String finalOutput) {
        int updated = taskMapper.completeTask(taskId, finalStatus.name(), finalOutput);
        if (updated == 0) {
            log.warn("Task {} final status {} skipped: already terminal (lost race with concurrent finalize)",
                    taskId, finalStatus);
            return;
        }
        emitEvent(taskId, null, "TASK_COMPLETED",
                Map.of("status", finalStatus.name()));
        if (finalStatus == WorkflowState.DONE) {
            persistTaskConclusion(taskId, finalOutput);
        }
    }

    /**
     * 任务成功完成后，把结论写入 task 记忆（30 天过期、绑定 taskId）。
     * 尽力而为：记忆失败只记日志，绝不影响任务完成本身。
     */
    private void persistTaskConclusion(String taskId, String finalOutput) {
        if (!StringUtils.hasText(finalOutput)) {
            return;
        }
        try {
            AgentTaskRecord task = taskMapper.findByTaskId(taskId);
            if (task == null) {
                return;
            }
            taskConclusionMemoryRecorder.recordConclusion(
                    task.getTenantId(), taskId, task.getType(),
                    task.getUserInput(), finalOutput, task.getChatId());
        } catch (Exception ex) {
            log.warn("task conclusion memory skipped: taskId={}, reason={}", taskId, ex.toString());
        }
    }

    /**
     * 将任务置为 FAILED（final_output 记录错误信息）并发 TASK_FAILED 事件。
     * 与 completeTask 同款守卫：任务已终态（如并发分支已写 DONE）时 0 行命中，告警跳过，不覆盖。
     */
    public void failTask(String taskId, String errorMessage) {
        int updated = taskMapper.completeTask(taskId, WorkflowState.FAILED.name(), errorMessage);
        if (updated == 0) {
            log.warn("Task {} FAILED skipped: already terminal (lost race with concurrent finalize)", taskId);
            return;
        }
        emitEvent(taskId, null, "TASK_FAILED", Map.of("error", errorMessage));
    }

    /**
     * 守卫式收尾：仅当任务仍在非终态时置为 FAILED 并发 TASK_FAILED 事件，
     * 已 DONE / FAILED 的任务不被覆盖，返回是否实际置失败。
     * 供 SSE 断连取消与孤儿任务回收使用 —— 这两个路径与正常完成路径存在竞态
     * （取消信号恰在 completeTask 落库前后到达），无守卫会把 DONE 覆盖成 FAILED。
     */
    public boolean abandonTask(String taskId, String reason) {
        boolean updated = taskMapper.failIfNotTerminal(taskId, reason) > 0;
        if (updated) {
            emitEvent(taskId, null, "TASK_FAILED", Map.of("error", reason, "abandoned", true));
        }
        return updated;
    }

    // ── 状态管理 ─────────────────────────────────────────

    /**
     * 经 canTransitionTo 守卫后以 CAS 方式更新状态并发 STATE_CHANGED 事件；
     * 非法转移或库内状态与预期 from 不一致（并发分支已转移/已终态）只告警、不抛错、不落库。
     */
    public void transitionStatus(String taskId, WorkflowState from, WorkflowState to) {
        if (!from.canTransitionTo(to)) {
            log.warn("Invalid state transition: {} -> {} for task {}", from, to, taskId);
            return;
        }
        int updated = taskMapper.updateStatus(taskId, from.name(), to.name());
        if (updated == 0) {
            log.warn("State transition {} -> {} skipped for task {}: DB state differs from expected "
                    + "(concurrent transition or already terminal)", from, to, taskId);
            return;
        }
        emitEvent(taskId, null, "STATE_CHANGED",
                Map.of("from", from.name(), "to", to.name()));
    }

    /** 查询任务当前状态；任务不存在返回 null，status 非法串按 FAILED 兜底。 */
    public WorkflowState currentState(String taskId) {
        AgentTaskRecord task = taskMapper.findByTaskId(taskId);
        if (task == null) {
            return null;
        }
        try {
            return WorkflowState.valueOf(task.getStatus());
        } catch (IllegalArgumentException e) {
            return WorkflowState.FAILED;
        }
    }

    // ── 事件溯源 ───────────────────────────────────────────

    /** 追加一条事件到 agent_event（事件溯源）；写库失败仅记日志，绝不影响主流程。 */
    public void emitEvent(String taskId, String stepId, String eventType, Map<String, Object> payload) {
        try {
            AgentEventRecord event = AgentEventRecord.builder()
                    .eventId("evt-" + UUID.randomUUID().toString().replace("-", ""))
                    .taskId(taskId)
                    .stepId(stepId)
                    .eventType(eventType)
                    .payloadJson(toJson(payload))
                    .createdAt(LocalDateTime.now())
                    .build();
            eventMapper.insert(event);
        } catch (Exception e) {
            log.error("Failed to persist agent event: taskId={}, type={}", taskId, eventType, e);
        }
    }

    // ── 指标 ──────────────────────────────────────────────────

    /** 上报步骤执行指标（agent.workflow.step.latency / step.count，按 agent+status 打标）。 */
    public void recordStepMetrics(String agentName, String status, long latencyMs) {
        Timer.builder("agent.workflow.step.latency")
                .description("Step execution latency")
                .tag("agent", agentName)
                .tag("status", status)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);

        Counter.builder("agent.workflow.step.count")
                .description("Step execution count")
                .tag("agent", agentName)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    /** 上报任务执行指标（agent.workflow.task.latency / task.count，按 type+status 打标）。 */
    public void recordTaskMetrics(String type, String status, long latencyMs) {
        Timer.builder("agent.workflow.task.latency")
                .description("Task execution latency")
                .tag("type", type)
                .tag("status", status)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);

        Counter.builder("agent.workflow.task.count")
                .description("Task execution count")
                .tag("type", type)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    // ── 查询 ────────────────────────────────────────────────────

    /** 查询单个任务详情（含步骤与事件）；租户不匹配或不存在返回 null。 */
    public WorkflowTaskVO getTask(String tenantId, String taskId) {
        AgentTaskRecord task = taskMapper.findByTenantAndTaskId(TenantContext.normalize(tenantId), taskId);
        if (task == null) {
            return null;
        }
        List<AgentStepRecord> steps = stepMapper.findByTaskId(taskId);
        List<AgentEventRecord> events = eventMapper.findByTaskId(taskId);
        return toTaskVO(task, steps, events);
    }

    /** 分页查询租户任务列表（只带步骤、不带事件）；page 从 1 起。 */
    public List<WorkflowTaskVO> listTasks(String tenantId, int page, int pageSize) {
        long offset = (long) (Math.max(1, page) - 1) * pageSize;
        return taskMapper.findByTenant(TenantContext.normalize(tenantId), offset, pageSize)
                .stream()
                .map(t -> toTaskVO(t, stepMapper.findByTaskId(t.getTaskId()),
                        Collections.emptyList()))
                .toList();
    }

    /** 查询任务事件列表（时间升序，用于回放）；任务不属于该租户时返回空列表。 */
    public List<WorkflowEventVO> getTaskEvents(String tenantId, String taskId) {
        if (taskMapper.findByTenantAndTaskId(TenantContext.normalize(tenantId), taskId) == null) {
            return Collections.emptyList();
        }
        return eventMapper.findByTaskId(taskId).stream()
                .map(this::toEventVO)
                .toList();
    }

    // ── 转换辅助方法 ───────────────────────────────────────

    /** AgentTaskRecord → WorkflowTaskVO 聚合转换 */
    private WorkflowTaskVO toTaskVO(AgentTaskRecord t, List<AgentStepRecord> steps,
                                     List<AgentEventRecord> events) {
        return WorkflowTaskVO.builder()
                .taskId(t.getTaskId())
                .tenantId(t.getTenantId())
                .type(t.getType())
                .status(t.getStatus())
                .userInput(t.getUserInput())
                .finalOutput(t.getFinalOutput())
                .modelProfile(t.getModelProfile())
                .chatId(t.getChatId())
                .sessionId(t.getSessionId())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .steps(steps.stream().map(this::toStepVO).toList())
                .events(events.stream().map(this::toEventVO).toList())
                .build();
    }

    /** AgentStepRecord → WorkflowStepVO 转换（观测 JSON 容缺，token/耗时空值补 0） */
    private WorkflowStepVO toStepVO(AgentStepRecord s) {
        return WorkflowStepVO.builder()
                .stepId(s.getStepId())
                .taskId(s.getTaskId())
                .agentName(s.getAgentName())
                .status(s.getStatus())
                .stepOrder(s.getStepOrder())
                .thought(s.getThought())
                .action(s.getAction())
                .actionInput(parseJsonMap(s.getActionInputJson()))
                .observation(s.getObservationJson() != null ? parseJsonMap(s.getObservationJson()) : null)
                .modelProfile(s.getModelProfile())
                .inputTokens(s.getInputTokens() != null ? s.getInputTokens() : 0)
                .outputTokens(s.getOutputTokens() != null ? s.getOutputTokens() : 0)
                .latencyMs(s.getLatencyMs() != null ? s.getLatencyMs() : 0)
                .errorMessage(s.getErrorMessage())
                .startedAt(s.getStartedAt())
                .endedAt(s.getEndedAt())
                .build();
    }

    /** AgentEventRecord → WorkflowEventVO 转换 */
    private WorkflowEventVO toEventVO(AgentEventRecord e) {
        return WorkflowEventVO.builder()
                .eventId(e.getEventId())
                .taskId(e.getTaskId())
                .stepId(e.getStepId())
                .eventType(e.getEventType())
                .payload(parseJsonMap(e.getPayloadJson()))
                .createdAt(e.getCreatedAt())
                .build();
    }

    /** JSON 串反序列化为 Map；空串或解析失败返回空 Map */
    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }

    /** 序列化为 JSON；null 入参返回 null，失败兜底 "{}" */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
