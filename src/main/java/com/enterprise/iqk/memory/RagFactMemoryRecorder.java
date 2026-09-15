package com.enterprise.iqk.memory;

import com.enterprise.iqk.retrieval.EvidenceItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Persists high-confidence RAG evidence as tenant-scoped fact memory.
 *
 * Facts are the only tenant-wide memory layer: they represent statements
 * the knowledge base can back with a citation, and they are recalled by
 * confidence threshold (see MemoryService#buildContext, floor 0.7). The
 * write threshold intentionally matches that recall floor, and each fact
 * inherits the composite evidence score as its confidence so low-quality
 * evidence never reaches generation context. Writes are best-effort and
 * capped per request to keep memory growth bounded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagFactMemoryRecorder {

    /** Matches the recall floor used by MemoryService#buildContext. */
    static final double FACT_CONFIDENCE_THRESHOLD = 0.7;

    private static final int MAX_FACTS_PER_REQUEST = 3;
    private static final int MAX_SNIPPET_CHARS = 400;

    private final MemoryService memoryService;

    public void recordFacts(String tenantId, List<EvidenceItem> evidence) {
        if (!StringUtils.hasText(tenantId) || evidence == null || evidence.isEmpty()) {
            return;
        }
        int saved = 0;
        for (EvidenceItem item : evidence) {
            if (saved >= MAX_FACTS_PER_REQUEST) {
                break;
            }
            if (item == null || item.getScore() < FACT_CONFIDENCE_THRESHOLD
                    || !StringUtils.hasText(item.getSnippet())) {
                continue;
            }
            try {
                memoryService.saveFactMemory(tenantId, null,
                        "事实: " + truncate(item.getSnippet(), MAX_SNIPPET_CHARS),
                        factSource(item),
                        item.getScore());
                saved++;
            } catch (Exception ex) {
                log.warn("fact memory persistence failed: title={}, reason={}",
                        item.getTitle(), ex.toString());
            }
        }
    }

    private String factSource(EvidenceItem item) {
        String title = StringUtils.hasText(item.getTitle()) ? item.getTitle() : "untitled";
        return "rag:" + item.getSourceType() + ":" + title;
    }

    private String truncate(String text, int maxChars) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }
}
