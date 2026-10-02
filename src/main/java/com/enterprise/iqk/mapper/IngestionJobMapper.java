package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.IngestionJob;
import com.enterprise.iqk.domain.enums.IngestionJobStatus;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IngestionJobMapper extends BaseMapper<IngestionJob> {

    @Select("""
            SELECT * FROM ingestion_job
            WHERE tenant_id = #{tenantId}
              AND idempotency_key = #{idempotencyKey}
            LIMIT 1
            """)
    IngestionJob findByIdempotencyKey(@Param("tenantId") String tenantId,
                                      @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM ingestion_job WHERE job_id = #{jobId} LIMIT 1")
    IngestionJob findByJobId(@Param("jobId") String jobId);

    @Select("""
            SELECT * FROM ingestion_job
            WHERE tenant_id = #{tenantId}
              AND job_id = #{jobId}
            LIMIT 1
            """)
    IngestionJob findByJobIdAndTenant(@Param("tenantId") String tenantId, @Param("jobId") String jobId);

    @Select("""
            SELECT * FROM ingestion_job
            WHERE status IN ('PENDING','RETRY')
              AND (next_retry_at IS NULL OR next_retry_at <= #{now})
            ORDER BY created_at ASC
            LIMIT 1
            """)
    IngestionJob findNextReadyJob(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE ingestion_job
            SET status = #{toStatus},
                started_at = #{startedAt},
                updated_at = #{updatedAt},
                attempt_count = attempt_count + 1,
                error_message = NULL
            WHERE job_id = #{jobId}
              AND tenant_id = #{tenantId}
              AND status IN ('PENDING','RETRY')
            """)
    int claimForRun(@Param("jobId") String jobId,
                    @Param("tenantId") String tenantId,
                    @Param("toStatus") IngestionJobStatus toStatus,
                    @Param("startedAt") LocalDateTime startedAt,
                    @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE ingestion_job
            SET status = #{status},
                finished_at = #{finishedAt},
                updated_at = #{updatedAt},
                error_message = #{errorMessage},
                next_retry_at = #{nextRetryAt}
            WHERE job_id = #{jobId}
            """)
    int updateTerminalState(@Param("jobId") String jobId,
                            @Param("status") IngestionJobStatus status,
                            @Param("finishedAt") LocalDateTime finishedAt,
                            @Param("updatedAt") LocalDateTime updatedAt,
                            @Param("errorMessage") String errorMessage,
                            @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Select("""
            SELECT * FROM ingestion_job
            WHERE tenant_id = #{tenantId}
              AND chat_id = #{chatId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<IngestionJob> findLatestByChatId(@Param("tenantId") String tenantId,
                                          @Param("chatId") String chatId,
                                          @Param("limit") int limit);

    @Select("""
            SELECT * FROM ingestion_job
            WHERE tenant_id = #{tenantId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<IngestionJob> findLatestByTenant(@Param("tenantId") String tenantId,
                                          @Param("limit") int limit);

    @Delete("""
            DELETE FROM ingestion_job
            WHERE tenant_id = #{tenantId}
              AND chat_id = #{chatId}
            """)
    int deleteByChatIdAndTenant(@Param("tenantId") String tenantId, @Param("chatId") String chatId);

    @Select("""
            SELECT * FROM ingestion_job
            WHERE status = 'RETRY'
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= #{now}
            ORDER BY next_retry_at ASC
            LIMIT #{limit}
            """)
    List<IngestionJob> findReadyRetries(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE ingestion_job
            SET status = 'PENDING',
                updated_at = #{updatedAt},
                next_retry_at = NULL
            WHERE job_id = #{jobId}
              AND status = 'RETRY'
            """)
    int requeueRetry(@Param("jobId") String jobId, @Param("updatedAt") LocalDateTime updatedAt);

    /** 入库成功后回写向量切片数（纯展示字段，失败不影响入库结果）。 */
    @Update("""
            UPDATE ingestion_job
            SET chunk_count = #{chunkCount},
                updated_at = #{updatedAt}
            WHERE job_id = #{jobId}
            """)
    int updateChunkCount(@Param("jobId") String jobId,
                         @Param("chunkCount") int chunkCount,
                         @Param("updatedAt") LocalDateTime updatedAt);

    /** 知识库文档清单：当前租户内每个 chat_id 取最新一条任务代表一份文档，搜索作用于文件名/批次。 */
    @Select("""
            <script>
            SELECT * FROM ingestion_job
            WHERE tenant_id = #{tenantId}
              AND id IN (
                  SELECT MAX(id) FROM ingestion_job
                  WHERE tenant_id = #{tenantId}
                  GROUP BY chat_id
              )
            <if test="search != null and search != ''">
              AND (
                chat_id LIKE CONCAT('%', #{search}, '%')
                OR source_name LIKE CONCAT('%', #{search}, '%')
              )
            </if>
            ORDER BY id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<IngestionJob> findLatestPerChatByTenant(@Param("tenantId") String tenantId,
                                                 @Param("search") String search,
                                                 @Param("offset") long offset,
                                                 @Param("pageSize") int pageSize);

    /** 与 findLatestPerChatByTenant 同过滤条件的总数。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM ingestion_job
            WHERE tenant_id = #{tenantId}
              AND id IN (
                  SELECT MAX(id) FROM ingestion_job
                  WHERE tenant_id = #{tenantId}
                  GROUP BY chat_id
              )
            <if test="search != null and search != ''">
              AND (
                chat_id LIKE CONCAT('%', #{search}, '%')
                OR source_name LIKE CONCAT('%', #{search}, '%')
              )
            </if>
            </script>
            """)
    long countLatestPerChatByTenant(@Param("tenantId") String tenantId, @Param("search") String search);

    /** 管理员跨租户文档总览：每个 (tenant_id, chat_id) 取最新一条任务代表文档状态，搜索作用于展示行。 */
    @Select("""
            <script>
            SELECT * FROM ingestion_job
            WHERE id IN (
                SELECT MAX(id) FROM ingestion_job
                GROUP BY tenant_id, chat_id
            )
            <if test="search != null and search != ''">
              AND (
                tenant_id LIKE CONCAT('%', #{search}, '%')
                OR chat_id LIKE CONCAT('%', #{search}, '%')
                OR source_name LIKE CONCAT('%', #{search}, '%')
              )
            </if>
            ORDER BY id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<IngestionJob> findLatestPerChatCrossTenant(@Param("search") String search,
                                                    @Param("offset") long offset,
                                                    @Param("pageSize") int pageSize);

    /** 与 findLatestPerChatCrossTenant 同过滤条件的总数。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM ingestion_job
            WHERE id IN (
                SELECT MAX(id) FROM ingestion_job
                GROUP BY tenant_id, chat_id
            )
            <if test="search != null and search != ''">
              AND (
                tenant_id LIKE CONCAT('%', #{search}, '%')
                OR chat_id LIKE CONCAT('%', #{search}, '%')
                OR source_name LIKE CONCAT('%', #{search}, '%')
              )
            </if>
            </script>
            """)
    long countLatestPerChatCrossTenant(@Param("search") String search);
}
