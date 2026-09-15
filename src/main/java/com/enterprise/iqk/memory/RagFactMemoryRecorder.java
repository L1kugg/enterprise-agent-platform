package com.enterprise.iqk.memory;

import com.enterprise.iqk.retrieval.EvidenceItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 把高置信度的 RAG 证据写入租户级 fact 记忆。
 *
 * fact 是唯一按租户共享的记忆层：保存的是知识库能给出引用佐证的陈述，
 * 召回时按置信度过滤（见 MemoryService#buildContext，门槛 0.7）。
 * 写入门槛与召回门槛保持一致，每条事实继承证据综合分作为置信度，
 * 低质量证据永远进不了生成上下文。
 * 写入是尽力而为的，且每次请求有条数上限，防止记忆表无界增长。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagFactMemoryRecorder {

    /** 与 MemoryService#buildContext 的召回门槛保持一致。 */
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
