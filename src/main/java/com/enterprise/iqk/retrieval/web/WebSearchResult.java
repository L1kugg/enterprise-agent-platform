package com.enterprise.iqk.retrieval.web;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 来自外部搜索后端的单条网络搜索结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchResult {
    private String title;
    private String url;
    private String snippet;
    private double score;
}
