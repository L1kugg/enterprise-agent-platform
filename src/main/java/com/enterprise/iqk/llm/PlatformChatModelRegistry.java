package com.enterprise.iqk.llm;

import com.enterprise.iqk.config.properties.AgentRuntimeProperties;
import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.mapper.PlatformAssetMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 平台模型服务 → 独立 ChatClient 注册表：按模型服务资产 id 缓存程序化构建的
 * OpenAI 兼容客户端（OpenAiApi + OpenAiChatModel，Spring AI 1.1.7）。
 * 失效三保险：显式 evict（资产更新/删除时调用）、配置指纹（apiUrl|apiKey|modelName|updatedAt）
 * 变化自动重建、惰性 TTL。必须经 PlatformAssetMapper 原始读取——
 * 走 PlatformAssetService.get() 会把 apiKey 打码成 ******，导致密钥失效调用报错难排查。
 */
@Slf4j
@Component
public class PlatformChatModelRegistry {

    public record BoundChatModel(ChatClient chatClient, String modelName, long assetId) {
    }

    private record CacheEntry(BoundChatModel model, String fingerprint, long loadedAtNanos) {
    }

    private final PlatformAssetMapper assetMapper;
    private final ObjectMapper objectMapper;
    private final AgentRuntimeProperties properties;
    /** 凭据解密（FR-R12-01）：加载模型配置后解密 apiKey 供客户端构建 */
    private final com.enterprise.iqk.security.SecretCipher secretCipher;
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();
    /** 构建串行化的专用锁：ConcurrentHashMap 实例本身禁止作监视器（JLS 监视器语义不保证） */
    private final Object buildLock = new Object();
    private final AtomicLong buildCount = new AtomicLong();

    public PlatformChatModelRegistry(PlatformAssetMapper assetMapper,
                                     ObjectMapper objectMapper,
                                     AgentRuntimeProperties properties,
                                     com.enterprise.iqk.security.SecretCipher secretCipher) {
        this.assetMapper = assetMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.secretCipher = secretCipher;
    }

    /** 解析绑定模型服务为可用 ChatClient；配置缺失/不完整抛 400，调用失败由上层回退策略承接。
     *  可见性含系统共享资产（scope=SYSTEM）：本租户私有 ∪ 系统共享，租户自带优先由绑定 ID 唯一性保证。 */
    public BoundChatModel resolve(Long modelServiceAssetId, String tenantId) {
        if (modelServiceAssetId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "智能体未绑定模型服务");
        }
        PlatformAsset asset = assetMapper.findByIdAndTypeVisible(modelServiceAssetId, tenantId, "model-services");
        if (asset == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "智能体绑定的模型服务不存在");
        }
        JsonNode config = secretCipher.transformFields(parse(asset.getConfigJson()), false);
        String apiUrl = config.path("apiUrl").asText(config.path("api_url").asText("")).trim();
        String apiKey = config.path("apiKey").asText(config.path("api_key").asText("")).trim();
        String modelName = config.path("modelName").asText(
                config.path("modelParams").path("model").asText("")).trim();
        if (apiUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型服务未配置 API 地址");
        }
        if (apiKey.isBlank() || "******".equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型服务 API Key 缺失，请重新保存密钥");
        }
        if (modelName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型服务未配置模型名称");
        }

        String fingerprint = fingerprint(apiUrl, apiKey, modelName, asset.getUpdatedAt());
        CacheEntry entry = cache.get(modelServiceAssetId);
        if (entry != null && entry.fingerprint().equals(fingerprint) && !expired(entry)) {
            return entry.model();
        }
        synchronized (buildLock) {
            entry = cache.get(modelServiceAssetId);
            if (entry != null && entry.fingerprint().equals(fingerprint) && !expired(entry)) {
                return entry.model();
            }
            BoundChatModel built = build(modelServiceAssetId, apiUrl, apiKey, modelName);
            cache.put(modelServiceAssetId, new CacheEntry(built, fingerprint, System.nanoTime()));
            return built;
        }
    }

    /** 资产更新/删除时由 PlatformAssetService 调用，立即失效缓存。 */
    public void evict(Long modelServiceAssetId) {
        if (modelServiceAssetId != null) {
            cache.remove(modelServiceAssetId);
        }
    }

    private BoundChatModel build(long assetId, String apiUrl, String apiKey, String modelName) {
        long startedNs = System.nanoTime();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(apiUrl)
                .apiKey(apiKey)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(modelName).build())
                .build();
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        buildCount.incrementAndGet();
        log.info("构建智能体绑定模型客户端: assetId={}, model={}, 耗时={}ms",
                assetId, modelName, (System.nanoTime() - startedNs) / 1_000_000);
        return new BoundChatModel(chatClient, modelName, assetId);
    }

    private boolean expired(CacheEntry entry) {
        long ttlNanos = properties.getModelCacheTtlSeconds() <= 0
                ? Long.MAX_VALUE
                : properties.getModelCacheTtlSeconds() * 1_000_000_000L;
        return System.nanoTime() - entry.loadedAtNanos() > ttlNanos;
    }

    private String fingerprint(String apiUrl, String apiKey, String modelName, Object updatedAt) {
        return Integer.toHexString(Objects.hash(apiUrl, apiKey, modelName, String.valueOf(updatedAt)));
    }

    private JsonNode parse(String configJson) {
        try {
            return objectMapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型服务配置 JSON 不合法", ex);
        }
    }

    /** 供测试/观测：当前缓存条目数与累计构建次数。 */
    public Map<String, Long> stats() {
        return Map.of("cacheSize", (long) cache.size(), "builds", buildCount.get());
    }
}
