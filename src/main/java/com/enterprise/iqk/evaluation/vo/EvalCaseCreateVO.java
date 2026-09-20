package com.enterprise.iqk.evaluation.vo;

import lombok.Data;

import java.util.List;

/** 创建评测用例请求体：问题必填，三个期望列表驱动打分。 */
@Data
public class EvalCaseCreateVO {
    /** 用例 ID：不填则按序生成 case-### */
    private String caseId;
    /** 用例分类，用于分组统计 */
    private String category;
    /** 指定评测会话 ID（可空） */
    private String chatId;
    private String question;
    /** 期望命中的引用（sourceType:title:chunkId 片段匹配） */
    private List<String> expectedCitations;
    /** 期望答案中出现的关键词 */
    private List<String> expectedKeywords;
    /** 禁止出现的关键词：命中即按幻觉惩罚 */
    private List<String> forbiddenKeywords;
}
