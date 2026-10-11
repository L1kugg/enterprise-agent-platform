package com.enterprise.iqk.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ReactChatResponseVO {
    private Integer ok;
    private String msg;
    private String chatId;
    private String answer;
    private Boolean fallback;
    private List<String> citations;
    private List<String> evidence;
    private String routeProfile;
    private String routeReason;
    private String routeCostTier;
    private String experimentKey;
    private String experimentVariant;
    private Integer experimentBucket;
    private List<ReactTraceStepVO> trace;
    /** 本次注入规划/成稿上下文的记忆标签（type + 内容摘要），观测记忆闭环是否生效 */
    private List<String> memoryUsed;
    /** 生效的智能体 id（未绑定为 null） */
    private Long agentId;
    /** 会话前安全拦截是否命中（命中时 answer=拦截文案，未发生模型调用） */
    private Boolean safetyBlocked;
    /** 拦截文案（含命中防护的 blockMessage） */
    private String blockMessage;
    /** 实际使用的模型名（绑定模型或路由模型） */
    private String modelName;
    /** 模型来源：bound=智能体绑定模型服务，router=档位路由 */
    private String modelSource;
    /** 绑定模型调用失败后是否回退到了档位路由 */
    private Boolean modelFallback;
}
