package com.enterprise.iqk.retrieval;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * MySQL-backed lexical index. This lane is independent of pgvector: document
 * ingestion writes every chunk here, and retrieval uses FULLTEXT before falling
 * back to the legacy vector-candidate reranker for pre-migration documents.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordIndexStore {
    private static final String INSERT_SQL = """
            INSERT INTO retrieval_keyword_chunk
              (chunk_key, tenant_id, chat_id, knowledge_base_id, job_id, chunk_index, title, content, created_at, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON DUPLICATE KEY UPDATE
              chat_id = VALUES(chat_id),
              knowledge_base_id = VALUES(knowledge_base_id),
              title = VALUES(title),
              content = VALUES(content),
              created_at = VALUES(created_at),
              indexed_at = NOW()
            """;
    private static final String SEARCH_SQL = """
            SELECT title, content, chat_id, knowledge_base_id, job_id, chunk_index,
                   MATCH(title, content) AGAINST (? IN NATURAL LANGUAGE MODE) AS relevance
            FROM retrieval_keyword_chunk
            WHERE tenant_id = ?
              AND MATCH(title, content) AGAINST (? IN NATURAL LANGUAGE MODE)
            ORDER BY relevance DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    public void indexAll(String tenantId, List<Document> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            List<Object[]> rows = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            for (Document chunk : chunks) {
                String tenant = str(chunk.getMetadata().get("tenant_id"), tenantId);
                String jobId = str(chunk.getMetadata().get("job_id"), "");
                if (!StringUtils.hasText(jobId)) {
                    continue;
                }
                int chunkIndex = intOf(chunk.getMetadata().get("chunk_index"), rows.size());
                rows.add(new Object[]{
                        tenant + ":" + jobId + ":" + chunkIndex,
                        tenant,
                        str(chunk.getMetadata().get("chat_id"), null),
                        longOrNull(chunk.getMetadata().get("knowledge_base_id")),
                        jobId,
                        chunkIndex,
                        str(chunk.getMetadata().get("file_name"), "unknown"),
                        StringUtils.hasText(chunk.getText()) ? chunk.getText() : "",
                        createdAt(chunk.getMetadata().get("created_at"), now)
                });
            }
            if (rows.isEmpty()) {
                return;
            }
            jdbcTemplate.batchUpdate(INSERT_SQL, rows);
            counter("success").increment(rows.size());
        } catch (RuntimeException ex) {
            counter("error").increment();
            log.warn("keyword index write degraded: tenant={}, chunks={}, reason={}",
                    tenantId, chunks.size(), ex.toString());
        }
    }

    public List<ScoredDocument> search(String query, String tenantId, String chatId, int topK) {
        return search(query, tenantId, chatId, topK, KnowledgeScope.unrestricted());
    }

    /**
     * 知识库作用域检索（FR-R5-01）：scope restricted 时追加 knowledge_base_id IN 过滤。
     * 作用域走 SQL 级过滤（结构性数据在关系列上，不走向量库后置过滤）；
     * 元数据携带 knowledge_base_id，供下游引用溯源。
     */
    public List<ScoredDocument> search(String query, String tenantId, String chatId, int topK, KnowledgeScope scope) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        KnowledgeScope effectiveScope = scope == null ? KnowledgeScope.unrestricted() : scope;
        String sql = SEARCH_SQL;
        List<Object> args = new ArrayList<>(List.of(query, tenantId, query));
        if (effectiveScope.restricted()) {
            sql = SEARCH_SQL.replace("ORDER BY relevance DESC",
                    scopeFilter(effectiveScope) + " ORDER BY relevance DESC");
            args.addAll(effectiveScope.knowledgeBaseIds());
        }
        args.add(topK);
        try {
            List<ScoredDocument> candidates = jdbcTemplate.query(sql,
                    (rs, rowNum) -> {
                        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
                        metadata.put("tenant_id", tenantId);
                        metadata.put("chat_id", StringUtils.hasText(chatId) ? chatId : "");
                        metadata.put("job_id", rs.getString("job_id"));
                        metadata.put("chunk_index", rs.getInt("chunk_index"));
                        Long kbId = longOrNull(rs.getObject("knowledge_base_id"));
                        if (kbId != null) {
                            metadata.put("knowledge_base_id", kbId);
                        }
                        return ScoredDocument.builder()
                                .docId("kwft-" + rowNum)
                                .sourceType("keyword")
                                .title(rs.getString("title"))
                                .chunkId("chunk-" + rs.getInt("chunk_index"))
                                .content(rs.getString("content"))
                                .rawText(rs.getString("content"))
                                .retrievalScore(rs.getDouble("relevance"))
                                .metadata(metadata)
                                .build();
                    },
                    args.toArray());
            double max = candidates.stream()
                    .mapToDouble(ScoredDocument::getRetrievalScore)
                    .max()
                    .orElse(0.0);
            if (max > 0.0) {
                candidates.forEach(item -> item.setRetrievalScore(item.getRetrievalScore() / max));
            }
            counter("search_success").increment();
            return ChatScope.boostAll(candidates, chatId);
        } catch (RuntimeException ex) {
            counter("search_error").increment();
            log.warn("keyword index search degraded, falling back to vector candidates: tenant={}, reason={}",
                    tenantId, ex.toString());
            return List.of();
        }
    }

    /** 知识库作用域 SQL 片段：IN 列表由 Long 参数拼占位符，值不进 SQL 文本。 */
    private String scopeFilter(KnowledgeScope scope) {
        String placeholders = String.join(",",
                java.util.Collections.nCopies(scope.knowledgeBaseIds().size(), "?"));
        return "AND knowledge_base_id IN (" + placeholders + ")";
    }

    /**
     * 知识库删除时解绑（FR-R5-01 删除策略）：文档与索引数据保留，归属置空回退为"未归属"，
     * 不做级联删除——知识库是组织视图，删库不等于删资料。失败降级为 0（删除主流程不受阻）。
     */
    public int clearKnowledgeBase(String tenantId, Long knowledgeBaseId) {
        try {
            int rows = jdbcTemplate.update(
                    "UPDATE retrieval_keyword_chunk SET knowledge_base_id = NULL " +
                            "WHERE tenant_id = ? AND knowledge_base_id = ?",
                    tenantId, knowledgeBaseId);
            log.info("keyword index unbound from kb: tenant={}, kb={}, rows={}", tenantId, knowledgeBaseId, rows);
            return rows;
        } catch (RuntimeException ex) {
            log.warn("keyword index unbind degraded: tenant={}, kb={}, reason={}",
                    tenantId, knowledgeBaseId, ex.toString());
            return 0;
        }
    }

    public int deleteByChat(String tenantId, String chatId) {
        try {
            int rows = jdbcTemplate.update(
                    "DELETE FROM retrieval_keyword_chunk WHERE tenant_id = ? AND chat_id = ?",
                    tenantId, chatId);
            counter("delete_success").increment(rows);
            return rows;
        } catch (RuntimeException ex) {
            counter("delete_error").increment();
            log.warn("keyword index delete degraded: tenant={}, chatId={}, reason={}",
                    tenantId, chatId, ex.toString());
            return 0;
        }
    }

    /** 删除单个切片的关键词行（chunk_key = tenant:job:chunkIndex，R8 分段治理用）。 */
    public int deleteByJobChunkIndex(String tenantId, String jobId, int chunkIndex) {
        try {
            int rows = jdbcTemplate.update(
                    "DELETE FROM retrieval_keyword_chunk WHERE chunk_key = ?",
                    tenantId + ":" + jobId + ":" + chunkIndex);
            counter("delete_success").increment(rows);
            return rows;
        } catch (RuntimeException ex) {
            counter("delete_error").increment();
            log.warn("keyword index delete degraded: tenant={}, job={}, index={}, reason={}",
                    tenantId, jobId, chunkIndex, ex.toString());
            return 0;
        }
    }

    private Counter counter(String outcome) {
        return Counter.builder("retrieval.keyword.index")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private String str(Object value, String fallback) {
        return value == null || !StringUtils.hasText(value.toString()) ? fallback : value.toString();
    }

    /** 元数据/结果集里的知识库 ID 转 Long（写入为 Long、JSON 回读可能是 Integer），缺失/非法返回 null。 */
    private Long longOrNull(Object value) {
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

    private int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private LocalDateTime createdAt(Object value, LocalDateTime fallback) {
        if (value instanceof Number number) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(number.longValue()), ZoneOffset.UTC);
        }
        if (value instanceof Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
        }
        return fallback;
    }
}
