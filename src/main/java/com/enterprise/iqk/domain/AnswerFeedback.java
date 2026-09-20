package com.enterprise.iqk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 答案反馈（answer_feedback 表）：用户对一次回答的评分记录。
 * 除落库统计外，高分/低分反馈会被 AnswerFeedbackService 转写进评测数据集。
 */
@TableName("answer_feedback")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerFeedback {
    /** 数据库自增主键（内部使用） */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户 ID */
    private String tenantId;
    /** 反馈归属的会话 ID */
    private String chatId;
    /** 会话级标识（可空） */
    private String sessionId;
    /** 会话树分支 ID（可空） */
    private String branchId;
    /** 被评价的具体消息 ID（可空） */
    private String messageId;
    /** 评分 1~5：≥4 与 ≤2 的档位会触发评测数据集转写 */
    private Integer rating;
    /** 用户评语（截断到 1024 字符） */
    private String comment;
    /** 问题快照：转写数据集时作为 question 字段 */
    private String questionText;
    /** 答案快照：转写数据集时作为 answer 字段（截断到 1500 字符） */
    private String answerText;
    private LocalDateTime createdAt;
}
