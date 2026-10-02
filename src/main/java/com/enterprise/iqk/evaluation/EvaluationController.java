package com.enterprise.iqk.evaluation;

import com.enterprise.iqk.evaluation.vo.EvalComparisonVO;
import com.enterprise.iqk.evaluation.vo.EvalDatasetCreateVO;
import com.enterprise.iqk.evaluation.vo.EvalDatasetDeleteVO;
import com.enterprise.iqk.evaluation.vo.EvalDatasetVO;
import com.enterprise.iqk.evaluation.vo.EvalRunRequestVO;
import com.enterprise.iqk.evaluation.vo.EvalRunVO;
import com.enterprise.iqk.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 评测 HTTP 接口（/ai/evaluation）：数据集管理、触发评测、结果与对比查询、基线标记、报告导出。 */
@RestController
@RequestMapping("/ai/evaluation")
@RequiredArgsConstructor
public class EvaluationController {
    private final EvaluationService evaluationService;

    /** 创建评测数据集。 */
    @PostMapping("/datasets")
    public EvalDatasetVO createDataset(@RequestBody EvalDatasetCreateVO request) {
        return evaluationService.createDataset(TenantContext.currentTenantId(), request);
    }

    /** 列出租户下全部数据集。 */
    @GetMapping("/datasets")
    public List<EvalDatasetVO> listDatasets() {
        return evaluationService.listDatasets(TenantContext.currentTenantId());
    }

    /** 删除评测数据集：联动清掉其用例、运行与结果明细。 */
    @DeleteMapping("/datasets/{datasetId}")
    public EvalDatasetDeleteVO deleteDataset(@PathVariable("datasetId") String datasetId) {
        return evaluationService.deleteDataset(TenantContext.currentTenantId(), datasetId);
    }

    /** 触发一轮评测：逐 case 调用真实 RAG 链路并打分落库。 */
    @PostMapping("/datasets/{datasetId}/runs")
    public EvalRunVO triggerRun(@PathVariable("datasetId") String datasetId,
                                @RequestBody(required = false) EvalRunRequestVO request) {
        return evaluationService.triggerRun(TenantContext.currentTenantId(), datasetId, request);
    }

    /** 契约式触发评测：datasetId 放请求体，供脚本 / CI 调用。 */
    @PostMapping("/runs")
    public EvalRunVO triggerRunByContract(@RequestBody EvalRunRequestVO request) {
        if (request == null || !org.springframework.util.StringUtils.hasText(request.getDatasetId())) {
            throw new IllegalArgumentException("datasetId is required");
        }
        return evaluationService.triggerRun(TenantContext.currentTenantId(), request.getDatasetId(), request);
    }

    /** 最新一轮与基线的对比。 */
    @GetMapping("/datasets/{datasetId}/comparison")
    public EvalComparisonVO compareLatest(@PathVariable("datasetId") String datasetId) {
        return evaluationService.compareLatest(TenantContext.currentTenantId(), datasetId);
    }

    /** 查询单轮评测详情。 */
    @GetMapping("/runs/{runId}")
    public EvalRunVO getRun(@PathVariable("runId") String runId) {
        return evaluationService.getRun(TenantContext.currentTenantId(), runId);
    }

    /** 把该轮运行标记为数据集基线。 */
    @PostMapping("/runs/{runId}/baseline")
    public EvalRunVO markBaseline(@PathVariable("runId") String runId) {
        return evaluationService.markBaseline(TenantContext.currentTenantId(), runId);
    }

    /** 导出 Markdown 评测报告（附件下载）。 */
    @GetMapping(value = "/runs/{runId}/report", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> exportReport(@PathVariable("runId") String runId) {
        String report = evaluationService.exportReport(TenantContext.currentTenantId(), runId);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"rag-evaluation-" + runId + ".md\"")
                .body(report);
    }
}
