package com.enterprise.iqk.evaluation.vo;

import com.enterprise.iqk.retrieval.RetrievalResultItem;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 单 case 评测结果视图：答案快照、Top-K 检索快照与各分项得分。 */
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
    private List<RetrievalResultItem> retrievedResults;
    private double retrievalHit;
    private boolean retrievalMetricsApplicable;
    private String retrievalMetricLevel;
    private double recallAtK;
    private double mrrAtK;
    private double precisionAtK;
    private double citationCoverage;
    private double keywordScore;
    private double citationMarkerCoverage;
    private double score;
    private boolean keywordScoreApplicable;
    private boolean citationCoverageApplicable;
    private long latencyMs;
    private String errorMessage;
}
