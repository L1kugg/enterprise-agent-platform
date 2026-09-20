package com.enterprise.iqk.evaluation.vo;

import lombok.Builder;
import lombok.Data;

/** 评测对比视图：基线轮（可能为空）与最新一轮并排展示，衡量效果变化。 */
@Data
@Builder
public class EvalComparisonVO {
    private EvalDatasetVO dataset;
    /** 基线运行：无标记且不足两轮历史时为 null */
    private EvalRunVO baseline;
    private EvalRunVO current;
}
