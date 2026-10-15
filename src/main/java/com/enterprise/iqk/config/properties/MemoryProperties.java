package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.memory")
/**
 * 记忆子系统配置：证据源联动失效、同层近重复抑制与召回语义精排。
 * 三个增强全部允许独立关闭；语义相关开关在任何 embedding 调用失败时
 * 自动回退纯词面行为，不阻塞对话与 RAG 主链路。
 */
public class MemoryProperties {
    /** 文档删除时级联失效其沉淀的 fact 记忆（stale 软失效，保留审计轨迹） */
    private boolean cascadeInvalidateEnabled = true;
    /** 召回侧同层近重复抑制：内容规范化后相似度达阈值只保留排序靠前（综合分更高）的一条 */
    private boolean dedupEnabled = true;
    /** 近重复判定阈值：规范化内容字符 bigram 的 Jaccard 相似度上限 */
    private double nearDuplicateThreshold = 0.75;
    /** 语义精排总开关：写入时缓存向量、召回时与查询向量余弦融合；关闭或调用失败回退纯词面 */
    private boolean semanticEnabled = true;
    /** 词面相关性与语义相似度的融合权重（0=纯词面，1=纯语义） */
    private double semanticBlendWeight = 0.5;
    /** long 画像写入侧语义去重阈值：候选与既有记忆余弦相似度达到即视为重复（仅比对已缓存向量的记录） */
    private double semanticDedupThreshold = 0.88;
}
