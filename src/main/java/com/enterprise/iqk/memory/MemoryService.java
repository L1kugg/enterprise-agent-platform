package com.enterprise.iqk.memory;

import com.enterprise.iqk.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 记忆子系统统一入口：short / long / task / fact 四层记忆的写入、
 * 按层查询、召回快照构建与事件留痕。
 *
 * 设计要点 —— 写入时机决定层级：
 * 1. short：对话轮次完成写入，24 小时过期；
 * 2. task：工作流到达 DONE 写入结论，绑定 taskId，30 天过期；
 * 3. fact：RAG 证据置信度达标（>= 0.7）写入，永久、按租户共享；
 * 4. long：异步画像提取命中写入，永久。
 * 查询一律过滤已过期条目（expires_at 为空视为永久）；
 * 写入失败由调用方（各 Recorder）以 best-effort 方式兜底，不阻塞主链路。
 */
public class MemoryService {

    private final MemoryItemMapper itemMapper;
    private final MemoryEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    // ── 保存 ─────────────────────────────────────────────────────

    /** 保存会话级 short 记忆：24 小时过期；内容为空白时返回 null 不落库。 */
    public MemoryItemRecord saveShortMemory(String tenantId, String userId,
                                             String content, String source) {
        return save(tenantId, userId, "short", content, source, null, 0.9,
                LocalDateTime.now().plusHours(24));
    }

    /** 保存用户画像 long 记忆：永久有效；内容为空白时返回 null 不落库。 */
    public MemoryItemRecord saveLongMemory(String tenantId, String userId,
                                            String content, String source) {
        return save(tenantId, userId, "long", content, source, null, 0.85, null);
    }

    /** 保存任务结论 task 记忆：绑定 taskId、30 天过期，source 记为 task:{taskId}。 */
    public MemoryItemRecord saveTaskMemory(String tenantId, String userId,
                                            String content, String taskId) {
        return save(tenantId, userId, "task", content, "task:" + taskId, taskId, 0.9,
                LocalDateTime.now().plusDays(30));
    }

    /** 保存租户级 fact 记忆：永久有效，置信度由调用方（RAG 证据综合分）给定。 */
    public MemoryItemRecord saveFactMemory(String tenantId, String userId,
                                            String content, String source,
                                            double confidence) {
        return save(tenantId, userId, "fact", content, source, null,
                confidence, null);
    }

