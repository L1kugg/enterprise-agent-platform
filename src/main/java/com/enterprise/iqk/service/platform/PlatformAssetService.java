package com.enterprise.iqk.service.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.enterprise.iqk.security.TenantContext;
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

    private final PlatformAssetMapper mapper;
    private final ObjectMapper objectMapper;

    public long count(String assetType, String search) {
        return mapper.countForList(TenantContext.currentTenantId(), normalizeType(assetType), keyword(search));
    }

    public java.util.List<PlatformAsset> list(String assetType, String search, int page, int pageSize) {
        String type = normalizeType(assetType);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        return mapper.findForList(TenantContext.currentTenantId(), type, keyword(search),
                        safeSize, (long) (safePage - 1) * safeSize)
                .stream().map(this::sanitize).toList();
    }

    public PlatformAsset get(String assetType, Long id) {
        return sanitize(getOwned(assetType, id));
    }

    public PlatformAsset create(String assetType, String name, String description,
                                String configJson, Long parentId, String creator) {
        String type = normalizeType(assetType);
        validateName(name);
        validateJson(configJson);
        if ("knowledge-files".equals(type) && parentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "knowledge file parentId is required");
        }
        if (parentId != null && mapper.findByIdAndType(parentId, TenantContext.currentTenantId(), "knowledge-bases") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parentId does not exist");
        }
        try {
            PlatformAsset asset = PlatformAsset.builder()
                    .tenantId(TenantContext.currentTenantId()).assetType(type).parentId(parentId)
                    .name(name.trim()).description(StringUtils.hasText(description) ? description.trim() : null)
                    .status(TYPE_STATUS.get(type)).configJson(normalizeJson(configJson))
                    .createdBy(creator).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                    .build();
            mapper.insert(asset);
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
        if (parentId != null && mapper.findByIdAndType(parentId, TenantContext.currentTenantId(), "knowledge-bases") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parentId does not exist");
        }
        try {
            existing.setName(StringUtils.hasText(name) ? name.trim() : existing.getName());
            existing.setDescription(description == null ? existing.getDescription() :
                    (StringUtils.hasText(description) ? description.trim() : null));
            existing.setConfigJson(configJson == null ? existing.getConfigJson() : normalizeJson(configJson));
            existing.setStatus(StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : existing.getStatus());
            existing.setParentId(parentId == null ? existing.getParentId() : parentId);
            existing.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(existing);
            return sanitize(existing);
        } catch (DuplicateKeyException ex) {
            throw duplicate(assetType, name);
        }
    }

    public void delete(String assetType, Long id) {
        PlatformAsset asset = getOwned(assetType, id);
        mapper.deleteById(asset.getId());
        if ("knowledge-bases".equals(asset.getAssetType())) {
            mapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlatformAsset>()
                    .eq(PlatformAsset::getTenantId, asset.getTenantId())
                    .eq(PlatformAsset::getAssetType, "knowledge-files")
                    .eq(PlatformAsset::getParentId, asset.getId()));
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

    private PlatformAsset getOwned(String assetType, Long id) {
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid id");
        PlatformAsset asset = mapper.findByIdAndType(id, TenantContext.currentTenantId(), normalizeType(assetType));
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
                || normalized.contains("password") || normalized.contains("token");
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

    private static String keyword(String search) {
        return SqlLikeUtils.escapeForLike(StringUtils.hasText(search) ? search.trim() : "");
    }

    private static ResponseStatusException duplicate(String type, String name) {
        return new ResponseStatusException(HttpStatus.CONFLICT, type + " name already exists: " + name);
    }
}


