package com.enterprise.iqk.evaluation.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 单轮评测视图：运行元信息 + 汇总指标 + 逐 case 结果。 */
@Data
@Builder
public class EvalRunVO {
    private String runId;
    private String datasetId;
    private String tenantId;
    /** 运行状态：RUNNING / SUCCESS */
    private String status;
    private String modelProfile;
    /** 汇总指标（通过数、各分项均值） */
    private EvalMetricSummaryVO metrics;
    /** 逐 case 结果明细 */
    private List<EvalResultVO> results;
    private String errorMessage;
    private String startedAt;
    private String finishedAt;
    private String createdAt;
}
