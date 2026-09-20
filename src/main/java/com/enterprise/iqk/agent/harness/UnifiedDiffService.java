package com.enterprise.iqk.agent.harness;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一 diff 服务：为工作区补丁动作生成与应用 unified diff。
 * create 生成的是"全量替换式" diff（单个 hunk、全部旧行 - 前缀 + 全部新行 + 前缀），
 * 不做最小差异对齐——生成端简单可靠，语义等价；apply 则按标准 hunk 头逐块应用。
 */
@Component
public class UnifiedDiffService {
    /** hunk 头正则：@@ -旧起,旧计数 +新起,新计数 @@（计数可省略） */
    private static final Pattern HUNK_HEADER = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    /** 生成全量替换式统一 diff（---/+++ 文件头 + 单 hunk 全删全加） */
    public String create(String path, String oldContent, String newContent) {
        List<String> oldLines = splitLines(oldContent);
        List<String> newLines = splitLines(newContent);
        StringBuilder builder = new StringBuilder();
        builder.append("--- a/").append(path).append('\n');
        builder.append("+++ b/").append(path).append('\n');
        builder.append("@@ -1,").append(oldLines.size())
                .append(" +1,").append(newLines.size()).append(" @@\n");
        for (String line : oldLines) {
            builder.append('-').append(line).append('\n');
        }
        for (String line : newLines) {
            builder.append('+').append(line).append('\n');
        }
        return builder.toString();
    }

    /**
     * 应用补丁到旧内容：逐个 hunk 按头定位旧内容起点，
     * 行前缀含义——空格=保留旧行、'-'=删除旧行、'+'=插入新行；
     * hunk 之外的旧行原样带过，hunk 头非法抛 IllegalArgumentException。
     */
    public String apply(String oldContent, String patch) {
        List<String> oldLines = splitLines(oldContent);
        List<String> patchLines = splitLines(patch);
        List<String> result = new ArrayList<>();
        int oldIndex = 0;
        int patchIndex = 0;
        while (patchIndex < patchLines.size()) {
            String line = patchLines.get(patchIndex);
            if (!line.startsWith("@@ ")) {
                patchIndex++;
                continue;
            }
            Matcher matcher = HUNK_HEADER.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("invalid patch hunk header");
            }
            int oldStart = Integer.parseInt(matcher.group(1));
            int targetOldIndex = Math.max(0, oldStart - 1);
            while (oldIndex < targetOldIndex && oldIndex < oldLines.size()) {
                result.add(oldLines.get(oldIndex++));
            }
            patchIndex++;
            while (patchIndex < patchLines.size() && !patchLines.get(patchIndex).startsWith("@@ ")) {
                String body = patchLines.get(patchIndex++);
                if (body.isEmpty()) {
                    continue;
                }
                char prefix = body.charAt(0);
                String content = body.substring(1);
                if (prefix == ' ') {
                    if (oldIndex < oldLines.size()) {
                        result.add(oldLines.get(oldIndex++));
                    } else {
                        result.add(content);
                    }
                } else if (prefix == '-') {
                    oldIndex++;
                } else if (prefix == '+') {
                    result.add(content);
                }
            }
        }
        while (oldIndex < oldLines.size()) {
            result.add(oldLines.get(oldIndex++));
        }
        return String.join("\n", result);
    }

    /** 按行拆分：容忍结尾换行，空内容返回空列表（保留行内空行） */
    private List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        String normalized = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
        if (normalized.isEmpty()) {
            return List.of();
        }
        return List.of(normalized.split("\\n", -1));
    }
}
