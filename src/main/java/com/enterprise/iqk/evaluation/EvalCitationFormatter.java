package com.enterprise.iqk.evaluation;

import com.enterprise.iqk.retrieval.CitationItem;
import com.enterprise.iqk.retrieval.EvidenceItem;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 回答产物到评测字符串的转换：引用去重成 sourceType:title:chunkId，
 * 证据只保留非空片段。纯函数、无状态，落 eval_result 前统一在这里整形。
 */
final class EvalCitationFormatter {

    private EvalCitationFormatter() {
    }

    /** 引用格式化为去重的 sourceType:title:chunkId 字符串列表。 */
    static List<String> toCitationStrings(List<CitationItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (CitationItem item : items) {
            if (item == null) {
                continue;
            }
            String text = "%s:%s:%s".formatted(
                    emptyIfBlank(item.getSourceType()),
                    emptyIfBlank(item.getTitle()),
                    emptyIfBlank(item.getChunkId())
            );
            if (StringUtils.hasText(text.replace(":", ""))) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    static List<String> toEvidenceStrings(List<EvidenceItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (EvidenceItem item : items) {
            if (item != null && StringUtils.hasText(item.getSnippet())) {
                values.add(item.getSnippet());
            }
        }
        return values;
    }

    static String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
