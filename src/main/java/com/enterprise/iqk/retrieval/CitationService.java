package com.enterprise.iqk.retrieval;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成带编号的引用，包含来源、chunk、置信度和摘录。
 */
@Service
public class CitationService {

    /** 将证据按顺序转成编号引用（编号从 1 起），摘录取证据的 snippet */
    public List<CitationItem> buildCitations(List<EvidenceItem> evidence) {
        List<CitationItem> citations = new ArrayList<>();
        for (int i = 0; i < evidence.size(); i++) {
            EvidenceItem e = evidence.get(i);
            citations.add(CitationItem.builder()
                    .index(i + 1)
                    .sourceType(e.getSourceType())
                    .title(e.getTitle())
                    .url(e.getUrl())
                    .chunkId(e.getChunkId())
                    .confidence(e.getScore())
                    .excerpt(e.getSnippet())
                    .build());
        }
        return citations;
    }

    /** 格式化引用脚注：空列表返回空串；每条形如 "[n] 标题 (类型) 可信度: xx%" */
    public String formatCitationFooter(List<CitationItem> citations) {
        if (citations == null || citations.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n---\n引用来源:\n");
        for (CitationItem c : citations) {
            sb.append("[").append(c.getIndex()).append("] ");
            sb.append(StringUtils.hasText(c.getTitle()) ? c.getTitle() : "未知来源");
            sb.append(" (").append(c.getSourceType()).append(")");
            if (c.getConfidence() > 0) {
                sb.append(" 可信度: ").append(Math.round(c.getConfidence() * 100)).append("%");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
