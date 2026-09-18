package com.enterprise.iqk.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationItem {
    private int index;           // 答案中的引用编号，例如 [1]
    private String sourceType;
    private String title;
    private String url;
    private String chunkId;
    private double confidence;   // 0-1
    private String excerpt;      // 从来源摘录的原文

    public int getId() {
        return index;
    }

    public String getSource() {
        return sourceType;
    }

    public String getSnippet() {
        return excerpt;
    }
}
