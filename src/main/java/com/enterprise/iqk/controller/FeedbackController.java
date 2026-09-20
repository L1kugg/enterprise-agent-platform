package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.vo.AnswerFeedbackSubmitVO;
import com.enterprise.iqk.domain.vo.Result;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.service.AnswerFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 答案反馈接口（POST /ai/feedback）：采集用户对回答的评分与评语。 */
@RestController
@RequestMapping("/ai/feedback")
@RequiredArgsConstructor
public class FeedbackController {
    private final AnswerFeedbackService answerFeedbackService;

    /** 提交一条答案反馈（评分落库并可转写进评测数据集）。 */
    @PostMapping
    public Result submit(@RequestBody AnswerFeedbackSubmitVO payload) {
        answerFeedbackService.submit(TenantContext.currentTenantId(), payload);
        return Result.ok();
    }
}
