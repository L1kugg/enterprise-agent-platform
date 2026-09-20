package com.enterprise.iqk.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条引用：答案脚注中一项可追溯来源（由 CitationService 从证据生成）。
 * 末尾三个 getter 为字段别名，方便调用方以 id/source/snippet 语义取值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationItem {
    private int index;           // 答案中的引用编号，例如 [1]
    /** 来源类型（向量/关键词/图谱/网络） */
    private String sourceType;
    /** 来源标题 */
    private String title;
    /** 原文链接，仅网络来源有值 */
    private String url;
    /** 原文 chunk 序号或图谱实体/事实 ID */
    private String chunkId;
    private double confidence;   // 0-1
    private String excerpt;      // 从来源摘录的原文

    /** 编号别名（同 index） */
    public int getId() {
        return index;
    }

    /** 来源类型别名（同 sourceType） */
    public String getSource() {
        return sourceType;
    }

    /** 摘录别名（同 excerpt） */
    public String getSnippet() {
        return excerpt;
    }
}
