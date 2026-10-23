package com.enterprise.iqk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 租户 × 用户 × 日 用量累计（V34）：按用户用量统计的数据源。
 * 与 tenant_usage_daily（租户 × 日，预算闸门数据源）并行写入，互不影响；
 * 后台无身份线程（深度研究/图谱抽取）不写本表。
 */
@TableName("tenant_user_usage_daily")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantUserUsageDaily {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    /** 认证主体 user 键（JWT 用户名 / API Key 主体；匿名交互回落 chatId） */
    private String userId;
    private LocalDate usageDate;
    private Long requestCount;
    private Long inputTokens;
    private Long outputTokens;
    private BigDecimal totalCostUsd;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
