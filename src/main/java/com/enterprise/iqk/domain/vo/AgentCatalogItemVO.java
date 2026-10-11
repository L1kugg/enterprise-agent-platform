package com.enterprise.iqk.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 普通用户可见的智能体目录项：只含展示字段。
 *  系统提示词、模型绑定、安全防护绑定等配置一律不进此 VO（防止 /platform 之外的配置泄漏）。 */
@Data
@Builder
public class AgentCatalogItemVO {
    private Long id;
    private String name;
    private String description;
    /** 开场白（可空，前端为空时维持默认欢迎区块） */
    private String openingMessage;
    /** 引导问题建议（可空列表） */
    private List<String> openingQuestions;
}
