package com.enterprise.iqk.service.platform;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.AgentBindingMapper;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 平台工具共用调用器（R7.3）：把"在发起智能体绑定列表内找工具"与"按 config 真实执行"
 * 收敛为单一入口——聊天 tool_call 运行时与人工确认审批流走同一份绑定校验和执行逻辑，
 * 保证"审批通过后执行的工具与挂起时校验的工具"语义一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformToolInvoker {

    private final PlatformAssetMapper assetMapper;
    private final AgentBindingMapper bindingMapper;
    private final ToolInvocationService toolInvocationService;

    /**
     * 在发起智能体的绑定列表内按名字找工具：
     * agent_tool_binding（tenant+agent 界内）→ 逐个 mapper 原始读取（不过脱敏，credential 保真）→
     * 名字精确匹配（忽略大小写）且 ENABLED。绑定行为空或全不匹配返回 null（上层报未绑定）。
     */
    public PlatformAsset findBoundTool(Long agentId, String tenantId, String toolName) {
        if (agentId == null || agentId <= 0 || !StringUtils.hasText(toolName)) {
            return null;
        }
        List<Long> toolIds;
        try {
            toolIds = bindingMapper.findByAgent("agent_tool_binding", tenantId, agentId)
                    .stream().map(row -> row.getResourceId()).toList();
        } catch (Exception ex) {
            log.warn("读取智能体工具绑定失败: agentId={}, reason={}", agentId, ex.toString());
            return null;
        }
        for (Long toolId : toolIds) {
            PlatformAsset tool = assetMapper.findByIdAndTypeVisible(toolId, tenantId, "tools");
            if (tool == null || !"ENABLED".equalsIgnoreCase(tool.getStatus())) {
                continue;
            }
            if (toolName.equalsIgnoreCase(tool.getName())) {
                return tool;
            }
        }
        return null;
    }

    /** 按 config 真实执行（测试/运行时/审批执行同一入口）。 */
    public Map<String, Object> invoke(PlatformAsset tool, JsonNode config, Map<String, Object> arguments)
            throws Exception {
        return toolInvocationService.invoke(config, arguments);
    }
}
