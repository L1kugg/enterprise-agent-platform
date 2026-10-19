package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.AgentToolConfirmationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** 平台工具确认任务 Mapper：读按租户+ID 圈定，状态转移全部 CAS（防并发双审批）。 */
@Mapper
public interface AgentToolConfirmationMapper extends BaseMapper<AgentToolConfirmationRecord> {

    @Select("""
            SELECT * FROM agent_tool_confirmation
            WHERE id = #{id} AND tenant_id = #{tenantId}
            LIMIT 1
            """)
    AgentToolConfirmationRecord findByIdAndTenant(@Param("id") Long id,
                                                  @Param("tenantId") String tenantId);

    /** PENDING → APPROVED 原子转移：输家（并发第二个审批/已过期/已取消）0 行命中。 */
    @Update("""
            UPDATE agent_tool_confirmation
            SET status = 'APPROVED', decided_at = #{now}
            WHERE id = #{id} AND tenant_id = #{tenantId}
              AND status = 'PENDING'
              AND expires_at > #{now}
            """)
    int casApprove(@Param("id") Long id,
                   @Param("tenantId") String tenantId,
                   @Param("now") LocalDateTime now);

    /** PENDING → CANCELLED 原子转移（取消不受过期限制：过期后取消无意义但也无害）。 */
    @Update("""
            UPDATE agent_tool_confirmation
            SET status = 'CANCELLED', decided_at = #{now}
            WHERE id = #{id} AND tenant_id = #{tenantId}
              AND status = 'PENDING'
            """)
    int casCancel(@Param("id") Long id,
                  @Param("tenantId") String tenantId,
                  @Param("now") LocalDateTime now);

    /** 执行收尾：APPROVED → EXECUTED / FAILED（仅占位状态可写，不覆盖终态）。 */
    @Update("""
            UPDATE agent_tool_confirmation
            SET status = #{status}, result_summary = #{resultSummary},
                error_message = #{errorMessage}, decided_at = #{now}
            WHERE id = #{id} AND status = 'APPROVED'
            """)
    int casFinish(@Param("id") Long id,
                  @Param("status") String status,
                  @Param("resultSummary") String resultSummary,
                  @Param("errorMessage") String errorMessage,
                  @Param("now") LocalDateTime now);

    /** 惰性过期：读取时把过期 PENDING 打成 EXPIRED（无调度器，谁来读谁顺手收尾）。 */
    @Update("""
            UPDATE agent_tool_confirmation
            SET status = 'EXPIRED', decided_at = #{now}
            WHERE tenant_id = #{tenantId}
              AND status = 'PENDING'
              AND expires_at <= #{now}
            """)
    int expireOverdue(@Param("tenantId") String tenantId,
                      @Param("now") LocalDateTime now);
}
