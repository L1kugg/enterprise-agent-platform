package com.enterprise.iqk.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 检索结果统一结构：四路检索器（向量/关键词/图谱/网络）与混合层的公共载体。
 * retrievalScore 为检索器原始得分，finalScore 为加权融合后的最终得分，
 * 去重保留与排序均以 finalScore 为准。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoredDocument {
    /** 检索器自定义前缀的文档标识（如 vec-0 / kw-0 / web-1a2b3c4d） */
    private String docId;
    private String sourceType;   // 向量 | 关键词 | 图谱 | 网络
    /** 展示标题（文件名或图谱三元组等） */
    private String title;
    /** 原文链接，仅网络来源有值 */
    private String url;
    /** 原文 chunk 序号或图谱实体/事实 ID */
    private String chunkId;
    /** 命中的正文内容 */
    private String content;
    private double retrievalScore; // 检索器给出的原始得分（0-1）
    private double finalScore;     // 融合 + 证据评审之后的得分（0-1）
    /** 检索器透传的原始元数据（可含得分、时间戳等） */
    private Map<String, Object> metadata;
}
