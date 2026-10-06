package com.enterprise.iqk.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 工作流编排层配置：ReAct 循环步数上限等运行时参数。
 * 深度研究类任务与简单问答不应共用同一个硬编码上限，交给环境按需调节。
 */
@Data
@ConfigurationProperties(prefix = "app.workflow")
public class AgentWorkflowProperties {

    /** ReAct 循环（含流式）最大轮次，用尽仍未 finish 则强制总结成稿 */
    private int reactMaxSteps = 6;
}
