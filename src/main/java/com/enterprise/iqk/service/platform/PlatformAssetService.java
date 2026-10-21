package com.enterprise.iqk.service.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.llm.PlatformChatModelRegistry;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.agent.AgentBindingSyncService;
import com.enterprise.iqk.util.SqlLikeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PlatformAssetService {
    public static final Set<String> ASSET_TYPES = Set.of(
            "agents", "workflows", "tools", "knowledge-bases", "knowledge-files",
            "safety-guards", "model-services", "databases");

    private static final Map<String, String> TYPE_STATUS = Map.of(
            "agents", "DRAFT",
            "workflows", "DRAFT",
            "tools", "ENABLED",
            "knowledge-bases", "ACTIVE",
            "knowledge-files", "PENDING",
            "safety-guards", "ENABLED",
            "model-services", "UNTESTED",
            "databases", "UNTESTED");

    /** 允许 scope=SYSTEM（系统共享）的资产类型白名单：凭据型基础设施，其余六类业务资产强制租户隔离。 */
    private static final Set<String> SYSTEM_SCOPED_TYPES = Set.of("model-services", "databases");

    private final PlatformAssetMapper mapper;
    private final ObjectMapper objectMapper;
    /** agents 保存后把 config_json 绑定 ID 同步进关系表（运行时只读表） */
    private final AgentBindingSyncService agentBindingSyncService;
    /** model-services 配置变更/删除时失效绑定模型客户端缓存 */
    private final PlatformChatModelRegistry chatModelRegistry;
    /** 知识库删除时解绑文档记录（FR-R5-01 删除策略） */
    private final com.enterprise.iqk.mapper.IngestionJobMapper ingestionJobMapper;
    /** 知识库删除时解绑关键词索引切片（FR-R5-01 删除策略） */
    private final com.enterprise.iqk.retrieval.KeywordIndexStore keywordIndexStore;

    public long count(String assetType, String search) {
        String type = normalizeType(assetType);
        String tenantId = TenantContext.currentTenantId();
        String kw = keyword(search);
        return SYSTEM_SCOPED_TYPES.contains(type)
                ? mapper.countForListUnion(tenantId, type, kw)
                : mapper.countForList(tenantId, type, kw);
    }

    public java.util.List<PlatformAsset> list(String assetType, String search, int page, int pageSize) {
        String type = normalizeType(assetType);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        String tenantId = TenantContext.currentTenantId();
        String kw = keyword(search);
        return (SYSTEM_SCOPED_TYPES.contains(type)
                ? mapper.findForListUnion(tenantId, type, kw, safeSize, (long) (safePage - 1) * safeSize)
                : mapper.findForList(tenantId, type, kw, safeSize, (long) (safePage - 1) * safeSize))
                .stream().map(this::sanitize).toList();
    }

    public PlatformAsset get(String assetType, Long id) {
        return sanitize(getOwned(assetType, id));
    }

    public PlatformAsset create(String assetType, String name, String description,
                                String configJson, Long parentId, String creator) {
        return create(assetType, name, description, configJson, parentId, creator, null);
    }

    /** @param scope TENANT|SYSTEM（缺省 TENANT）；SYSTEM 仅凭据型白名单可用且强制落 public 主租户 */
    public PlatformAsset create(String assetType, String name, String description,
                                String configJson, Long parentId, String creator, String scope) {
        String type = normalizeType(assetType);
        validateName(name);
        validateJson(configJson);
        validateSplitConfig(type, configJson);
        boolean system = PlatformAsset.SCOPE_SYSTEM.equalsIgnoreCase(scope == null ? "" : scope.trim());
        if (system && !SYSTEM_SCOPED_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "asset type does not support system scope: " + type);
        }
        String effectiveScope = system ? PlatformAsset.SCOPE_SYSTEM : PlatformAsset.SCOPE_TENANT;
        // SYSTEM 行固定落 public 主租户（系统资源的家，唯一键防系统资源重名）
        String effectiveTenant = system ? TenantContext.DEFAULT_TENANT : TenantContext.currentTenantId();
        if ("knowledge-files".equals(type) && parentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledge file parentId is required");
        }
        if (parentId != null && mapper.findByIdAndType(parentId, TenantContext.currentTenantId(), "knowledge-bases") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parentId does not exist");
        }
        try {
            PlatformAsset asset = PlatformAsset.builder()
                    .tenantId(effectiveTenant).assetType(type).scope(effectiveScope).parentId(parentId)
                    .name(name.trim()).description(StringUtils.hasText(description) ? description.trim() : null)
                    .status(TYPE_STATUS.get(type)).configJson(normalizeJson(configJson))
                    .createdBy(creator).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
            mapper.insert(asset);
            if ("agents".equals(type)) {
                agentBindingSyncService.syncFromConfig(asset.getTenantId(), asset.getId(), asset.getConfigJson());
            }
            return sanitize(asset);
        } catch (DuplicateKeyException ex) {
            throw duplicate(type, name);
        }
    }

    public PlatformAsset update(String assetType, Long id, String name, String description,
                                String configJson, String status, Long parentId) {
        PlatformAsset existing = getOwned(assetType, id);
        if (StringUtils.hasText(name)) validateName(name);
        validateJson(configJson);
        validateSplitConfig(normalizeType(assetType), configJson);
        if (parentId != null && mapper.findByIdAndType(parentId, TenantContext.currentTenantId(), "knowledge-bases") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parentId does not exist");
        }
        try {
            existing.setName(StringUtils.hasText(name) ? name.trim() : existing.getName());
            existing.setDescription(description == null ? existing.getDescription() :
                    (StringUtils.hasText(description) ? description.trim() : null));
            existing.setConfigJson(configJson == null ? existing.getConfigJson() : preserveSecrets(existing.getConfigJson(), configJson));
            existing.setStatus(StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : existing.getStatus());
            existing.setParentId(parentId == null ? existing.getParentId() : parentId);
            existing.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(existing);
            if ("agents".equals(existing.getAssetType())) {
                // 用合并密钥后的最终 config_json 同步绑定表（****** 不会进表）
                agentBindingSyncService.syncFromConfig(existing.getTenantId(), existing.getId(), existing.getConfigJson());
            }
            if ("model-services".equals(existing.getAssetType())) {
                chatModelRegistry.evict(existing.getId());
            }
            return sanitize(existing);
        } catch (DuplicateKeyException ex) {
            throw duplicate(assetType, name);
        }
    }

    public void delete(String assetType, Long id) {
        PlatformAsset asset = getOwned(assetType, id);
        mapper.deleteById(asset.getId());
        if ("agents".equals(asset.getAssetType())) {
            agentBindingSyncService.deleteAllBindings(asset.getTenantId(), asset.getId());
        }
        if ("model-services".equals(asset.getAssetType())) {
            chatModelRegistry.evict(asset.getId());
        }
        if ("knowledge-bases".equals(asset.getAssetType())) {
            mapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlatformAsset>()
                    .eq(PlatformAsset::getTenantId, asset.getTenantId())
                    .eq(PlatformAsset::getAssetType, "knowledge-files")
                    .eq(PlatformAsset::getParentId, asset.getId()));
            // 删除策略（FR-R5-01）：文档与向量/关键词索引数据保留，归属置空回退为"未归属"，
            // 继续走租户级共享检索——删库不等于删资料；绑定了该库的智能体召回范围自然收窄。
            ingestionJobMapper.clearKnowledgeBase(asset.getTenantId(), asset.getId(), LocalDateTime.now());
            keywordIndexStore.clearKnowledgeBase(asset.getTenantId(), asset.getId());
        }
    }

    public PlatformAsset copyAgent(Long id, String targetName) {
        PlatformAsset source = getOwned("agents", id);
        String base = StringUtils.hasText(targetName) ? targetName.trim() : source.getName() + " - 副本";
        for (int i = 2; i < 100; i++) {
            try {
                return create("agents", i == 2 ? base : base + " (" + i + ")", source.getDescription(),
                        source.getConfigJson(), null, source.getCreatedBy());
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode() != HttpStatus.CONFLICT) throw ex;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "unable to generate a unique agent name");
    }

    public PlatformAsset publishAgent(Long id, java.util.List<String> channels) {
        LinkedHashSet<String> safeChannels = new LinkedHashSet<>();
        if (channels != null) {
            channels.forEach(channel -> {
                if (StringUtils.hasText(channel)) {
                    safeChannels.add(channel.trim());
                }
            });
        }
        if (safeChannels.isEmpty()) {
            safeChannels.add("platform");
        }
        try {
            JsonNode root = readTree(getOwned("agents", id).getConfigJson());
            ObjectNode config = root instanceof ObjectNode objectNode ? objectNode : objectMapper.createObjectNode();
            config.set("publishChannels", objectMapper.valueToTree(safeChannels));
            return update("agents", id, null, null, objectMapper.writeValueAsString(config), "PUBLISHED", null);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid agent config", ex);
        }
    }

    public PlatformAsset testModel(Long id) {
        PlatformAsset model = getOwned("model-services", id);
        String result = probeModel(model.getConfigJson());
        try {
            JsonNode root = readTree(model.getConfigJson());
            ObjectNode config = root instanceof ObjectNode objectNode ? objectNode : objectMapper.createObjectNode();
            config.set("lastTestStatus", new TextNode(result));
            update("model-services", id, null, null, objectMapper.writeValueAsString(config),
                    "AVAILABLE".equals(result) ? result : "UNAVAILABLE", null);
            return get("model-services", id);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid model config", ex);
        }
    }

    private String probeModel(String configJson) {
        try {
            JsonNode root = readTree(configJson);
            String endpoint = root.path("apiUrl").asText(root.path("api_url").asText());
            URL url = URI.create(endpoint).toURL();
            if (!("http".equalsIgnoreCase(url.getProtocol()) || "https".equalsIgnoreCase(url.getProtocol()))) {
                return "UNAVAILABLE";
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            return status >= 200 && status < 500 ? "AVAILABLE" : "UNAVAILABLE";
        } catch (IOException | IllegalArgumentException ex) {
            return "UNAVAILABLE";
        }
    }

    /** 编辑表单不回显密钥，因此“缺省/******”都不能覆盖库中原值。 */
    private String preserveSecrets(String existingJson, String incomingJson) {
        try {
            JsonNode existing = readTree(existingJson);
            ObjectNode incoming = (ObjectNode) objectMapper.readTree(incomingJson);
            if (existing == null || !existing.isObject()) return normalizeJson(incomingJson);
            existing.fieldNames().forEachRemaining(field -> {
                JsonNode oldValue = existing.get(field);
                JsonNode newValue = incoming.get(field);
                if (isSecret(field) && oldValue != null && !oldValue.isNull()
                        && (newValue == null || newValue.isNull() || "******".equals(newValue.asText()))) {
                    incoming.set(field, oldValue);
                }
            });
            return objectMapper.writeValueAsString(incoming);
        } catch (IOException | ClassCastException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config must be valid JSON", ex);
        }
    }
    /**
     * 写路径 ownership 解析：本租户私有行，或系统共享行（scope=SYSTEM，落 public）。
     * 系统行对任意租户的 ADMIN 开放（写接口本就 hasRole('ADMIN')，此处防的是
     * "ADMIN 越过租户改别的租户私有行"——联合查询只放行 SYSTEM 行，租户私有行仍按 tenant 过滤）。
     */
    private PlatformAsset getOwned(String assetType, Long id) {
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid id");
        PlatformAsset asset = mapper.findByIdAndTypeVisible(id, TenantContext.currentTenantId(), normalizeType(assetType));
        if (asset == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "asset not found");
        return asset;
    }

    private PlatformAsset sanitize(PlatformAsset asset) {
        try {
            JsonNode root = readTree(asset.getConfigJson());
            mask(root);
            asset.setConfigJson(root == null ? null : objectMapper.writeValueAsString(root));
        } catch (IOException ignored) {
            // 配置不是 JSON 时按普通文本返回；创建/更新入口已做 JSON 校验
        }
        return asset;
    }

    private void mask(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.fieldNames().forEachRemaining(field -> {
                JsonNode child = object.get(field);
                if (isSecret(field) && child.isTextual() && !child.asText().isBlank()) object.put(field, "******");
                else mask(child);
            });
        } else if (node.isArray()) {
            node.forEach(this::mask);
        }
    }

    private boolean isSecret(String field) {
        String normalized = field.toLowerCase(Locale.ROOT);
        return normalized.contains("apikey") || normalized.contains("api_key") || normalized.contains("secret")
                 || normalized.contains("password") || normalized.contains("credential") || normalized.contains("token");
    }

    private static String normalizeType(String assetType) {
        if (assetType == null || !ASSET_TYPES.contains(assetType)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unsupported platform asset type");
        }
        return assetType;
    }

    private static void validateName(String name) {
        if (!StringUtils.hasText(name) || name.trim().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required and must be at most 128 chars");
        }
    }

    private JsonNode readTree(String json) throws IOException {
        return StringUtils.hasText(json) ? objectMapper.readTree(json) : null;
    }

    private String normalizeJson(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(json));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config must be valid JSON", ex);
        }
    }

    private void validateJson(String json) {
        if (StringUtils.hasText(json)) normalizeJson(json);
    }

    /**
     * 知识库切分配置校验（FR-R8-03）：配置了 split 节时数值必须合法——
     * 保存即拦截，防止脏配置在入库时被静默回退全局默认（用户以为生效了实际没有）。
     */
    private void validateSplitConfig(String type, String configJson) {
        if (!"knowledge-bases".equals(type) || !StringUtils.hasText(configJson)) {
            return;
        }
        try {
            JsonNode split = objectMapper.readTree(configJson).path("split");
            if (!split.isObject()) {
                return;
            }
            int chunkSize = split.path("chunkSize").asInt(0);
            int minChunkSize = split.path("minChunkSize").asInt(0);
            int maxNumChunks = split.path("maxNumChunks").asInt(0);
            if (chunkSize <= 0 || minChunkSize <= 0 || minChunkSize >= chunkSize
                    || maxNumChunks <= 0 || maxNumChunks > 2000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "切分配置非法：需满足 0 < minChunkSize < chunkSize，0 < maxNumChunks ≤ 2000");
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config must be valid JSON", ex);
        }
    }

    private static String keyword(String search) {
        return SqlLikeUtils.escapeForLike(StringUtils.hasText(search) ? search.trim() : "");
    }

    private static ResponseStatusException duplicate(String type, String name) {
        return new ResponseStatusException(HttpStatus.CONFLICT, type + " name already exists: " + name);
    }
}


