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
    /** 结果标题 */
    private String title;
    /** 结果链接 */
    private String url;
    /** 内容摘要 */
    private String snippet;
    /** 相关性得分（后端按排名衰减，第 1 条为 1.0） */
    private double score;
}
