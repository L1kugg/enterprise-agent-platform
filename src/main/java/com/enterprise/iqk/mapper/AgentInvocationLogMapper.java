package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.agent.AgentInvocationLogRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 智能体调用日志 Mapper（R2.2 观测看板读侧）：聚合走真实日志表，全部强制 tenant+agent 圈定。 */
@Mapper
public interface AgentInvocationLogMapper extends BaseMapper<AgentInvocationLogRecord> {

    /** 汇总统计：无数据返回一行全 0（服务层判 total=0 走空态）。 */
    @Select("""
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count,
                   SUM(CASE WHEN status = 'BLOCKED' THEN 1 ELSE 0 END) AS blocked_count,
                   SUM(CASE WHEN status = 'ERROR' THEN 1 ELSE 0 END) AS error_count,
                   COALESCE(ROUND(AVG(NULLIF(latency_ms, 0))), 0) AS avg_latency_ms,
                   COALESCE(MAX(latency_ms), 0) AS max_latency_ms,
                   COALESCE(SUM(input_tokens), 0) AS input_tokens,
                   COALESCE(SUM(output_tokens), 0) AS output_tokens,
                   COALESCE(SUM(CASE WHEN fallback_applied = 1 THEN 1 ELSE 0 END), 0) AS fallback_count,
                   MIN(created_at) AS first_invocation_at,
                   MAX(created_at) AS last_invocation_at
            FROM agent_invocation_log
            WHERE tenant_id = #{tenantId} AND agent_id = #{agentId}
              AND created_at >= #{since}
            """)
    Map<String, Object> summarizeByAgent(@Param("tenantId") String tenantId,
                                         @Param("agentId") Long agentId,
                                         @Param("since") LocalDateTime since);

    /** 按天趋势（缺天由服务层补零，保证曲线连续）。 */
    @Select("""
            SELECT DATE(created_at) AS day,
                   COUNT(*) AS total,
                   SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count,
                   SUM(CASE WHEN status = 'BLOCKED' THEN 1 ELSE 0 END) AS blocked_count,
                   SUM(CASE WHEN status = 'ERROR' THEN 1 ELSE 0 END) AS error_count,
                   COALESCE(ROUND(AVG(NULLIF(latency_ms, 0))), 0) AS avg_latency_ms
            FROM agent_invocation_log
            WHERE tenant_id = #{tenantId} AND agent_id = #{agentId}
              AND created_at >= #{since}
            GROUP BY DATE(created_at)
            ORDER BY day
            """)
    List<Map<String, Object>> trendByAgent(@Param("tenantId") String tenantId,
                                           @Param("agentId") Long agentId,
                                           @Param("since") LocalDateTime since);

    /** 常见错误 Top N（BLOCKED 的拦截详情与 ERROR 的失败摘要同列聚合）。 */
    @Select("""
            SELECT error_summary, COUNT(*) AS cnt
            FROM agent_invocation_log
            WHERE tenant_id = #{tenantId} AND agent_id = #{agentId}
              AND created_at >= #{since}
              AND error_summary IS NOT NULL AND error_summary != ''
            GROUP BY error_summary
            ORDER BY cnt DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> topErrorsByAgent(@Param("tenantId") String tenantId,
                                               @Param("agentId") Long agentId,
                                               @Param("since") LocalDateTime since,
                                               @Param("limit") int limit);

    /** 调用日志分页（status/channel 可选过滤，最新在前）。 */
    @Select("""
            <script>
            SELECT * FROM agent_invocation_log
            WHERE tenant_id = #{tenantId} AND agent_id = #{agentId}
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            <if test="channel != null and channel != ''">
              AND channel = #{channel}
            </if>
            ORDER BY id DESC
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<AgentInvocationLogRecord> pageByAgent(@Param("tenantId") String tenantId,
                                               @Param("agentId") Long agentId,
                                               @Param("status") String status,
                                               @Param("channel") String channel,
                                               @Param("offset") long offset,
                                               @Param("limit") int limit);

    /** 与 pageByAgent 同过滤条件的总数。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM agent_invocation_log
            WHERE tenant_id = #{tenantId} AND agent_id = #{agentId}
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            <if test="channel != null and channel != ''">
              AND channel = #{channel}
            </if>
            </script>
            """)
    long countByAgent(@Param("tenantId") String tenantId,
                      @Param("agentId") Long agentId,
                      @Param("status") String status,
                      @Param("channel") String channel);
}
