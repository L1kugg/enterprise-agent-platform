package com.enterprise.iqk.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

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

    /** KB 圈定版：只召回无标记全局事实或知识库 ID 数组与圈定有交集的事实。 */
    @Select("""
            SELECT * FROM memory_item
            WHERE tenant_id = #{tenantId}
              AND type = #{type}
              AND confidence >= #{minConfidence}
              AND stale = 0
              AND (expires_at IS NULL OR expires_at > NOW())
              AND (knowledge_base_ids IS NULL OR JSON_OVERLAPS(knowledge_base_ids, CAST(#{kbJson} AS JSON)))
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<MemoryItemRecord> findByTypeAndConfidenceScoped(@Param("tenantId") String tenantId,
                                                          @Param("type") String type,
                                                          @Param("minConfidence") double minConfidence,
                                                          @Param("limit") int limit,
                                                          @Param("kbJson") String kbJson);

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

    /** 写侧判重扫描：租户内活跃 fact（不过滤置信度，低置信旧条目也参与合并），最新优先。 */
    @Select("""
            SELECT * FROM memory_item
            WHERE tenant_id = #{tenantId}
              AND type = 'fact'
              AND stale = 0
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<MemoryItemRecord> findActiveFactsByTenant(@Param("tenantId") String tenantId,
                                                   @Param("limit") int limit);

    /** 近重复合并：置信度取 max（调用方算好传入），缺失向量补齐、已有向量保留。 */
    @Update("""
            UPDATE memory_item
            SET confidence = #{confidence},
                embedding_json = COALESCE(#{embeddingJson, jdbcType=VARCHAR}, embedding_json),
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int mergeFactConfidence(@Param("id") Long id,
                            @Param("confidence") double confidence,
                            @Param("embeddingJson") String embeddingJson);

    /** 近重复被更强证据取代：内容/来源/溯源锚点/KB 作用域一并切到更强证据，置信度取调用方算好的合并值。 */
    @Update("""
            UPDATE memory_item
            SET content = #{content}, source = #{source}, source_chat_id = #{sourceChatId},
                knowledge_base_ids = #{kbIdsJson, jdbcType=VARCHAR},
                confidence = #{confidence},
                embedding_json = COALESCE(#{embeddingJson, jdbcType=VARCHAR}, embedding_json),
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int mergeFactSupersede(@Param("id") Long id,
                           @Param("content") String content,
                           @Param("source") String source,
                           @Param("sourceChatId") String sourceChatId,
                           @Param("confidence") double confidence,
                           @Param("embeddingJson") String embeddingJson,
                           @Param("kbIdsJson") String kbIdsJson);

    /** 仅更新 KB 作用域标记（弱证据合并路径：KB 归属切到新来源）。 */
    @Update("UPDATE memory_item SET knowledge_base_ids = #{kbIdsJson, jdbcType=VARCHAR}, updated_at = NOW() WHERE id = #{id}")
    int updateKnowledgeBaseIds(@Param("id") Long id, @Param("kbIdsJson") String kbIdsJson);

    /** 按业务 ID 软失效任意类型记忆（用户纠偏/治理动作）：幂等条件更新，返回失效行数。 */
    @Update("""
            UPDATE memory_item
            SET stale = 1, updated_at = NOW()
            WHERE tenant_id = #{tenantId}
              AND memory_id = #{memoryId}
              AND stale = 0
            """)
    int markMemoryStaleByMemoryId(@Param("tenantId") String tenantId,
                                  @Param("memoryId") String memoryId);

    @Delete("DELETE FROM memory_item WHERE expires_at IS NOT NULL AND expires_at < NOW()")
    int deleteExpired();

    /** 租户内全部记忆的 USE 聚合（利用率闭环）：每条记忆的召回次数与最近召回时间。 */
    @Select("""
            SELECT i.memory_id AS memoryId, i.type AS type,
                   COUNT(e.id) AS useCount, MAX(e.created_at) AS lastUsedAt
            FROM memory_item i
            LEFT JOIN memory_event e
              ON e.memory_id = i.memory_id AND e.action = 'USE'
            WHERE i.tenant_id = #{tenantId}
              AND i.stale = 0
              AND (i.expires_at IS NULL OR i.expires_at > NOW())
            GROUP BY i.memory_id, i.type
            """)
    List<Map<String, Object>> findUseStatsByTenant(@Param("tenantId") String tenantId);
}
