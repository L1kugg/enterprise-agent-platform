package com.enterprise.iqk.service.agent;

import com.enterprise.iqk.config.properties.AgentRuntimeProperties;
import com.enterprise.iqk.domain.agent.AgentBindingRow;
import com.enterprise.iqk.domain.agent.AgentBindingTable;
import com.enterprise.iqk.domain.agent.AgentRuntimeConfig;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.AgentBindingMapper;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 智能体运行时配置装载：按 tenantId+agentId 读原始资产（不走 get() 的脱敏路径，
 * 否则密钥类字段会变成 ******），绑定读关系表、标量读 config_json，装配为不可变配置。
 * 权限与配置完整性校验都在这里：非发布态拒绝普通用户，缺模型/缺提示词给出可修复的 400。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntimeConfigService {

    private final PlatformAssetMapper assetMapper;
    private final AgentBindingMapper bindingMapper;
    private final ObjectMapper objectMapper;
    private final AgentRuntimeProperties properties;

    /**
     * @param debugMode 工作台调试：允许草稿态（控制器已 ADMIN 把关）
     */
    public AgentRuntimeConfig loadForChat(String tenantId, Long agentId, boolean debugMode) {
        requireAuthenticated();
        PlatformAsset agent = assetMapper.findByIdAndType(agentId, tenantId, "agents");
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "智能体不存在");
        }
        if (!"PUBLISHED".equalsIgnoreCase(agent.getStatus()) && !debugMode && !callerIsAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "智能体未发布或未启用，无权使用");
        }

        JsonNode config = parse(agent.getConfigJson());
        String prompt = config.path("prompt").asText("");
        if (prompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "智能体系统提示词为空，请先完善配置");
        }

        List<AgentBindingRow> modelBindings =
                bindingMapper.findByAgent(AgentBindingTable.MODEL.tableName(), tenantId, agentId);
        Long modelServiceId = modelBindings.isEmpty() ? null : modelBindings.get(0).getResourceId();
        String boundModelName = null;
        if (modelServiceId != null) {
            // 系统共享资产可见（scope=SYSTEM）：本租户私有 ∪ 系统共享
            PlatformAsset model = assetMapper.findByIdAndTypeVisible(modelServiceId, tenantId, "model-services");
            if (model == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "智能体绑定的模型服务不存在");
            }
            boundModelName = modelNameOf(model.getConfigJson());
            if (boundModelName == null || boundModelName.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型服务未配置模型名称");
            }
        }

        return new AgentRuntimeConfig(
                agentId,
                agent.getName(),
                agent.getStatus(),
                prompt,
                temperatureOf(config),
                memoryTurnsOf(config),
                maxStepsOf(config),
                textOrNull(config.path("openingMessage")),
                stringList(config.path("openingQuestions")),
                !config.path("executionConfirm").isBoolean() || config.path("executionConfirm").asBoolean(true),
                modelServiceId,
                boundModelName,
                resourceIds(bindingMapper.findByAgent(AgentBindingTable.SAFETY.tableName(), tenantId, agentId)),
                resourceIds(bindingMapper.findByAgent(AgentBindingTable.WORKFLOW.tableName(), tenantId, agentId)),
                resourceIds(bindingMapper.findByAgent(AgentBindingTable.TOOL.tableName(), tenantId, agentId)),
                resourceIds(bindingMapper.findByAgent(AgentBindingTable.KNOWLEDGE.tableName(), tenantId, agentId)),
                bindingMapper.findByAgent(AgentBindingTable.PUBLISH_CHANNEL.tableName(), tenantId, agentId)
                        .stream().map(AgentBindingRow::getChannel).filter(channel -> channel != null && !channel.isBlank())
                        .toList()
        );
    }

    /** 带 agentId 的请求必须认证：匿名（含关闭鉴权的本地环境）直接 401，不带 agentId 的老用法不受影响。 */
    private void requireAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getPrincipal() == null
                || "anonymousUser".equals(String.valueOf(authentication.getPrincipal()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "使用智能体需要登录");
        }
    }

    /** 与 @PreAuthorize("hasRole('ADMIN')") 同语义的管理员判定。 */
    private boolean callerIsAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    /** modelName 为主字段，兼容 modelParams.model / model 旧写法。 */
    private String modelNameOf(String configJson) {
        JsonNode config = parse(configJson);
        String modelName = config.path("modelName").asText("");
        if (modelName.isBlank()) {
            modelName = config.path("modelParams").path("model").asText(config.path("model").asText(""));
        }
        return modelName.isBlank() ? null : modelName.trim();
    }

    /** temperature 缺省返回 null（沿用模型默认），非法值忽略，有效值钳制 [0,2]。 */
    private Double temperatureOf(JsonNode config) {
        JsonNode node = config.path("modelParams").path("temperature");
        if (!node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        if (value < 0 || value > 2) {
            log.warn("智能体 temperature={} 超出 [0,2]，按边界截断", value);
            value = Math.max(0, Math.min(2, value));
        }
        return value;
    }

    /** memoryTurns 缺省 10，钳制 [1, maxMemoryTurns]。 */
    private int memoryTurnsOf(JsonNode config) {
        JsonNode node = config.path("memoryTurns");
        int turns = node.isInt() ? node.asInt() : 10;
        return Math.max(1, Math.min(properties.getMaxMemoryTurns(), turns));
    }

    /** maxSteps 缺省 null（走链路默认），配置了则钳制 [1,10]——复杂任务步数预算按智能体放开。 */
    private Integer maxStepsOf(JsonNode config) {
        JsonNode node = config.path("maxSteps");
        if (!node.isInt()) {
            return null;
        }
        int steps = Math.max(1, Math.min(10, node.asInt()));
        return steps;
    }

    private List<Long> resourceIds(List<AgentBindingRow> rows) {
        return rows.stream().map(AgentBindingRow::getResourceId).toList();
    }

    private String textOrNull(JsonNode node) {
        String text = node.asText("");
        return text.isBlank() ? null : text;
    }

    private List<String> stringList(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode item : array) {
            String text = item.asText("");
            if (!text.isBlank()) {
                values.add(text.trim());
            }
        }
        return values;
    }

    private JsonNode parse(String configJson) {
        try {
            return objectMapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "智能体配置 JSON 不合法，请重新保存配置", ex);
        }
    }
}
