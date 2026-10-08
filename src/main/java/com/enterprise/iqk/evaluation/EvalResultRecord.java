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
 * 评测结果（eval_result 表）：单 case 的答案快照与四项得分明细。
 * 一轮 eval_run 的每个 case 各产生一行，保留原始答案、引用与证据，
 * 是复盘失败用例的第一手数据。
 */
@TableName("eval_result")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalResultRecord {
    /** 数据库自增主键（内部使用） */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 结果业务 ID（eval-result-{uuid12}） */
    private String resultId;
    /** 所属运行 ID */
    private String runId;
    /** 所属数据集 ID（冗余存一份，便于直接按数据集追溯） */
    private String datasetId;
    /** 对应用例 ID */
    private String caseId;
    /** 租户 ID */
    private String tenantId;
    /** 用例执行状态：SUCCESS / FAILED（异常或空答案） */
    private String status;
    /** 问题文本快照 */
    private String questionText;
    /** 实际答案快照 */
    private String answerText;
    /** 实际引用列表 JSON（sourceType:title:chunkId） */
    private String citationsJson;
    /** 实际证据片段列表 JSON */
    private String evidenceJson;
    /** 混合检索去重后的有序 Top-K 身份快照 JSON */
    private String retrievedResultsJson;
    /** 检索命中分 0~1 */
    private Double retrievalHit;
    /** 是否配置了标准检索指标期望 */
    private Boolean retrievalMetricsApplicable;
    /** document / chunk / none */
    private String retrievalMetricLevel;
    private Double recallAtK;
    private Double mrrAtK;
    private Double precisionAtK;
    /** 引用覆盖分 0~1 */
    private Double citationCoverage;
    /** 关键词命中分 0~1 */
    private Double keywordScore;
    /** 答案中引用标记覆盖率 0~1 */
    private Double citationMarkerCoverage;
    /** 加权综合分 0~1（达到 0.70 记为通过） */
    private Double score;
    /** 关键词/禁用词评分是否适用；false 表示该用例未配置相关期望 */
    private Boolean keywordScoreApplicable;
    /** 引用覆盖评分是否适用；false 表示该用例未配置期望引用 */
    private Boolean citationCoverageApplicable;
    /** 单 case 耗时（毫秒） */
    private Long latencyMs;
    /** 失败原因（成功时为空） */
    private String errorMessage;
    private LocalDateTime createdAt;
}
