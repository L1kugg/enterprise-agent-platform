package com.enterprise.iqk.controller;

import com.enterprise.iqk.memory.MemoryEventRecord;
import com.enterprise.iqk.memory.MemoryItemRecord;
import com.enterprise.iqk.memory.MemoryService;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 记忆查询与管理端点（补齐 README 承诺的 /ai/memory 接口）。
 *
 * 租户隔离约定：tenantId 一律取自请求上下文（鉴权过滤器写入 MDC），
 * 不接受前端传入，跨租户读取在服务层返回空结果。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/memory")
public class MemoryController {

    /** fact 记忆查询的默认置信度下限，与召回门槛保持一致。 */
    private static final double DEFAULT_FACT_MIN_CONFIDENCE = 0.7;

    private final MemoryService memoryService;

    /**
     * 查询记忆：不传 type 返回三类聚合；传 type 只返回该类。
     *
     * @param userId 记忆的用户键（认证主体；匿名会话的历史数据为 chatId）
     * @param type   short / long / fact，可选
     */
    @GetMapping("/query")
    public MemoryQueryResponse query(
            @RequestParam("userId") String userId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        String tenantId = TenantContext.currentTenantId();
        MemoryQueryResponse response = new MemoryQueryResponse();
        if (!StringUtils.hasText(type) || "short".equals(type)) {
            response.setShortMemories(memoryService.queryShortMemory(tenantId, userId, limit));
        }
        if (!StringUtils.hasText(type) || "long".equals(type)) {
            response.setLongMemories(memoryService.queryLongMemory(tenantId, userId, limit));
        }
        if (!StringUtils.hasText(type) || "fact".equals(type)) {
            response.setFacts(memoryService.queryFactMemory(tenantId,
                    DEFAULT_FACT_MIN_CONFIDENCE, limit));
        }
        return response;
    }

    /**
     * 查询任务关联记忆：按 taskId 精确召回该任务沉淀的结论（30 天内），
     * 供任务详情页 / 重跑前查看"上次研究到什么"。
     */
    @GetMapping("/task/{taskId}")
    public List<MemoryItemRecord> taskMemories(@PathVariable("taskId") String taskId) {
        return memoryService.queryTaskMemory(TenantContext.currentTenantId(), taskId);
    }

    /**
     * 查询单条记忆的完整事件链（CREATE / USE / DELETE ...），
     * 用于追溯"这条记忆从写入到召回经历了什么"。
     * 服务层已按租户隔离：不属于当前租户的记忆返回空列表，不暴露存在性。
     */
    @GetMapping("/{memoryId}/events")
    public List<MemoryEventRecord> events(@PathVariable("memoryId") String memoryId) {
        return memoryService.getEvents(TenantContext.currentTenantId(), memoryId);
    }

    /**
     * 手动保存记忆（运维/调试入口）。type 决定落层与生命周期：
     * short 24h / long 永久 / task 30 天且需 taskId / fact 需置信度。
     */
    @PostMapping("/save")
    public MemoryItemRecord save(@RequestBody MemorySaveRequest request) {
        if (request == null || !StringUtils.hasText(request.type())
                || !StringUtils.hasText(request.content())) {
            throw new IllegalArgumentException("type 和 content 不能为空");
        }
        String tenantId = TenantContext.currentTenantId();
        return switch (request.type()) {
            case "short" -> memoryService.saveShortMemory(
                    tenantId, request.userId(), request.content(), "manual");
            case "long" -> memoryService.saveLongMemory(
                    tenantId, request.userId(), request.content(), "manual");
            case "task" -> {
                if (!StringUtils.hasText(request.taskId())) {
                    throw new IllegalArgumentException("task 记忆必须提供 taskId");
                }
                yield memoryService.saveTaskMemory(
                        tenantId, request.userId(), request.content(), request.taskId());
            }
            case "fact" -> memoryService.saveFactMemory(
                    tenantId, null, request.content(), "manual",
                    request.confidence() == null ? DEFAULT_FACT_MIN_CONFIDENCE : request.confidence());
            default -> throw new IllegalArgumentException("不支持的记忆类型: " + request.type());
        };
    }

    /** 删除租户内指定记忆；不存在或不属于当前租户时静默返回。 */
    @DeleteMapping("/{memoryId}")
    public void delete(@PathVariable("memoryId") String memoryId) {
        memoryService.deleteMemory(TenantContext.currentTenantId(), memoryId);
    }

    /** 记忆保存请求体。 */
    public record MemorySaveRequest(String type, String userId, String taskId,
                                    String content, Double confidence) {
    }

    /** 记忆聚合查询响应。 */
    @lombok.Data
    public static class MemoryQueryResponse {
        private List<MemoryItemRecord> shortMemories = List.of();
        private List<MemoryItemRecord> longMemories = List.of();
        private List<MemoryItemRecord> facts = List.of();
    }
}
