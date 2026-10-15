package com.enterprise.iqk.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 记忆条目表 Mapper：自定义查询一律过滤已过期（expires_at 为空视为永久）
 * 与软失效（stale=1，证据源被删除等治理动作）条目。
 */
@Mapper
public interface MemoryItemMapper extends BaseMapper<MemoryItemRecord> {

    @Select("""
            SELECT * FROM memory_item
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND type = #{type}
              AND stale = 0
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<MemoryItemRecord> findByUserAndType(@Param("tenantId") String tenantId,
                                              @Param("userId") String userId,
                                              @Param("type") String type,
                                              @Param("limit") int limit);

    @Select("""
            SELECT * FROM memory_item
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND stale = 0
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<MemoryItemRecord> findByUser(@Param("tenantId") String tenantId,
                                       @Param("userId") String userId,
                                       @Param("limit") int limit);

    @Select("""
            SELECT * FROM memory_item
            WHERE tenant_id = #{tenantId}
              AND source_task_id = #{taskId}
              AND stale = 0
              AND (expires_at IS NULL OR expires_at > NOW())
            """)
    List<MemoryItemRecord> findByTenantAndTaskId(@Param("tenantId") String tenantId,
                                                 @Param("taskId") String taskId);

    @Select("""
            SELECT * FROM memory_item
            WHERE tenant_id = #{tenantId}
              AND type = #{type}
              AND confidence >= #{minConfidence}
              AND stale = 0
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<MemoryItemRecord> findByTypeAndConfidence(@Param("tenantId") String tenantId,
                                                    @Param("type") String type,
                                                    @Param("minConfidence") double minConfidence,
                                                    @Param("limit") int limit);

    /** 详情/事件链查询不过滤 stale：软失效的记忆仍可在 memory_event 里追溯一生。 */
    @Select("""
            SELECT * FROM memory_item
            WHERE tenant_id = #{tenantId}
              AND memory_id = #{memoryId}
              AND (expires_at IS NULL OR expires_at > NOW())
            LIMIT 1
            """)
    MemoryItemRecord findByTenantAndMemoryId(@Param("tenantId") String tenantId,
                                             @Param("memoryId") String memoryId);

    /** 待级联失效的 fact 记忆业务 ID（用于补写 INVALIDATE 事件），只取未失效的。 */
    @Select("""
            SELECT memory_id FROM memory_item
            WHERE tenant_id = #{tenantId}
              AND type = 'fact'
              AND source_chat_id = #{chatId}
              AND stale = 0
            """)
    List<String> findActiveFactMemoryIdsByChat(@Param("tenantId") String tenantId,
                                               @Param("chatId") String chatId);

    /** 按证据源批次软失效 fact 记忆：文档删除钩子调用，返回失效行数。 */
    @Update("""
            UPDATE memory_item
            SET stale = 1, updated_at = NOW()
            WHERE tenant_id = #{tenantId}
              AND type = 'fact'
              AND source_chat_id = #{chatId}
              AND stale = 0
            """)
    int markFactsStaleByChat(@Param("tenantId") String tenantId,
                             @Param("chatId") String chatId);

    @Delete("DELETE FROM memory_item WHERE expires_at IS NOT NULL AND expires_at < NOW()")
    int deleteExpired();
}
