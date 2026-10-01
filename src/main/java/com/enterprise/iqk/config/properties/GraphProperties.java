package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.graph")
/**
 * 知识图谱抽取配置：文档入库成功后用 LLM 从切片文本提取实体/关系/事实的比例与上限。
 * extraction-enabled 只控制入库自动钩子；回填端点（存量文档补图谱）不受它限制。
 */
public class GraphProperties {
    /** 入库后自动抽取开关 */
    private boolean extractionEnabled = true;
    /** 抽取调用走的模型档位（economy = 便宜快速） */
    private String profile = "economy";
    /** 单文档最多抽取实体数 */
    private int maxEntities = 15;
    /** 单文档最多抽取关系数 */
    private int maxRelations = 20;
    /** 单文档最多抽取事实数 */
    private int maxFacts = 15;
    /** 送 LLM 的切片拼接文本长度上限（字符） */
    private int maxInputChars = 6000;
}