    /** 各层写入的公共落库路径：生成 memoryId、规范化租户、内容去空白，插入后记 CREATE 事件。 */
    private MemoryItemRecord save(String tenantId, String userId, String type,
                                   String content, String source, String sourceTaskId,
                                   double confidence, LocalDateTime expiresAt) {
        if (!StringUtils.hasText(content)) return null;
        String memoryId = "mem-" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        MemoryItemRecord item = MemoryItemRecord.builder()
                .memoryId(memoryId)
                .tenantId(TenantContext.normalize(tenantId))
                .userId(userId)
                .type(type)
                .content(content.trim())
                .source(source)
                .sourceTaskId(sourceTaskId)
                .confidence(confidence)
                .expiresAt(expiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
        itemMapper.insert(item);
        emitEvent(memoryId, "CREATE", "saved " + type + " memory");
        return item;
    }

    // ── 查询 ─────────────────────────────────────────────────────

    /** 查询某用户键下未过期的 short 记忆，按创建时间倒序取前 limit 条。 */
    public List<MemoryItemRecord> queryShortMemory(String tenantId, String userId, int limit) {
        return itemMapper.findByUserAndType(TenantContext.normalize(tenantId), userId, "short", limit);
    }

    /** 查询某用户键下未过期的 long 记忆，按创建时间倒序取前 limit 条。 */
    public List<MemoryItemRecord> queryLongMemory(String tenantId, String userId, int limit) {
        return itemMapper.findByUserAndType(TenantContext.normalize(tenantId), userId, "long", limit);
    }

    /** 查询租户内绑定指定 taskId 的未过期 task 记忆（用于召回早前任务的结论）。 */
    public List<MemoryItemRecord> queryTaskMemory(String tenantId, String taskId) {
        return itemMapper.findByTenantAndTaskId(TenantContext.normalize(tenantId), taskId);
    }

    /** 查询租户内最近的未过期 task 结论（写入时置信度 0.9，复用 0.7 复验门槛），供新任务拆题时参考。 */
    public List<MemoryItemRecord> queryRecentTaskMemories(String tenantId, int limit) {
        return itemMapper.findByTypeAndConfidence(
                TenantContext.normalize(tenantId), "task", 0.7, limit);
    }

    /** 查询租户内置信度不低于 minConfidence 的未过期 fact 记忆（召回侧复验门槛 0.7）。 */
    public List<MemoryItemRecord> queryFactMemory(String tenantId, double minConfidence, int limit) {
        return itemMapper.findByTypeAndConfidence(
                TenantContext.normalize(tenantId), "fact", minConfidence, limit);
    }

    /**
     * 构建进入生成上下文的记忆快照（全量三层）：近期会话要点 + 用户画像 + 高置信事实。
     * 召回即留痕 —— 每条被召回的记忆都会写入 USE 事件，
     * 使记忆从写入、召回到失效的全程都可以在 memory_event 里追溯。
     */
    public MemoryContextSnapshot buildContext(String tenantId, String userId) {
        return buildContext(tenantId, userId, true);
    }

    /**
     * 可裁剪变体：includeShort=false 时跳过 short 层的查询与拼装。
     * 供已挂 ChatMemory advisor 的链路使用 —— 会话内近况已由 advisor
     * 保真注入，short 摘要再进一次会让同一信息双份进入 prompt。
     */
    public MemoryContextSnapshot buildContext(String tenantId, String userId, boolean includeShort) {
        List<MemoryItemRecord> shortMem = includeShort ? queryShortMemory(tenantId, userId, 5) : List.of();
        List<MemoryItemRecord> longMem = queryLongMemory(tenantId, userId, 10);
        List<MemoryItemRecord> facts = queryFactMemory(tenantId, 0.7, 5);

        List<MemoryItemRecord> recalled = new java.util.ArrayList<>(shortMem);
        recalled.addAll(longMem);
        recalled.addAll(facts);
        recalled.forEach(m -> emitEvent(m.getMemoryId(), "USE", "recalled into generation context"));

        StringBuilder context = new StringBuilder();
        if (!longMem.isEmpty()) {
            context.append("用户长期记忆:\n");
            for (MemoryItemRecord m : longMem) {
                context.append("- ").append(m.getContent()).append("\n");
            }
            context.append("\n");
        }
        if (!shortMem.isEmpty()) {
            context.append("近期对话要点:\n");
            for (MemoryItemRecord m : shortMem) {
                context.append("- ").append(m.getContent()).append("\n");
            }
            context.append("\n");
        }
        if (!facts.isEmpty()) {
            context.append("可信事实:\n");
            for (MemoryItemRecord m : facts) {
                context.append("- ").append(m.getContent()).append("\n");
            }
        }
        return new MemoryContextSnapshot(context.toString(), shortMem, longMem, facts);
    }

    // ── 维护 ──────────────────────────────────────────────────────

    /** 物理删除所有 expires_at 已过期的记忆条目；long / fact 永久保存不受影响。 */
    @Scheduled(cron = "0 0 3 * * ?") // 每天凌晨 3 点执行
    public void cleanExpiredMemories() {
        int deleted = itemMapper.deleteExpired();
        if (deleted > 0) {
            log.info("Cleaned {} expired memory items", deleted);
        }
    }

    /** 删除租户内指定记忆并记 DELETE 事件；记忆不存在或不属于该租户时静默返回。 */
    public void deleteMemory(String tenantId, String memoryId) {
        MemoryItemRecord item = itemMapper.findByTenantAndMemoryId(TenantContext.normalize(tenantId), memoryId);
        if (item != null) {
            itemMapper.deleteById(item.getId());
            emitEvent(memoryId, "DELETE", "manual deletion");
        }
    }

    // ── 事件 ─────────────────────────────────────────────────────

    /** 写入一条记忆事件（CREATE / USE / DELETE 等，best-effort）：失败只记日志，绝不影响主流程。 */
    private void emitEvent(String memoryId, String action, String reason) {
        try {
            MemoryEventRecord event = MemoryEventRecord.builder()
                    .eventId("mevt-" + UUID.randomUUID().toString().replace("-", ""))
                    .memoryId(memoryId)
                    .action(action)
                    .reason(reason)
                    .createdAt(LocalDateTime.now())
                    .build();
            eventMapper.insert(event);
        } catch (Exception e) {
            log.error("Failed to persist memory event", e);
        }
    }

    /** 查询指定记忆的事件链（按时间倒序）；记忆不属于该租户时返回空列表，不暴露存在性。 */
    public List<MemoryEventRecord> getEvents(String tenantId, String memoryId) {
        MemoryItemRecord item = itemMapper.findByTenantAndMemoryId(TenantContext.normalize(tenantId), memoryId);
        return item == null ? Collections.emptyList() : eventMapper.findByMemoryId(memoryId);
    }

    /** 召回快照：拼接好的上下文文本 + 按层返回的原始记忆记录（供 memoryUsed 观测上报）。 */
    public record MemoryContextSnapshot(String contextText,
                                         List<MemoryItemRecord> shortMemories,
                                         List<MemoryItemRecord> longMemories,
                                         List<MemoryItemRecord> facts) {

        /** 观测标签：把注入上下文的记忆整理为 "type: 内容摘要"，供响应的 memoryUsed 上报。 */
        public List<String> usedLabels() {
            List<String> labels = new ArrayList<>();
            shortMemories.forEach(m -> labels.add(label("short", m)));
            longMemories.forEach(m -> labels.add(label("long", m)));
            facts.forEach(m -> labels.add(label("fact", m)));
            return labels;
        }

        /** 单条标签：内容超 80 字符截断加省略号。 */
        private static String label(String type, MemoryItemRecord memory) {
            String content = memory.getContent() == null ? "" : memory.getContent().replaceAll("\\s+", " ").trim();
            return type + ": " + (content.length() <= 80 ? content : content.substring(0, 80) + "…");
        }
    }
}
