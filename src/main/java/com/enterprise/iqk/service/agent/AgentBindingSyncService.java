package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.domain.agent.AgentBindingRow;
import com.enterprise.iqk.domain.agent.AgentBindingTable;
import com.enterprise.iqk.mapper.AgentBindingMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体绑定同步：config_json 里的绑定 ID（modelId/workflowIds/tools/knowledgeBaseIds/
 * safetyGuardIds/publishChannels）落到 agent_*_binding 关系表，先删后插全量重写。
 * 阶段一前端仍编辑 config_json，运行时只读关系表；表数据以这里为准。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBindingSyncService {

    private final AgentBindingMapper bindingMapper;
    private final ObjectMapper objectMapper;

    /** 全量重写某智能体的绑定关系；configJson 非法时按空配置处理（仅清空旧绑定）。 */
    @Transactional
    public void syncFromConfig(String tenantId, Long agentId, String configJson) {
        JsonNode root = parseQuietly(configJson);
        // 顶层 executionConfirm：显式 false 才为 0，缺省视为允许执行
        boolean executionConfirm = !root.path("executionConfirm").isBoolean()
                || root.path("executionConfirm").asBoolean(true);

        replace(tenantId, agentId, AgentBindingTable.MODEL, modelRows(root, executionConfirm));
        replace(tenantId, agentId, AgentBindingTable.WORKFLOW, idRows(root.path("workflowIds"),
                AgentBindingTable.WORKFLOW.resourceType(), executionConfirm));
        replace(tenantId, agentId, AgentBindingTable.TOOL, toolRows(root, executionConfirm));
        replace(tenantId, agentId, AgentBindingTable.KNOWLEDGE, idRows(root.path("knowledgeBaseIds"),
                AgentBindingTable.KNOWLEDGE.resourceType(), executionConfirm));
        replace(tenantId, agentId, AgentBindingTable.SAFETY, idRows(root.path("safetyGuardIds"),
                AgentBindingTable.SAFETY.resourceType(), executionConfirm));
        replace(tenantId, agentId, AgentBindingTable.PUBLISH_CHANNEL, channelRows(root));
    }

    /** 删除智能体全部绑定（随资源删除调用）；六表删除需原子，独立调用时也要成事务。 */
    @Transactional
    public void deleteAllBindings(String tenantId, Long agentId) {
        for (AgentBindingTable table : AgentBindingTable.values()) {
            bindingMapper.deleteByAgent(table.tableName(), tenantId, agentId);
        }
    }

    private void replace(String tenantId, Long agentId, AgentBindingTable table, List<AgentBindingRow> rows) {
        bindingMapper.deleteByAgent(table.tableName(), tenantId, agentId);
        if (rows.isEmpty()) {
            return;
        }
        bindingMapper.insertBatch(table.tableName(), tenantId, agentId, rows);
    }

    /** modelId 是单值绑定，固定 binding_order = 1。 */
    private List<AgentBindingRow> modelRows(JsonNode root, boolean executionConfirm) {
        List<AgentBindingRow> rows = new ArrayList<>();
        long modelId = positiveLong(root.path("modelId"));
        if (modelId > 0) {
            rows.add(AgentBindingRow.builder()
                    .resourceId(modelId)
                    .resourceType(AgentBindingTable.MODEL.resourceType())
                    .bindingOrder(1)
                    .executionConfirm(executionConfirm)
                    .build());
        }
        return rows;
    }

    /** 通用 ID 数组绑定：非正数或非整数值跳过（旧前端存过 -1 哨兵），顺序即 binding_order。 */
    private List<AgentBindingRow> idRows(JsonNode array, String resourceType, boolean executionConfirm) {
        List<AgentBindingRow> rows = new ArrayList<>();
        if (!array.isArray()) {
            return rows;
        }
        int order = 1;
        for (JsonNode item : array) {
            long id = positiveLong(item);
            if (id <= 0) {
                continue;
            }
            rows.add(AgentBindingRow.builder()
                    .resourceId(id)
                    .resourceType(resourceType)
                    .bindingOrder(order++)
                    .executionConfirm(executionConfirm)
                    .build());
        }
        return rows;
    }

    /** tools 是 [{id, description}] 数组：id 做资源引用，description 存 extra_json 供阶段二透传。 */
    private List<AgentBindingRow> toolRows(JsonNode root, boolean executionConfirm) {
        List<AgentBindingRow> rows = new ArrayList<>();
        JsonNode array = root.path("tools");
        if (!array.isArray()) {
            return rows;
        }
        int order = 1;
        for (JsonNode item : array) {
            long id = positiveLong(item.path("id"));
            if (id <= 0) {
                continue;
            }
            rows.add(AgentBindingRow.builder()
                    .resourceId(id)
                    .resourceType(AgentBindingTable.TOOL.resourceType())
                    .bindingOrder(order++)
                    .executionConfirm(executionConfirm)
                    .extraJson(item.path("description").asText(null))
                    .build());
        }
        return rows;
    }

    /** publishChannels 是字符串数组，resource_id 恒为 0，渠道值进 channel 列。 */
    private List<AgentBindingRow> channelRows(JsonNode root) {
        List<AgentBindingRow> rows = new ArrayList<>();
        JsonNode array = root.path("publishChannels");
        if (!array.isArray()) {
            return rows;
        }
        int order = 1;
        for (JsonNode item : array) {
            String channel = item.asText("");
            if (channel.isBlank()) {
                continue;
            }
            rows.add(AgentBindingRow.builder()
                    .resourceId(0L)
                    .resourceType(AgentBindingTable.PUBLISH_CHANNEL.resourceType())
                    .bindingOrder(order++)
                    .executionConfirm(true)
                    .channel(channel.trim())
                    .build());
        }
        return rows;
    }

    private long positiveLong(JsonNode node) {
        return node != null && node.canConvertToLong() ? node.asLong() : 0L;
    }

    private JsonNode parseQuietly(String configJson) {
        try {
            JsonNode node = objectMapper.readTree(configJson);
            return node instanceof ObjectNode objectNode ? objectNode : objectMapper.createObjectNode();
        } catch (Exception ex) {
            log.warn("智能体 config_json 解析失败，按空配置同步绑定: reason={}", ex.toString());
            return objectMapper.createObjectNode();
        }
    }
}
