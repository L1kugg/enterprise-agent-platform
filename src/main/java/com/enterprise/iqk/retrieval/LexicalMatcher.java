package com.enterprise.iqk.retrieval;

import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 词面匹配工具：CJK 感知切词 + 查询召回打分，供关键词检索/本地重排/证据判分共用。
 * 修复点：
 * 1. CJK 切词——按非字母数字切分后，中文段没有分隔符会整句成单 token
 *    （"课程预约怎么办理" 是一个 token），查询"预约"永远匹配不上；
 *    这里把 CJK 连续段切成字符 2-gram（无分词器时中文检索的标准做法），拉丁/数字段保持原样。
 * 2. 打分分母用查询 token 数（召回语义：查询词有多大比例命中了目标）——
 *    此前除以目标 token 数，长文档分数必然趋零。
 */
public final class LexicalMatcher {

    /** CJK 连续段与其余段交替切分：Han 段走 2-gram，非 Han 段整体作 token */
    private static final Pattern RUN_PATTERN = Pattern.compile("\\p{IsHan}+|[^\\p{IsHan}]+");

    private LexicalMatcher() {
    }

    /** 小写化切词：非字母数字分隔 → 段内再分 Han/非 Han 连续段，Han 段切 2-gram；空白文本返回空集。 */
    public static Set<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        Set<String> tokens = new HashSet<>();
        for (String part : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            collectTokens(part, tokens);
        }
        return tokens;
    }

    /** 查询召回分：查询 token 在目标集中的命中占比（分母 = 查询 token 数）；查询为空记 0。 */
    public static double recallScore(Set<String> query, Set<String> target) {
        if (query == null || query.isEmpty() || target == null) {
            return 0.0;
        }
        long overlap = query.stream().filter(target::contains).count();
        return (double) overlap / query.size();
    }

    /** 段内切分：Han 段切 2-gram（单字保留原样），非 Han 段（拉丁/数字）整体作 token。 */
    private static void collectTokens(String part, Set<String> tokens) {
        Matcher matcher = RUN_PATTERN.matcher(part);
        while (matcher.find()) {
            String run = matcher.group();
            if (isHanRun(run)) {
                addBigrams(run, tokens);
            } else {
                tokens.add(run);
            }
        }
    }

    /** 段首字符是否为 Han（RUN_PATTERN 保证同一段内字符类别一致）。 */
    private static boolean isHanRun(String run) {
        return !run.isEmpty() && Character.UnicodeScript.of(run.charAt(0)) == Character.UnicodeScript.HAN;
    }

    /** 连续 Han 段切字符 2-gram；单字段直接保留（单字查询仍可命中）。 */
    private static void addBigrams(String run, Set<String> tokens) {
        if (run.length() == 1) {
            tokens.add(run);
            return;
        }
        for (int i = 0; i + 2 <= run.length(); i++) {
            tokens.add(run.substring(i, i + 2));
        }
    }
}
