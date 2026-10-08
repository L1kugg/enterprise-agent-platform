package com.enterprise.iqk.evaluation.vo;

import lombok.Builder;
import lombok.Data;

/** 一轮评测的汇总指标：通过数、标准检索指标与各分项均值。 */
@Data
@Builder
public class EvalMetricSummaryVO {
    private int totalCases;
    /** 综合分达到阈值（0.70）的用例数 */
    private int passedCases;
    /** 综合分均值 */
    private double runScore;
    private double retrievalHitRate;
    private int retrievalMetricsCases;
    /** document / chunk / mixed / none */
    private String retrievalMetricLevel;
    private double recallAtKRate;
    private double mrrAtK;
    private double precisionAtKRate;
    private double citationCoverageRate;
    private double citationMarkerCoverageRate;
    /** 单 case 平均耗时（毫秒） */
    private double avgLatencyMs;
    /** 失败用例占比 */
    private double failureRate;
}
