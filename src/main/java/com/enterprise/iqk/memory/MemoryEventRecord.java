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
 * 记忆事件（memory_event 表）：记忆操作的事件溯源记录。
 * 同一 memoryId 的事件按时间排列，即可回放一条记忆
 * 从写入（CREATE）、召回（USE）到删除（DELETE）的完整生命周期。
 */
@TableName("memory_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEventRecord {
    /** 数据库自增主键（内部使用） */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 事件业务 ID（mevt-{uuid}） */
    private String eventId;
    /** 关联 memory_item.memory_id */
    private String memoryId;
    /** 事件类型 CREATE | UPDATE | DELETE | EXPIRE | HIT | USE（数据库实际存储的字符串值，勿改为中文） */
    private String action;
    /** 事件说明，如 "saved short memory"、"recalled into generation context" */
    private String reason;
    /** 扩展元数据 JSON（预留字段） */
    private String metadataJson;
    /** 事件发生时间 */
    private LocalDateTime createdAt;
}
