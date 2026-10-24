package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.vo.Result;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.FeedbackInsightsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务效果看板（GET /ai/feedback/insights）：好评率/点踩原因等反馈聚合，
 * 供用量页管理员区块消费。管理视角端点，与平台统计看板同权限口径。
 */
@RestController
@RequestMapping("/ai/feedback")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','OPS')")
public class FeedbackInsightsController {

    private final FeedbackInsightsService feedbackInsightsService;

    /** 窗口默认 7 天、钳制 [1,90]。 */
    @GetMapping("/insights")
    public Result insights(@RequestParam(name = "days", defaultValue = "7") int days) {
        Result result = Result.ok();
        result.setData(feedbackInsightsService.insights(TenantContext.currentTenantId(), days));
        return result;
    }
}
