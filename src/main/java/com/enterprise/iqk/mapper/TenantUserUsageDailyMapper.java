package com.enterprise.iqk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.iqk.domain.TenantUserUsageDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface TenantUserUsageDailyMapper extends BaseMapper<TenantUserUsageDaily> {

    @Insert("""
            INSERT INTO tenant_user_usage_daily (
              tenant_id,
              user_id,
              usage_date,
              request_count,
              input_tokens,
              output_tokens,
              total_cost_usd,
              created_at,
              updated_at
            ) VALUES (
              #{tenantId},
              #{userId},
              #{usageDate},
              #{requestCount},
              #{inputTokens},
              #{outputTokens},
              #{totalCostUsd},
              NOW(),
              NOW()
            )
            ON DUPLICATE KEY UPDATE
              request_count = request_count + VALUES(request_count),
              input_tokens = input_tokens + VALUES(input_tokens),
              output_tokens = output_tokens + VALUES(output_tokens),
              total_cost_usd = total_cost_usd + VALUES(total_cost_usd),
              updated_at = NOW()
            """)
    int addUsage(@Param("tenantId") String tenantId,
                 @Param("userId") String userId,
                 @Param("usageDate") LocalDate usageDate,
                 @Param("requestCount") long requestCount,
                 @Param("inputTokens") long inputTokens,
                 @Param("outputTokens") long outputTokens,
                 @Param("totalCostUsd") java.math.BigDecimal totalCostUsd);

    /** 时间窗内按用户聚合用量（按费用降序），供管理员"按用户统计"视图。 */
    @Select("""
            SELECT user_id,
                   COALESCE(SUM(request_count), 0)  AS request_count,
                   COALESCE(SUM(input_tokens), 0)   AS input_tokens,
                   COALESCE(SUM(output_tokens), 0)  AS output_tokens,
                   COALESCE(SUM(total_cost_usd), 0) AS total_cost_usd
            FROM tenant_user_usage_daily
            WHERE tenant_id = #{tenantId}
              AND usage_date BETWEEN #{fromDate} AND #{toDate}
            GROUP BY user_id
            ORDER BY total_cost_usd DESC
            """)
    List<TenantUserUsageDaily> sumByUser(@Param("tenantId") String tenantId,
                                         @Param("fromDate") LocalDate fromDate,
                                         @Param("toDate") LocalDate toDate);
}
