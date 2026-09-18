package com.enterprise.iqk.retrieval;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认的空操作重排器，直接原样返回文档。
 * 在未配置或不可用 LLM 重排器时使用。
 */
@Component
public class IdentityReranker implements Reranker {

    @Override
    public List<ScoredDocument> rerank(String query, List<ScoredDocument> documents, int topK) {
        return documents.stream().limit(topK).toList();
    }

    @Override
    public String getName() {
        return "identity";
    }
}
