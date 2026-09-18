package com.enterprise.iqk.retrieval;

import java.util.List;

/**
 * 可插拔的重排器接口，用于对检索结果重新打分。
 * 实现可以采用 LLM 重排、cross-encoder 或基于规则的评分。
 */
public interface Reranker {

    /**
     * 根据与查询的相关度对给定文档进行重排和过滤。
     *
     * @param query     用户查询
     * @param documents 初始检索结果
     * @param topK      返回结果的最大数量
     * @return 重排后的文档（可能经过过滤和重新打分）
     */
    List<ScoredDocument> rerank(String query, List<ScoredDocument> documents, int topK);

    /**
     * 该重排器策略的人类可读名称。
     */
    String getName();
}
