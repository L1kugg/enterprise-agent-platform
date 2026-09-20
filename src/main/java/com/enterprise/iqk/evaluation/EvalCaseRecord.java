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
 * 评测用例（eval_case 表）：单条问答期望。
 * 期望引用 / 期望关键词 / 禁用关键词三个列表以 JSON 数组字符串落库，
 * 打分时由 EvaluationScorer 反序列化使用。
 */
@TableName("eval_case")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCaseRecord {
    /** 数据库自增主键（内部使用） */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 用例业务 ID：创建时指定或按序生成 case-###，结果表通过它对齐 */
    private String caseId;
    /** 所属数据集 ID（eval-ds-xxx） */
    private String datasetId;
    /** 租户 ID */
    private String tenantId;
    /** 用例分类（如 rag_multi_hop / tool_routing），用于分组统计 */
    private String category;
    /** 指定评测时的会话 ID，为空则由运行请求的前缀或数据集 ID 兜底 */
    private String chatId;
    /** 问题文本 */
    private String questionText;
    /** 期望命中的引用列表 JSON（sourceType:title:chunkId 片段匹配） */
    private String expectedCitationsJson;
    /** 期望答案中出现的关键词列表 JSON */
    private String expectedKeywordsJson;
    /** 禁止出现的关键词列表 JSON：命中即触发幻觉惩罚 */
    private String forbiddenKeywordsJson;
    /** 用例在数据集内的展示顺序 */
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
