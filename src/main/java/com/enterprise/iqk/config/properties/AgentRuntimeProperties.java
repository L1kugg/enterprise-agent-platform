package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 智能体运行时行为配置（绑定模型回退策略、缓存、记忆轮数上限等）。 */
@Data
@ConfigurationProperties(prefix = "app.agent")
public class AgentRuntimeProperties {
    /** 智能体绑定模型调用失败后是否回退到既有模型档位路由；false 时直接报错不回退 */
    private boolean modelFallbackEnabled = true;
    /** 绑定模型客户端缓存 TTL（秒）；资产配置指纹变化时无条件重建 */
    private long modelCacheTtlSeconds = 600;
    /** memoryTurns 有效上限：超出按上限截断，防止会话提示词膨胀 */
    private int maxMemoryTurns = 30;
    /** 调用日志 prompt 摘录长度（字符） */
    private int invocationPromptExcerptChars = 500;
}
