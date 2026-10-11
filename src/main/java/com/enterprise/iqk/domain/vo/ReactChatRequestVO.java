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
}

