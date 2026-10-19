package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.AgentBindingMapper;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 智能体绑定工具目录（R7.3）：按 agentId 动态生成规划器提示词里的可用工具清单
 * （每工具一行：名称 + 类型 + 必填参数 + 描述），供 tool_call 动作参考。
 * 与 McpToolCatalog 的全局静态 hint 不同，本目录是 per-agent 的——
 * 两条 ReAct 链路在 reason() 组装提示词时按 runState.config().toolIds() 注入。
 * 目录只做展示拼装：mapper 原始读取（描述/参数不含密钥），失败返回空段不阻断会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolCatalog {

    private final AgentBindingMapper bindingMapper;
    private final PlatformAssetMapper assetMapper;
    private final ObjectMapper objectMapper;

    /**
     * 生成绑定工具目录段（无绑定时返回空串，调用方不追加任何内容）。
     * 每行格式：- 工具名（FORM_API，必填参数：city, date）：描述；
     * executionConfirm=true 的工具追加【需人工确认】——模型知道调用后会挂起等审批，
     * 不要在未获结果时反复重试同一工具。
     */
    public String catalogSection(Long agentId, String tenantId) {
        if (agentId == null || agentId <= 0) {
            return "";
        }
        try {
            List<Long> toolIds = bindingMapper.findByAgent("agent_tool_binding", tenantId, agentId)
                    .stream().map(row -> row.getResourceId()).toList();
            if (toolIds.isEmpty()) {
                return "";
            }
            StringBuilder section = new StringBuilder();
            for (Long toolId : toolIds) {
                PlatformAsset tool = assetMapper.findByIdAndTypeVisible(toolId, tenantId, "tools");
                if (tool == null || !"ENABLED".equalsIgnoreCase(tool.getStatus())) {
                    continue;
                }
                JsonNode config = parseConfig(tool);
                section.append("- ").append(tool.getName())
                        .append("（").append(config.path("toolType").asText("FORM_API").toUpperCase()).append("）");
                if (config.path("executionConfirm").asBoolean(false)) {
                    section.append("【需人工确认：调用后会挂起等待用户在聊天界面审批，审批后才有结果，勿重复调用】");
                }
                String required = requiredParams(config);
                if (!required.isEmpty()) {
                    section.append("，必填参数：").append(required);
                }
                if (StringUtils.hasText(tool.getDescription())) {
                    section.append("：").append(tool.getDescription().trim());
                }
                section.append('\n');
            }
            if (section.isEmpty()) {
                return "";
            }
            return "当前智能体绑定的可用平台工具（tool_call 动作，tool 填工具名）：\n" + section;
        } catch (Exception ex) {
            log.warn("生成绑定工具目录失败（不阻断会话）: agentId={}, reason={}", agentId, ex.toString());
            return "";
        }
    }

    private JsonNode parseConfig(PlatformAsset tool) {
        try {
            return objectMapper.readTree(tool.getConfigJson() == null
                    || tool.getConfigJson().isBlank() ? "{}" : tool.getConfigJson());
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    /** 从 params 定义数组提取必填参数名（逗号分隔）。 */
    private String requiredParams(JsonNode config) {
        JsonNode definitions = config.path("params");
        StringBuilder names = new StringBuilder();
        if (definitions.isArray()) {
            for (JsonNode definition : definitions) {
                if (!definition.path("required").asBoolean(false)) {
                    continue;
                }
                if (!names.isEmpty()) {
                    names.append(", ");
                }
                names.append(definition.path("name").asText());
            }
        }
        return names.toString();
    }
}
