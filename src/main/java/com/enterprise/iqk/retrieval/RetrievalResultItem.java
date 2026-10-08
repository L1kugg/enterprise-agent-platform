package com.enterprise.iqk.retrieval;

/**
 * 对外暴露的有序混合检索结果。只保留评测与展示需要的身份字段与排序分数，
 * 不携带正文，避免把检索上下文意外泄露到评测 API。
 */
public record RetrievalResultItem(
        String sourceType,
        String title,
        String chunkId,
        double score) {
}
