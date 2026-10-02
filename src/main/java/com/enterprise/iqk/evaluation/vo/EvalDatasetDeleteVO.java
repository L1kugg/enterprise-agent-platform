package com.enterprise.iqk.evaluation.vo;

import lombok.Builder;
import lombok.Data;

/** 删除评测集的回执：数据集名 + 各层联动清理条数，供前端成功提示展示。 */
@Data
@Builder
public class EvalDatasetDeleteVO {
    private String datasetName;
    private int cases;
    private int runs;
    private int results;
}
