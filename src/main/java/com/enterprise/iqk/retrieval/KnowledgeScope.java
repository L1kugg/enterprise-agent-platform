package com.enterprise.iqk.retrieval;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 知识库召回作用域（FR-R5-01）：智能体绑定知识库后，召回严格限定在绑定范围内。
 * unrestricted（空列表）= 不限库，保持租户级共享检索（普通聊天/未绑定智能体原行为零变化）；
 * restricted = 只保留元数据 knowledge_base_id 命中绑定列表的切片，未归属文档（历史数据）
 * 一并排除——"绑定即收紧"，不让未圈库的旧文档漏进圈定范围。
 * 过滤实现在各检索器读取结果元数据完成，不依赖向量库的过滤表达式语义
 * （SimpleVectorStore / pgvector 对 IN 谓词的元数据类型处理不一致，后置过滤对两类后端行为一致）。
 */
public record KnowledgeScope(List<Long> knowledgeBaseIds) {

    private static final KnowledgeScope UNRESTRICTED = new KnowledgeScope(List.of());

    public static KnowledgeScope unrestricted() {
        return UNRESTRICTED;
    }

    /** null/空列表归一为不限库，调用方无需判空。 */
    public static KnowledgeScope of(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return UNRESTRICTED;
        }
        return new KnowledgeScope(List.copyOf(knowledgeBaseIds));
    }

    /** 是否处于"绑定知识库圈定范围"模式。 */
    public boolean restricted() {
        return !knowledgeBaseIds.isEmpty();
    }

    /** 单条向量库文档是否在作用域内；不限库时恒真，无元数据视为未归属、圈定时排除。 */
    public boolean allows(Document doc) {
        if (!restricted()) {
            return true;
        }
        Long kbId = kbIdOf(doc);
        return kbId != null && knowledgeBaseIds.contains(kbId);
    }

    /** 读取文档元数据里的知识库 ID（写入为 Long，JSON 序列化回读可能退化为 Integer），缺失返回 null。 */
    public static Long kbIdOf(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        return toLong(doc.getMetadata().get("knowledge_base_id"));
    }

    /** 读取 ScoredDocument 元数据里的知识库 ID，缺失返回 null。 */
    public static Long kbIdOf(ScoredDocument doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        return toLong(doc.getMetadata().get("knowledge_base_id"));
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
