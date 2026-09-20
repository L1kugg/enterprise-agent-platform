package com.enterprise.iqk.evaluation.vo;

import lombok.Data;

import java.util.List;

/** 创建评测数据集请求体：名称必填，附用例列表一次性建全。 */
@Data
public class EvalDatasetCreateVO {
    private String name;
    private String description;
    private List<EvalCaseCreateVO> cases;
}
