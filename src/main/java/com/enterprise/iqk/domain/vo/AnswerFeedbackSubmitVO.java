package com.enterprise.iqk.domain.vo;

import lombok.Data;

@Data
public class AnswerFeedbackSubmitVO {
    private String chatId;
    private String sessionId;
    private String branchId;
    private String messageId;
    private Integer rating;
    private String comment;
    /** 点踩原因（低分反馈选填）：白名单校验后落库，供业务效果看板聚合 */
    private String reason;
    private String question;
    private String answer;
}
