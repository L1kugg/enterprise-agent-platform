package com.enterprise.iqk.retrieval;

/**
 * 混合检索中按来源可配置的权重。
 */
public record HybridWeights(double vectorWeight, double keywordWeight,
                             double graphWeight, double webWeight) {

    /** 默认权重：向量 0.40 / 关键词 0.25 / 图谱 0.20 / 网络 0.15 */
    public static final HybridWeights DEFAULT = new HybridWeights(0.40, 0.25, 0.20, 0.15);
    /** 语义检索倾向：向量主导，适合事实型/定义型查询 */
    public static final HybridWeights SEMANTIC = new HybridWeights(0.55, 0.20, 0.15, 0.10);
    /** 关键词检索倾向：关键词主导，适合精确匹配/查找型查询 */
    public static final HybridWeights KEYWORD = new HybridWeights(0.20, 0.50, 0.15, 0.15);
    /** 均衡档位：四路等权 */
    public static final HybridWeights BALANCED = new HybridWeights(0.25, 0.25, 0.25, 0.25);

    /** 按四路权重显式构造（不校验也不归一化，使用前由调用方 normalize） */
    public static HybridWeights of(double vector, double keyword, double graph, double web) {
        return new HybridWeights(vector, keyword, graph, web);
    }

    /** 语义检索倾向的预置权重 */
    public static HybridWeights semantic() { return SEMANTIC; }
    /** 关键词检索倾向的预置权重 */
    public static HybridWeights keyword() { return KEYWORD; }
    /** 均衡预置权重 */
    public static HybridWeights balanced() { return BALANCED; }

    /** 归一化使四路权重总和为 1.0；总和非正时回退 BALANCED，已归一化则原样返回 */
    public HybridWeights normalize() {
        double sum = vectorWeight + keywordWeight + graphWeight + webWeight;
        if (sum <= 0) return BALANCED;
        if (Math.abs(sum - 1.0) < 1e-9) return this;
        return new HybridWeights(vectorWeight / sum, keywordWeight / sum, graphWeight / sum, webWeight / sum);
    }
}
