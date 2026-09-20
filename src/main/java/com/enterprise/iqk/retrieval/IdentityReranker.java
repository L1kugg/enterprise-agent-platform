package com.enterprise.iqk.retrieval;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认的空操作重排器，直接原样返回文档。
 * 在未配置或不可用 LLM 重排器时使用。
 */
@Component
public class IdentityReranker implements Reranker {

    /** 恒等重排：不重打分、不重排，仅截断到 topK 条 */
    @Override
    public List<ScoredDocument> rerank(String query, List<ScoredDocument> documents, int topK) {
        return documents.stream().limit(topK).toList();
    }

    /** 策略名固定为 "identity" */
    @Override
    public String getName() {
        return "identity";
    }
}
