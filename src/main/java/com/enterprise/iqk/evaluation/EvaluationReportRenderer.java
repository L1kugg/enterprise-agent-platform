package com.enterprise.iqk.evaluation;

import com.enterprise.iqk.evaluation.vo.EvalMetricSummaryVO;
import com.enterprise.iqk.evaluation.vo.EvalResultVO;
import com.enterprise.iqk.evaluation.vo.EvalRunVO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 评测报告渲染器：把一轮 EvalRunVO 渲染为 Markdown 报告，
 * 内容固定三段：运行元信息、汇总指标表、逐 case 得分明细表。
 * 产物供下载归档或随发布附件，不做任何分数计算。
 */
@Component
public class EvaluationReportRenderer {

    /** 渲染 Markdown 报告文本。 */
    public String render(EvalRunVO run) {
        List<String> lines = new ArrayList<>();
        lines.add("# RAG Evaluation Report");
        lines.add("");
        lines.add("- Run ID: `" + run.getRunId() + "`");
        lines.add("- Dataset ID: `" + run.getDatasetId() + "`");
        lines.add("- Tenant: `" + run.getTenantId() + "`");
        lines.add("- Model Profile: `" + run.getModelProfile() + "`");
        lines.add("- Status: `" + run.getStatus() + "`");
        lines.add("- Generated At: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        lines.add("");
        lines.add("## Metrics");
        lines.add("");
        lines.add("| Metric | Value |");
        lines.add("| --- | ---: |");
        EvalMetricSummaryVO m = run.getMetrics();
        lines.add("| Run Score | " + pct(m.getRunScore()) + " |");
        lines.add("| Retrieval Hit Rate | " + pct(m.getRetrievalHitRate()) + " |");
        lines.add("| Citation Coverage | " + pct(m.getCitationCoverageRate()) + " |");
        lines.add("| Answer Faithfulness | " + pct(m.getAnswerFaithfulnessScore()) + " |");
        lines.add("| Avg Latency | " + String.format(Locale.ROOT, "%.1f ms", m.getAvgLatencyMs()) + " |");
        lines.add("| Failure Rate | " + pct(m.getFailureRate()) + " |");
        lines.add("");
        lines.add("## Cases");
        lines.add("");
        lines.add("| Case | Status | Score | Retrieval | Citation | Faithfulness | Latency |");
        lines.add("| --- | --- | ---: | ---: | ---: | ---: | ---: |");
        for (EvalResultVO result : run.getResults()) {
            lines.add("| `" + result.getCaseId() + "` | " + result.getStatus()
                    + " | " + pct(result.getScore())
                    + " | " + pct(result.getRetrievalHit())
                    + " | " + pct(result.getCitationCoverage())
                    + " | " + pct(result.getAnswerFaithfulness())
                    + " | " + result.getLatencyMs() + " ms |");
        }
        lines.add("");
        return String.join("\n", lines);
    }

    /** 0~1 分值格式化为百分数文本。 */
    private String pct(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100.0);
    }
}
