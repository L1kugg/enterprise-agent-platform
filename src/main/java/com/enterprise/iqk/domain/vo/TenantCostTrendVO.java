package com.enterprise.iqk.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** 用量趋势单日数据点（GET /cost/trend 返回裸数组，缺数据的天由服务层补零）。 */
@Data
@Builder
public class TenantCostTrendVO {
    private String date;
    private Long requestCount;
    private Long inputTokens;
    private Long outputTokens;
    private BigDecimal costUsd;
}
