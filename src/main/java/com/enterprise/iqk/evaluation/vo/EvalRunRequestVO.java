package com.enterprise.iqk.evaluation.vo;

import lombok.Data;

/** 触发评测请求体：路径参数与契约式入口共用。 */
@Data
public class EvalRunRequestVO {
    /** 数据集 ID：契约式入口（POST /runs）必填，路径参数入口忽略 */
    private String datasetId;
    /** 模型档位，缺省 balanced */
    private String modelProfile;
    /** 用例未指定 chatId 时的会话前缀（后拼序号） */
    private String chatIdPrefix;
}
