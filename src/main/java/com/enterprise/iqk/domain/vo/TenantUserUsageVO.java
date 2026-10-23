package com.enterprise.iqk.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 按用户聚合的用量统计（管理员视角，GET /cost/users）：
 * 时间窗内单个认证主体（user 键）的请求数 / token / 费用累计。
 */
@Data
@Builder
public class TenantUserUsageVO {
    /** 认证主体 user 键（JWT 用户名 / API Key 主体） */
    private String userId;
    /** 用户显示名（user 键匹配 users.username 时补齐，未匹配为 null） */
    private String displayName;
    private Long requestCount;
    private Long inputTokens;
    private Long outputTokens;
    private java.math.BigDecimal costUsd;
}
