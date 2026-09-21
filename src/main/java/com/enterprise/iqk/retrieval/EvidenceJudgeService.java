package com.enterprise.iqk.retrieval;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 从三个维度为证据条目评分：
 * - 相关度：证据与查询的匹配程度
 * - 权威度：来源可信度（例如：内部文档 > 网络来源）
 * - 时效度：信息的新鲜程度
 */
@Service
@RequiredArgsConstructor
public class EvidenceJudgeService {

    private final MeterRegistry meterRegistry;

    /** 综合得分权重分配：相关度 0.50、权威度 0.30、时效度 0.20 */
    private static final double RELEVANCE_WEIGHT = 0.50;
    private static final double AUTHORITY_WEIGHT = 0.30;
    private static final double TIMELINESS_WEIGHT = 0.20;

    /**
     * 对文档逐条做三维评分并按综合分降序返回证据条目；
     * 摘录压缩空白后截断到 180 字符。记录判分延迟指标。
     */
    public List<EvidenceItem> judge(List<ScoredDocument> documents, String query) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<EvidenceItem> items = new ArrayList<>();
            for (ScoredDocument doc : documents) {
                double relevance = scoreRelevance(doc, query);
                double authority = scoreAuthority(doc);
                double timeliness = scoreTimeliness(doc);
                double composite = relevance * RELEVANCE_WEIGHT
                        + authority * AUTHORITY_WEIGHT
                        + timeliness * TIMELINESS_WEIGHT;

                items.add(EvidenceItem.builder()
                        .sourceType(doc.getSourceType())
                        .title(doc.getTitle())
                        .url(doc.getUrl())
                        .chunkId(doc.getChunkId())
                        .score(composite)
                        .reason(buildReason(doc, relevance, authority, timeliness))
                        .relevanceScore(relevance)
                        .authorityScore(authority)
                        .timelinessScore(timeliness)
                        .snippet(truncate(doc.getContent(), 180))
                        .build());
            }
            items.sort(Comparator.comparingDouble(EvidenceItem::getScore).reversed());
            return items;
        } finally {
            sample.stop(Timer.builder("evidence.judge.latency")
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    /** 相关度：以 finalScore 为基础，按查询词命中内容的个数加成（每个 +0.05、上限 +0.3，封顶 1.0） */
    private double scoreRelevance(ScoredDocument doc, String query) {
        // 以检索得分为基础，再按关键词重叠度加成；
        // 切词走 LexicalMatcher（CJK 2-gram）——此前中文查询整句成单 token，命中检查必然落空
        double base = doc.getFinalScore();
        if (!StringUtils.hasText(query) || !StringUtils.hasText(doc.getContent())) {
            return base;
        }
        String lowerContent = doc.getContent().toLowerCase(Locale.ROOT);
        long hits = 0;
        for (String token : LexicalMatcher.tokenize(query)) {
            if (token.length() >= 2 && lowerContent.contains(token)) {
                hits++;
            }
        }
        double boost = Math.min(0.3, hits * 0.05);
        return Math.min(1.0, base + boost);
    }

    /** 权威度按来源类型固定赋值：图谱 0.90 &gt; 向量 0.75 &gt; 关键词 0.65 &gt; 网络/未知 0.50 */
    private double scoreAuthority(ScoredDocument doc) {
        return switch (doc.getSourceType()) {
            case "graph" -> 0.90;   // 结构化知识图谱数据
            case "vector" -> 0.75;  // 内部文档 chunk
            case "keyword" -> 0.65; // 关键词匹配（来源相同，置信度较低）
            case "web" -> 0.50;     // 外部网络内容
            default -> 0.50;
        };
    }

    /** 时效度：按元数据时间戳距今天数分档（30 天内 1.0 → 一年以上 0.5），无时间戳记中性 0.70 */
    private double scoreTimeliness(ScoredDocument doc) {
        // 默认：没有时间戳元数据 → 中性分数
        if (doc.getMetadata() == null) return 0.70;
        Object timestamp = doc.getMetadata().get("created_at");
        if (timestamp == null) timestamp = doc.getMetadata().get("timestamp");
        if (timestamp == null) return 0.70;
        try {
            long epoch = Long.parseLong(timestamp.toString());
            long now = System.currentTimeMillis();
            long daysAgo = (now - epoch) / (1000 * 60 * 60 * 24);
            if (daysAgo < 30) return 1.0;
            if (daysAgo < 90) return 0.9;
            if (daysAgo < 180) return 0.8;
            if (daysAgo < 365) return 0.7;
            return 0.5;
        } catch (NumberFormatException e) {
            return 0.70;
        }
    }

    /** 生成人类可读的评分理由（来源类型 + 三个维度的百分比） */
    private String buildReason(ScoredDocument doc, double relevance,
                                double authority, double timeliness) {
        String sourceLabel = switch (doc.getSourceType()) {
            case "vector" -> "向量语义匹配";
            case "keyword" -> "关键词精确匹配";
            case "graph" -> "知识图谱关联";
            case "web" -> "外部搜索";
            default -> "未知来源";
        };
        return String.format("%s，相关度%.0f%%，权威度%.0f%%，时效度%.0f%%",
                sourceLabel, relevance * 100, authority * 100, timeliness * 100);
    }

    /** 压缩空白后截断到 maxLen，超出部分以 "..." 结尾 */
    private String truncate(String text, int maxLen) {
        if (!StringUtils.hasText(text)) return "";
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen) + "...";
    }
}
