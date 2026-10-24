package com.enterprise.iqk.memory;

import com.enterprise.iqk.config.properties.MemoryProperties;
import com.enterprise.iqk.util.VectorDistanceUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆向量助手：记忆写入时缓存 embedding（embedding_json 列），
 * 召回时把查询向量与缓存向量做余弦相似，供词面 + 语义融合精排。
 *
 * 失败语义统一为"返回 null / 占位 null"：embedding 服务不可用、超时、
 * 返回维度异常都只降级为纯词面召回，绝不向记忆主链路抛异常。
 * 所有调用带计时与失败计数，语义链路的健康状态在指标里可见。
 */
@Slf4j
@Component
public class MemoryEmbeddingHelper {

    /** 向量 JSON 反序列化用（ObjectMapper 线程安全，静态共享避免召回路径重复构建） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenAiEmbeddingModel embeddingModel;
    private final MemoryProperties properties;
    private final MeterRegistry meterRegistry;

    public MemoryEmbeddingHelper(OpenAiEmbeddingModel embeddingModel,
                                 MemoryProperties properties,
                                 MeterRegistry meterRegistry) {
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /** 语义链路是否启用（总开关；关闭后所有向量调用短路）。 */
    public boolean isEnabled() {
        return properties.isSemanticEnabled();
    }

    /** 单条文本取向量，失败返回 null（降级信号）。 */
    public float[] embed(String text) {
        if (!isEnabled() || !StringUtils.hasText(text)) {
            return null;
        }
        long start = System.nanoTime();
        try {
            float[] vector = embeddingModel.embed(text);
            recordLatency(start);
            return vector == null || vector.length == 0 ? null : vector;
        } catch (Exception ex) {
            recordLatency(start);
            meterRegistry.counter("memory.embed.failures").increment();
            log.warn("memory embedding failed (fall back to lexical): reason={}", ex.toString());
            return null;
        }
    }

    /** 单条文本取向量并序列化为 JSON 数组（写入 embedding_json 列），失败返回 null。 */
    public String embedToJson(String text) {
        return toJson(embed(text));
    }

    /**
     * 批量取向量并逐条序列化（一次 API 调用），失败或未启用时返回与入参等长的 null 占位列表，
     * 保证调用方可按下标对位消费、无需处理长度不齐。
     */
    public List<String> embedAllToJson(List<String> texts) {
        int size = texts == null ? 0 : texts.size();
        List<String> placeholders = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            placeholders.add(null);
        }
        if (!isEnabled() || size == 0) {
            return placeholders;
        }
        long start = System.nanoTime();
        try {
            List<float[]> vectors = embeddingModel.embed(texts);
            recordLatency(start);
            for (int i = 0; i < size; i++) {
                float[] vector = vectors == null || i >= vectors.size() ? null : vectors.get(i);
                placeholders.set(i, vector == null || vector.length == 0 ? null : toJson(vector));
            }
        } catch (Exception ex) {
            recordLatency(start);
            meterRegistry.counter("memory.embed.failures").increment();
            log.warn("memory batch embedding failed (fall back to lexical): size={}, reason={}",
                    size, ex.toString());
        }
        return placeholders;
    }

    /** 反序列化 embedding_json 列，任何异常（空值/格式坏/维度漂移）返回 null。 */
    public static float[] fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isArray() || node.isEmpty()) {
                return null;
            }
            float[] vector = new float[node.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) node.get(i).asDouble();
            }
            return vector;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    /** 向量序列化为 JSON 数组字符串（embedding_json 列格式），null 向量返回 null。 */
    public static String toJson(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * 安全余弦相似度 [-1,1]，零向量或维度不齐返回 0（视为无语义信号）。
     * VectorDistanceUtils.cosineDistance 实际返回的就是相似度（历史命名），
     * 这里包一层把非法输入（仅 IllegalArgumentException）压成 0，供打分路径无条件使用。
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        try {
            return VectorDistanceUtils.cosineDistance(a, b);
        } catch (IllegalArgumentException ex) {
            return 0.0;
        }
    }

    private void recordLatency(long startNanos) {
        meterRegistry.timer("memory.embed.latency").record(
                Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
