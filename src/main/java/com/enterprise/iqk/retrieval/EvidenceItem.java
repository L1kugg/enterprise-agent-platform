package com.enterprise.iqk.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 证据判分条目：EvidenceJudgeService 对单条 ScoredDocument 的三维评分结果。
 * 按综合分降序输出，供下游决定哪些证据进入生成，并由 CitationService 转成引用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceItem {
    private String sourceType;   // 向量 | 关键词 | 图谱 | 网络
    private String title;
    private String url;
    private String chunkId;
    private double score;        // 0-1 综合得分
    private String reason;       // 人类可读的评分理由
    private double relevanceScore;
    private double authorityScore;
    private double timelinessScore;
    private String snippet;      // 简短预览文本
}
