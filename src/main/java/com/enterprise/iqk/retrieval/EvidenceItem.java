package com.enterprise.iqk.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
