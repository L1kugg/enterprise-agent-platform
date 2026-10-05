package com.enterprise.iqk.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ReactTraceStepVO {
    private Integer step;
    private String thought;
    private String action;
    private Map<String, Object> actionInput;
    private Object observation;
    /** 本步真实耗时（毫秒）：从步开始（规划前）到轨迹落笔；工作流引擎与历史数据无此值为 null。 */
    private Long elapsedMs;

    public String getThoughtSummary() {
        return thought;
    }
}
