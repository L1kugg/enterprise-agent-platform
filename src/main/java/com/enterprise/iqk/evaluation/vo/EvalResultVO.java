package com.enterprise.iqk.evaluation.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 单 case 评测结果视图：答案与引用证据快照 + 四项得分与耗时。 */
@Data
@Builder
public class EvalResultVO {
    private String resultId;
    private String caseId;
    private String status;
    private String question;
    private String answer;
    private List<String> citations;
    private List<String> evidence;
    private double retrievalHit;
    private double citationCoverage;
    private double keywordScore;
    private double answerFaithfulness;
    private double score;
    private long latencyMs;
    private String errorMessage;
}
