package com.enterprise.iqk.domain.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** agent_*_binding 六张表的通用行载体；channel 仅 agent_publish_channel 使用，extra_json 存透传描述等。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentBindingRow {
    private Long resourceId;
    private String resourceType;
    private Integer bindingOrder;
    private Boolean executionConfirm;
    private String channel;
    private String extraJson;
}
