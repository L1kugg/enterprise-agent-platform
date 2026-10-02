package com.enterprise.iqk.retrieval;

/**
 * 试搜（检索预览）单条命中：只走本地检索路（向量/关键词/图谱），不调用 LLM、不出答案。
 * snippet 为截断后的命中片段，score 为该路检索器的原始得分（0-1）。
 */
public record RetrievalPreviewItem(
        String source,
        String fileName,
        String chunkId,
        double score,
        String snippet) {
}
