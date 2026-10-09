package com.enterprise.iqk.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.util.StringUtils;
import java.util.List;

/**
 * 会话软作用域：知识库按租户共享，chat_id 不再做检索硬过滤。
 * 硬过滤的代价是知识库按会话割裂——A 会话上传的文档 B 会话检索不到，
 * DeepResearch 用临时 chatId（research_+taskId）发检索，向量/关键词两路必然为空。
 * 软作用域改为：同会话命中的文档获得有界加分（+0.05，封顶 1.0），
 * 兼顾"当前会话上传的资料更可能相关"与"租户内知识共享"两个诉求。
 */
public final class ChatScope {

    /** 同会话命中的加分幅度（有界，避免压过相关性本身的差距） */
    public static final double BOOST = 0.05;

    private ChatScope() {
    }

    /** 文档元数据 chat_id 与请求 chatId 一致；两者任一空白视为不同会话，不加成。 */
    public static boolean isSameChat(Document doc, String chatId) {
        if (doc == null || !StringUtils.hasText(chatId) || doc.getMetadata() == null) {
            return false;
        }
        Object docChat = doc.getMetadata().get("chat_id");
        return docChat != null && chatId.equals(docChat.toString());
    }

    /** 同会话命中时 score + BOOST 并夹紧到 [0,1]，否则原样返回。 */
    public static double boost(double score, Document doc, String chatId) {
        if (!isSameChat(doc, chatId)) {
            return score;
        }
        return clamp01(score + BOOST);
    }

    /** ScoredDocument 版本的会话软作用域加分。 */
    public static double boost(double score, ScoredDocument doc, String chatId) {
        if (doc == null || !StringUtils.hasText(chatId) || doc.getMetadata() == null) {
            return score;
        }
        Object docChat = doc.getMetadata().get("chat_id");
        if (docChat == null || !chatId.equals(docChat.toString())) {
            return score;
        }
        return clamp01(score + BOOST);
    }

    /** Apply the same bounded chat-scope boost to ScoredDocument results. */
    public static List<ScoredDocument> boostAll(List<ScoredDocument> docs, String chatId) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        docs.forEach(doc -> doc.setRetrievalScore(boost(doc.getRetrievalScore(), doc, chatId)));
        return docs;
    }

    /** 夹紧到 [0,1]，NaN/无穷按 0 处理。 */
    public static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
