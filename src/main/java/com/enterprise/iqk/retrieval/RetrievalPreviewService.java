package com.enterprise.iqk.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 知识库「试搜」：直接调用向量/关键词/图谱三条本地检索路并返回原始命中，
 * 不走大模型、不碰网络路。用途：用户上传文档后，在不出答案、不烧 token 的前提下
 * 验证"这段内容到底能不能被检索到"，也是调 RAG 效果时的低成本观察窗口。
 * 单路异常按降级处理（留痕不中止），与混合检索的降级语义保持一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalPreviewService {

    /** 命中片段展示截断长度：足够判断"搜到了没"，又不至于把整个切片刷在页面上 */
    private static final int SNIPPET_MAX_LENGTH = 280;
    /** 去重指纹长度，与 HybridRetrievalService 的指纹口径一致 */
    private static final int FINGERPRINT_LENGTH = 200;

    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final GraphRetriever graphRetriever;

    public RetrievalPreviewResult search(String tenantId, String query, int topK) {
        String normalizedTenantId = tenantId == null ? "" : tenantId.trim();
        int safeTopK = Math.max(1, Math.min(topK, 20));

        Set<String> degraded = new LinkedHashSet<>();
        List<ScoredDocument> all = new ArrayList<>();
        collectLane("vector", degraded, all, () -> vectorRetriever.retrieve(query, normalizedTenantId, ""));
        collectLane("keyword", degraded, all, () -> keywordRetriever.retrieve(query, normalizedTenantId, "", safeTopK));
        collectLane("graph", degraded, all, () -> graphRetriever.retrieve(query, normalizedTenantId, safeTopK));

        List<ScoredDocument> merged = deduplicate(all);
        merged.sort(Comparator.comparingDouble(ScoredDocument::getRetrievalScore).reversed());

        List<RetrievalPreviewItem> items = merged.stream()
                .limit(safeTopK)
                .map(doc -> new RetrievalPreviewItem(
                        doc.getSourceType() == null ? "unknown" : doc.getSourceType(),
                        doc.getTitle() == null ? "unknown" : doc.getTitle(),
                        doc.getChunkId() == null ? "" : doc.getChunkId(),
                        round3(doc.getRetrievalScore()),
                        snippet(displayContent(doc))))
                .toList();
        return new RetrievalPreviewResult(items, List.copyOf(degraded));
    }

    /** 展示用正文：优先纯正文（rawText），没有再退回带元数据前缀的 content（图谱路等） */
    private String displayContent(ScoredDocument doc) {
        return StringUtils.hasText(doc.getRawText()) ? doc.getRawText() : doc.getContent();
    }

    /** 单路采集：异常记入 degraded 并降级为空，不中止其它路（与混合检索同语义） */
    private void collectLane(String source, Set<String> degraded, List<ScoredDocument> sink,
                             Supplier<List<ScoredDocument>> lane) {
        try {
            List<ScoredDocument> docs = lane.get();
            if (docs != null) {
                sink.addAll(docs);
            }
        } catch (RuntimeException ex) {
            // 降级要留痕：静默吞掉异常会让"没搜到"和"搜挂了"在页面上长得一样，排查无从下手
            log.warn("试搜 {} 路降级: {}", source, ex.getMessage());
            degraded.add(source);
        }
    }

    /** 按内容前缀指纹去重（跨路重复命中只留分高的一条），保持首次出现顺序 */
    private List<ScoredDocument> deduplicate(List<ScoredDocument> docs) {
        Map<String, ScoredDocument> seen = new LinkedHashMap<>();
        for (ScoredDocument d : docs) {
            String fingerprint = fingerprint(d);
            ScoredDocument existing = seen.get(fingerprint);
            if (existing == null || d.getRetrievalScore() > existing.getRetrievalScore()) {
                seen.put(fingerprint, d);
            }
        }
        return new ArrayList<>(seen.values());
    }

    private String fingerprint(ScoredDocument d) {
        String content = displayContent(d) != null ? displayContent(d) : "";
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= FINGERPRINT_LENGTH ? normalized : normalized.substring(0, FINGERPRINT_LENGTH);
    }

    private String snippet(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= SNIPPET_MAX_LENGTH ? normalized : normalized.substring(0, SNIPPET_MAX_LENGTH) + "…";
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
