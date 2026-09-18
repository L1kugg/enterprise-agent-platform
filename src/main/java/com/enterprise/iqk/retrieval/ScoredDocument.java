package com.enterprise.iqk.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoredDocument {
    private String docId;
    private String sourceType;   // 向量 | 关键词 | 图谱 | 网络
    private String title;
    private String url;
    private String chunkId;
    private String content;
    private double retrievalScore; // 检索器给出的原始得分（0-1）
    private double finalScore;     // 融合 + 证据评审之后的得分（0-1）
    private Map<String, Object> metadata;
}
