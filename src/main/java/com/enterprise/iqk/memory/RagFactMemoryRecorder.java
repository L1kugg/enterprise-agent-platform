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
 * chatId 记入 source_chat_id 列：文档删除时可级联软失效，防"引用已删
 * 来源的可信事实"继续被召回。向量按批次一次批量计算（单次管线最多
 * 多一次 embedding 调用），失败整体回退词面召回。
 * 写入是尽力而为的，且每次请求有条数上限，防止记忆表无界增长。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagFactMemoryRecorder {

    /** 与 MemoryService#buildContext 的召回门槛保持一致。 */
    static final double FACT_CONFIDENCE_THRESHOLD = 0.7;

    /** 单次请求最多写入的事实条数（防止记忆表无界增长）。 */
    private static final int MAX_FACTS_PER_REQUEST = 3;
    /** 单条事实摘录的截断上限（字符）。 */
    private static final int MAX_SNIPPET_CHARS = 400;

    private final MemoryService memoryService;
    private final MemoryEmbeddingHelper embeddingHelper;

    /**
     * 把置信度达标且带摘录的证据写入 fact 记忆：单次最多 3 条，
     * 向量整批一次算完，单条失败只告警、不阻塞其余条目。
     */
    public void recordFacts(String tenantId, String chatId, List<EvidenceItem> evidence) {
        if (!StringUtils.hasText(tenantId) || evidence == null || evidence.isEmpty()) {
            return;
        }
        List<EvidenceItem> qualified = evidence.stream()
                .filter(item -> item != null && item.getScore() >= FACT_CONFIDENCE_THRESHOLD
                        && StringUtils.hasText(item.getSnippet()))
                .limit(MAX_FACTS_PER_REQUEST)
                .toList();
        if (qualified.isEmpty()) {
            return;
        }
        List<String> contents = qualified.stream()
                .map(item -> "事实: " + truncate(item.getSnippet(), MAX_SNIPPET_CHARS))
                .toList();
        List<String> embeddings = embeddingHelper.embedAllToJson(contents);
        for (int i = 0; i < qualified.size(); i++) {
            EvidenceItem item = qualified.get(i);
            try {
                memoryService.saveFactMemory(tenantId, chatId, contents.get(i),
                        factSource(item), item.getScore(), embeddings.get(i));
            } catch (Exception ex) {
                log.warn("fact memory persistence failed: title={}, reason={}",
                        item.getTitle(), ex.toString());
            }
        }
    }

    /** 构造 "rag:{sourceType}:{title}" 格式的来源标识，无标题时以 untitled 兜底。 */
    private String factSource(EvidenceItem item) {
        String title = StringUtils.hasText(item.getTitle()) ? item.getTitle() : "untitled";
        return "rag:" + item.getSourceType() + ":" + title;
    }

    /** 空白规范化为单空格后按上限截断，超长补省略号。 */
    private String truncate(String text, int maxChars) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }
}
