package com.enterprise.iqk.service;

import com.enterprise.iqk.domain.vo.AgentSessionBranchVO;
import com.enterprise.iqk.domain.vo.AgentSessionMessageVO;
import com.enterprise.iqk.domain.vo.AgentSessionStateVO;
import com.enterprise.iqk.domain.vo.SessionHandoffRequestVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Generates a deterministic handoff summary from the persisted session branch.
 * It avoids an extra LLM call: handoff is a context-transfer operation, not
 * another answer-generation task.
 */
@Service
@RequiredArgsConstructor
public class SessionHandoffService {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withLocale(Locale.CHINA);

    private final AgentSessionService agentSessionService;

    public AgentSessionStateVO generate(String tenantId, String sessionId,
                                         SessionHandoffRequestVO request) {
        AgentSessionStateVO state = agentSessionService.get(tenantId, sessionId);
        AgentSessionBranchVO branch = selectBranch(state, request == null ? null : request.getBranchId());
        state.setHandoffSummary(buildSummary(state, branch));
        state.setHandoffGeneratedAt(System.currentTimeMillis());
        return agentSessionService.upsert(tenantId, sessionId, state);
    }

    private AgentSessionBranchVO selectBranch(AgentSessionStateVO state, String branchId) {
        List<AgentSessionBranchVO> branches = state.getBranches() == null ? List.of() : state.getBranches();
        if (StringUtils.hasText(branchId)) {
            return branches.stream()
                    .filter(branch -> Objects.equals(branchId, branch.getId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("branch not found"));
        }
        return branches.stream()
                .filter(branch -> Objects.equals(branch.getId(), state.getActiveBranchId()))
                .findFirst()
                .orElse(branches.isEmpty() ? null : branches.get(0));
    }

    private String buildSummary(AgentSessionStateVO state, AgentSessionBranchVO branch) {
        List<AgentSessionMessageVO> messages = branch == null || branch.getMessages() == null
                ? List.of() : branch.getMessages();
        List<AgentSessionMessageVO> userMessages = messages.stream()
                .filter(item -> "user".equalsIgnoreCase(item.getRole()))
                .filter(item -> StringUtils.hasText(item.getContent()))
                .toList();
        List<AgentSessionMessageVO> assistantMessages = messages.stream()
                .filter(item -> "assistant".equalsIgnoreCase(item.getRole()))
                .filter(item -> StringUtils.hasText(item.getContent()))
                .toList();
        AgentSessionMessageVO goal = userMessages.isEmpty() ? null : userMessages.get(0);
        AgentSessionMessageVO latestResult = assistantMessages.isEmpty() ? null : assistantMessages.get(assistantMessages.size() - 1);

        StringBuilder summary = new StringBuilder();
        summary.append("# 会话交接摘要\n\n");
        summary.append("- 会话：").append(defaultText(state.getTitle(), sessionId(state)))
                .append("\n");
        summary.append("- 生成时间：").append(formattedNow()).append("\n");
        summary.append("- 消息规模：").append(messages.size()).append(" 条；其中用户 ")
                .append(userMessages.size()).append(" 条，助手 ").append(assistantMessages.size()).append(" 条\n");
        summary.append("- 活动分支：").append(branch == null ? defaultText(state.getActiveBranchId(), "root")
                : defaultText(branch.getTitle(), branch.getId())).append("\n\n");

        summary.append("## 当前目标\n");
        summary.append(goal == null ? "- 未记录用户请求。\n" : "- " + compact(goal.getContent(), 500) + "\n");

        summary.append("\n## 最新进展\n");
        summary.append(latestResult == null
                ? "- 助手尚未给出最终回答。\n"
                : "- " + compact(latestResult.getContent(), 900) + "\n");

        summary.append("\n## 近期讨论\n");
        List<AgentSessionMessageVO> recent = messages.stream()
                .filter(item -> StringUtils.hasText(item.getContent()))
                .toList();
        if (recent.isEmpty()) {
            summary.append("- 无历史消息。\n");
        } else {
            recent.subList(Math.max(0, recent.size() - 6), recent.size()).forEach(message -> {
                String role = "assistant".equalsIgnoreCase(message.getRole()) ? "助手" : "用户";
                summary.append("- ").append(role).append("：")
                        .append(compact(message.getContent(), 260)).append("\n");
            });
        }

        Set<String> references = references(messages);
        summary.append("\n## 引用与证据标识\n");
        if (references.isEmpty()) {
            summary.append("- 本会话没有引用知识库证据。\n");
        } else {
            references.forEach(item -> summary.append("- ").append(item).append("\n"));
        }

        summary.append("\n## 新会话启动提示\n");
        summary.append("- 将本摘要粘贴到新会话开头，再描述下一个目标。\n");
        summary.append("- 继续前先确认上面的最新进展是否已经验证。\n");
        summary.append("- 若引用仍相关，可重新检索同一知识库；不要默认旧证据仍是最新的。\n");
        return summary.toString();
    }

    private Set<String> references(List<AgentSessionMessageVO> messages) {
        Set<String> values = new LinkedHashSet<>();
        for (AgentSessionMessageVO message : messages) {
            if (message.getCitations() != null) {
                message.getCitations().stream().filter(StringUtils::hasText)
                        .limit(4).forEach(values::add);
            }
        }
        return values.size() > 8
                ? new LinkedHashSet<>(List.copyOf(values).subList(0, 8))
                : values;
    }

    private String compact(String value, int maxChars) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String sessionId(AgentSessionStateVO state) {
        return defaultText(state.getId(), "unknown-session");
    }

    private String formattedNow() {
        return TIME_FORMAT.format(Instant.now().atZone(ZoneId.systemDefault()));
    }
}
