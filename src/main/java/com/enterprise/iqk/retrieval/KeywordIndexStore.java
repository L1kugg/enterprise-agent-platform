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
              (chunk_key, tenant_id, chat_id, job_id, chunk_index, title, content, created_at, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON DUPLICATE KEY UPDATE
              chat_id = VALUES(chat_id),
              title = VALUES(title),
              content = VALUES(content),
              created_at = VALUES(created_at),
              indexed_at = NOW()
            """;
    private static final String SEARCH_SQL = """
            SELECT title, content, chat_id, job_id, chunk_index,
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
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        try {
            List<ScoredDocument> candidates = jdbcTemplate.query(SEARCH_SQL,
                    (rs, rowNum) -> ScoredDocument.builder()
                            .docId("kwft-" + rowNum)
                            .sourceType("keyword")
                            .title(rs.getString("title"))
                            .chunkId("chunk-" + rs.getInt("chunk_index"))
                            .content(rs.getString("content"))
                            .rawText(rs.getString("content"))
                            .retrievalScore(rs.getDouble("relevance"))
                            .metadata(java.util.Map.of(
                                    "tenant_id", tenantId,
                                    "chat_id", StringUtils.hasText(chatId) ? chatId : "",
                                    "job_id", rs.getString("job_id"),
                                    "chunk_index", rs.getInt("chunk_index")
                            ))
                            .build(),
                    query, tenantId, query, topK);
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

    private Counter counter(String outcome) {
        return Counter.builder("retrieval.keyword.index")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private String str(Object value, String fallback) {
        return value == null || !StringUtils.hasText(value.toString()) ? fallback : value.toString();
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
