package com.enterprise.iqk.evaluation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评测数据集（eval_dataset 表）：一组评测用例的容器。
 * baselineRunId 指向被标记为基线的运行，对比接口以此为参照衡量效果变化。
 */
@TableName("eval_dataset")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalDatasetRecord {
    /** 数据库自增主键（内部使用） */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 数据集业务 ID（eval-ds-{uuid12}），对外暴露与关联 eval_case 的键 */
    private String datasetId;
    /** 租户 ID，所有查询的第一道过滤条件 */
    private String tenantId;
    /** 数据集名称 */
    private String name;
    /** 数据集描述 */
    private String description;
    /** 基线运行 ID：compareLatest 优先与它对比，为空时退化为最近两轮互比 */
    private String baselineRunId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
