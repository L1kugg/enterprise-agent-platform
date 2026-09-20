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
 * 评测运行（eval_run 表）：对某数据集跑一轮评测的汇总记录。
 * 生命周期 RUNNING → SUCCESS，指标字段在全部 case 执行完后由汇总结果回写，
 * 是效果回归对比（compareLatest / 基线）的基本单位。
 */
@TableName("eval_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalRunRecord {
    /** 数据库自增主键（内部使用） */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 运行业务 ID（eval-run-{uuid12}） */
    private String runId;
    /** 所属数据集 ID */
    private String datasetId;
    /** 租户 ID */
    private String tenantId;
    /** 运行状态：RUNNING（执行中）/ SUCCESS（完成） */
    private String status;
    /** 本轮使用的模型档位（缺省 balanced） */
    private String modelProfile;
    /** 本轮执行的用例总数 */
    private Integer totalCases;
    /** 综合分达到阈值（0.70）的用例数 */
    private Integer passedCases;
    /** 全量综合分均值 */
    private Double runScore;
    /** 检索命中率均值 */
    private Double retrievalHitRate;
    /** 引用覆盖率均值 */
    private Double citationCoverageRate;
    /** 答案忠实度均值 */
    private Double answerFaithfulnessScore;
    /** 单 case 平均耗时（毫秒） */
    private Double avgLatencyMs;
    /** 失败（异常或空答案）用例占比 */
    private Double failureRate;
    /** 运行级错误信息（单 case 错误记在 eval_result） */
    private String errorMessage;
    /** 本轮开始时间 */
    private LocalDateTime startedAt;
    /** 本轮结束时间 */
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
