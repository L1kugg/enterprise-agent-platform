package com.enterprise.iqk.graph;

import com.enterprise.iqk.util.SqlLikeUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
/**
 * 知识图谱查询服务：围绕实体/关系/事实三张表提供关键词检索与一跳邻居扩展。
 * 全部查询强制 tenant_id 过滤，LIKE 关键词先经 SqlLikeUtils 转义防通配符注入；
 * 结果供 GraphRetriever 在 RAG 检索阶段与向量召回融合使用。
 */
public class GraphService {

    private final KgEntityMapper entityMapper;
    private final KgRelationMapper relationMapper;
    private final KgFactMapper factMapper;

    /**
     * 按关键词检索实体（匹配名称或别名）。
     */
    public List<KgEntityRecord> searchEntities(String tenantId, String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) return List.of();
        return entityMapper.searchByName(tenantId, SqlLikeUtils.escapeForLike(keyword.trim()), Math.max(1, limit));
    }

    /**
     * 按多候选关键词批量 OR 检索实体（中文滑窗候选），关键词逐一转义。
     */
    public List<KgEntityRecord> searchEntitiesByKeywords(String tenantId, List<String> keywords, int limit) {
        List<String> escaped = escapeKeywords(keywords);
        if (escaped.isEmpty()) return List.of();
        return entityMapper.searchByKeywords(tenantId, escaped, Math.max(1, limit));
    }

    /**
     * 获取实体的一跳邻居，附带关系信息。
     */
    public List<GraphNeighbor> getNeighbors(String tenantId, String entityId) {
        List<KgRelationRecord> relations = relationMapper.findRelations(tenantId, entityId);
        Map<String, KgEntityRecord> entityCache = new HashMap<>();
        List<GraphNeighbor> neighbors = new ArrayList<>();

        for (KgRelationRecord rel : relations) {
            String neighborId = rel.getSourceEntityId().equals(entityId)
                    ? rel.getTargetEntityId() : rel.getSourceEntityId();
            KgEntityRecord entity = entityCache.computeIfAbsent(neighborId,
                    id -> entityMapper.findByEntityId(tenantId, id));
            if (entity == null) continue;
            boolean outgoing = rel.getSourceEntityId().equals(entityId);
            neighbors.add(GraphNeighbor.builder()
                    .entity(entity)
                    .relationType(rel.getRelationType())
                    .direction(outgoing ? "OUT" : "IN")
                    .weight(rel.getWeight())
                    .build());
        }
        return neighbors;
    }

    /**
     * 按关键词检索事实（匹配主语或宾语）。
     */
    public List<KgFactRecord> searchFacts(String tenantId, String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) return List.of();
        return factMapper.searchByKeyword(tenantId, SqlLikeUtils.escapeForLike(keyword.trim()), Math.max(1, limit));
    }

    /**
     * 按多候选关键词批量 OR 检索事实（中文滑窗候选），关键词逐一转义。
     */
    public List<KgFactRecord> searchFactsByKeywords(String tenantId, List<String> keywords, int limit) {
        List<String> escaped = escapeKeywords(keywords);
        if (escaped.isEmpty()) return List.of();
        return factMapper.searchByKeywords(tenantId, escaped, Math.max(1, limit));
    }

    /** 关键词列表清洗：去空、trim、LIKE 通配符转义。 */
    private List<String> escapeKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return List.of();
        return keywords.stream()
                .filter(StringUtils::hasText)
                .map(keyword -> SqlLikeUtils.escapeForLike(keyword.trim()))
                .toList();
    }

    /**
     * 按类型获取实体，例如全部 COURSE 类型的实体。
     */
    public List<KgEntityRecord> getEntitiesByType(String tenantId, String type) {
        return entityMapper.findByType(tenantId, type);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    /** 一跳邻居视图：邻居实体 + 关系类型 + 方向 + 权重 */
    public static class GraphNeighbor {
        /** 邻居实体 */
        private KgEntityRecord entity;
        /** 与中心实体的关系类型 */
        private String relationType;
        private String direction;  // 取值为 IN 或 OUT
        /** 关系权重 */
        private Double weight;
    }
}
