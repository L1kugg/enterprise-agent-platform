package com.enterprise.iqk.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 记忆条目（memory_item 表）：四层记忆的统一存储，
 * 靠 type 字段区分层级，靠 expiresAt 控制生命周期。
 */
@TableName("memory_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryItemRecord {
    /** 数据库自增主键（内部使用） */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 记忆业务 ID（mem-{uuid}），memory_event 表通过它关联，一条记忆的一生可串成事件链 */
    private String memoryId;
    /** 租户 ID，所有记忆查询的第一道过滤条件，保证跨租户隔离 */
    private String tenantId;
    /** 归属主体：当前实现传入会话 ID（chatId），即以会话为召回单位；字段名保留 userId 以支持未来按用户归集 */
    private String userId;
    /** 记忆层级 short | long | task | fact（数据库实际存储的字符串值，勿改为中文） */
    private String type;
    /** 记忆正文：写入前已按层级截断（会话要点 Q/A 各 200/400 字符、任务结论 600 字符） */
    private String content;
    /** 来源标记，用于追溯这条记忆从哪来：task:{taskId} / rag:{sourceType}:{title} / extract:chat:{chatId} */
    private String source;
    /** 仅 task 层使用：绑定工作流任务 ID，召回与过期都按它限定在任务作用域内 */
    private String sourceTaskId;
    /** 置信度 0~1：fact 层写入时继承证据分数（门槛 0.7），召回时还会再校验一次 */
    private Double confidence;
    /** 扩展元数据 JSON（预留字段） */
    private String metadataJson;
    /** 过期时间：short=+24h、task=+30d；long/fact 为空表示永久。每天凌晨 3 点定时清理 */
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
