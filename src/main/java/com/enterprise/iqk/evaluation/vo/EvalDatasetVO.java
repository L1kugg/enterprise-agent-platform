package com.enterprise.iqk.evaluation.vo;

import lombok.Builder;
import lombok.Data;

/** 数据集视图：含基线运行 ID 与用例数，时间已格式化为 ISO 字符串。 */
@Data
@Builder
public class EvalDatasetVO {
    private String datasetId;
    private String tenantId;
    private String name;
    private String description;
    private String baselineRunId;
    private int caseCount;
    private String createdAt;
    private String updatedAt;
}
