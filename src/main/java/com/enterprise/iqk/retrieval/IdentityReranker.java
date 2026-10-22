package com.enterprise.iqk.retrieval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 恒等重排器（rag.reranker=identity 时启用）：不重打分、不重排，仅截断到 topK 条。
 * LLM 重排器（rag.reranker=llm，默认）失败时也回退到原序语义。
 */
@Component
@ConditionalOnProperty(name = "rag.reranker", havingValue = "identity", matchIfMissing = false)
public class IdentityReranker implements Reranker {

    /** 恒等重排：不重打分、不重排，仅截断到 topK 条 */
    @Override
    public List<ScoredDocument> rerank(String tenantId, String chatId, String query,
                                       List<ScoredDocument> documents, int topK) {
        return documents.stream().limit(topK).toList();
    }

    /** 策略名固定为 "identity" */
    @Override
    public String getName() {
        return "identity";
    }
}
