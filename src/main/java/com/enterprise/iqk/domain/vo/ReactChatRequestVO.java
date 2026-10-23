package com.enterprise.iqk.domain.vo;

import lombok.Data;

@Data
public class ReactChatRequestVO {
    private String prompt;
    private String chatId;
    private String modelProfile;
    /** 绑定平台智能体（platform_asset agents id）；null 走原有无智能体路径 */
    private Long agentId;
    /** 调用来源：platform=真实会话，debug=工作台调试；null 记为 platform */
    private String channel;
    /** 用户在对话栏手选的模型服务 id（platform_asset model-services id）；
     * null 走默认档位路由；智能体已绑定模型时以绑定为准 */
    private Long modelServiceId;
}

