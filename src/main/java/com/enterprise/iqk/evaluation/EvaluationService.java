package com.enterprise.iqk.evaluation;

import com.enterprise.iqk.evaluation.vo.EvalCaseCreateVO;
import com.enterprise.iqk.evaluation.vo.EvalComparisonVO;
import com.enterprise.iqk.evaluation.vo.EvalDatasetCreateVO;
import com.enterprise.iqk.evaluation.vo.EvalDatasetDeleteVO;
import com.enterprise.iqk.evaluation.vo.EvalDatasetVO;
import com.enterprise.iqk.evaluation.vo.EvalMetricSummaryVO;
import com.enterprise.iqk.evaluation.vo.EvalResultVO;
import com.enterprise.iqk.evaluation.vo.EvalRunRequestVO;
import com.enterprise.iqk.evaluation.vo.EvalRunVO;
import com.enterprise.iqk.rag.HybridRagAnswerService;
import com.enterprise.iqk.retrieval.RetrievalResultItem;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.util.ConversationIdHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 评测主服务：串起数据集 → 用例 → 运行 → 结果四层数据模型。
 * 核心链路：加载 eval_case → 逐条调用 HybridRagAnswerService.answer()
 * （conversationId 统一用 ConversationIdHelper.build("eval", chatId)，与线上会话隔离）
 * → EvaluationScorer 打分 → 逐条落 eval_result → 汇总指标回写 eval_run。
 * 单个 case 失败不中断整轮，只记 FAILED 与错误信息，由失败率指标暴露。
 */
@Service
@RequiredArgsConstructor
public class EvaluationService {
    private final EvalDatasetMapper evalDatasetMapper;
    private final EvalCaseMapper evalCaseMapper;
    private final EvalRunMapper evalRunMapper;
    private final EvalResultMapper evalResultMapper;
    private final HybridRagAnswerService hybridRagAnswerService;
    private final ObjectMapper objectMapper;
    private final EvaluationScorer evaluationScorer;
    private final EvaluationSummaryCalculator summaryCalculator;
    private final EvaluationReportRenderer evaluationReportRenderer;
    /** 创建评测数据集：校验后写 eval_dataset / eval_case，用例缺 caseId 时按序生成 case-###。 */
    public EvalDatasetVO createDataset(String tenantId, EvalDatasetCreateVO request) {
        if (request == null) {
            throw new IllegalArgumentException("评测集内容不能为空");
        }
        if (!StringUtils.hasText(request.getName())) {
            throw new IllegalArgumentException("评测集名称不能为空");
        }
        if (request.getCases() == null || request.getCases().isEmpty()) {
            throw new IllegalArgumentException("评测集至少需要一条测试题");
        }
        String tenant = TenantContext.normalize(tenantId);
        String datasetId = "eval-ds-" + shortUuid();
        LocalDateTime now = LocalDateTime.now();
        EvalDatasetRecord dataset = EvalDatasetRecord.builder()
                .datasetId(datasetId)
                .tenantId(tenant)
                .name(request.getName().trim())
                .description(emptyIfBlank(request.getDescription()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        evalDatasetMapper.insert(dataset);

        int order = 0;
        for (EvalCaseCreateVO item : request.getCases()) {
            if (item == null || !StringUtils.hasText(item.getQuestion())) {
                throw new IllegalArgumentException("测试题的问题不能为空");
            }
            String caseId = StringUtils.hasText(item.getCaseId())
                    ? item.getCaseId().trim()
                    : "case-" + String.format(Locale.ROOT, "%03d", order + 1);
            evalCaseMapper.insert(EvalCaseRecord.builder()
                    .caseId(caseId)
                    .datasetId(datasetId)
                    .tenantId(tenant)
                    .category(emptyIfBlank(item.getCategory()))
                    .chatId(emptyIfBlank(item.getChatId()))
                    .questionText(item.getQuestion().trim())
                    .expectedCitationsJson(writeJsonList(item.getExpectedCitations()))
                    .expectedDocumentIdsJson(writeJsonList(item.getExpectedDocumentIds()))
                    .expectedChunkIdsJson(writeJsonList(item.getExpectedChunkIds()))
                    .expectedKeywordsJson(writeJsonList(item.getExpectedKeywords()))
                    .forbiddenKeywordsJson(writeJsonList(item.getForbiddenKeywords()))
                    .sortOrder(order++)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        return toDatasetVO(dataset, order);
    }

    /** 列出租户下全部数据集（附各自用例数）。 */
    public List<EvalDatasetVO> listDatasets(String tenantId) {
        String tenant = TenantContext.normalize(tenantId);
        return evalDatasetMapper.findByTenant(tenant).stream()
                .map(record -> toDatasetVO(record, evalCaseMapper.findByTenantAndDatasetId(tenant, record.getDatasetId()).size()))
                .toList();
    }

    /**
     * 删除评测数据集：四张表之间没有外键、只有 dataset_id 的逻辑引用，
     * 在同一事务里按 results → runs → cases → dataset 的顺序清干净，
     * 避免半删状态留下"幽灵"运行；返回各层清理条数供前端提示。
     */
    @Transactional
    public EvalDatasetDeleteVO deleteDataset(String tenantId, String datasetId) {
        String tenant = TenantContext.normalize(tenantId);
        EvalDatasetRecord dataset = requireDataset(tenant, datasetId);
        int results = evalResultMapper.deleteByTenantAndDatasetId(tenant, dataset.getDatasetId());
        int runs = evalRunMapper.deleteByTenantAndDatasetId(tenant, dataset.getDatasetId());
        int cases = evalCaseMapper.deleteByTenantAndDatasetId(tenant, dataset.getDatasetId());
        evalDatasetMapper.deleteByTenantAndDatasetId(tenant, dataset.getDatasetId());
        return EvalDatasetDeleteVO.builder()
                .datasetName(dataset.getName())
                .cases(cases)
                .runs(runs)
                .results(results)
                .build();
    }

    /**
     * 触发一轮评测：先落 RUNNING 状态的 eval_run，逐 case 同步执行后
     * 汇总指标回写并把状态置为 SUCCESS；modelProfile 缺省 balanced。
     */
    public EvalRunVO triggerRun(String tenantId, String datasetId, EvalRunRequestVO request) {
        String tenant = TenantContext.normalize(tenantId);
        EvalDatasetRecord dataset = requireDataset(tenant, datasetId);
        List<EvalCaseRecord> cases = evalCaseMapper.findByTenantAndDatasetId(tenant, datasetId);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("该评测集没有测试题，无法运行");
        }

        String runId = "eval-run-" + shortUuid();
        LocalDateTime now = LocalDateTime.now();
        String modelProfile = request == null || !StringUtils.hasText(request.getModelProfile())
                ? "balanced"
                : request.getModelProfile().trim();
        EvalRunRecord run = EvalRunRecord.builder()
                .runId(runId)
                .datasetId(dataset.getDatasetId())
                .tenantId(tenant)
                .status("RUNNING")
                .modelProfile(modelProfile)
                .totalCases(cases.size())
                .passedCases(0)
                .runScore(0.0)
                .retrievalHitRate(0.0)
                .retrievalMetricsCases(0)
                .retrievalMetricLevel("none")
                .recallAtKRate(0.0)
                .mrrAtK(0.0)
                .precisionAtKRate(0.0)
                .citationCoverageRate(0.0)
                .citationMarkerCoverageRate(0.0)
                .avgLatencyMs(0.0)
                .failureRate(0.0)
                .startedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        evalRunMapper.insert(run);

        List<EvalResultRecord> results = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            results.add(runCase(tenant, runId, dataset.getDatasetId(), cases.get(i), request, i));
        }

        EvalMetricSummaryVO summary = summaryCalculator.summarize(results);
        run.setStatus("SUCCESS");
        run.setPassedCases(summary.getPassedCases());
        run.setRunScore(summary.getRunScore());
        run.setRetrievalHitRate(summary.getRetrievalHitRate());
        run.setRetrievalMetricsCases(summary.getRetrievalMetricsCases());
        run.setRetrievalMetricLevel(summary.getRetrievalMetricLevel());
        run.setRecallAtKRate(summary.getRecallAtKRate());
        run.setMrrAtK(summary.getMrrAtK());
        run.setPrecisionAtKRate(summary.getPrecisionAtKRate());
        run.setCitationCoverageRate(summary.getCitationCoverageRate());
        run.setCitationMarkerCoverageRate(summary.getCitationMarkerCoverageRate());
        run.setAvgLatencyMs(summary.getAvgLatencyMs());
        run.setFailureRate(summary.getFailureRate());
        run.setFinishedAt(LocalDateTime.now());
        run.setUpdatedAt(run.getFinishedAt());
        evalRunMapper.updateById(run);

        return toRunVO(run, results);
    }

    /** 查询单轮评测详情（附全部 case 级结果）。 */
    public EvalRunVO getRun(String tenantId, String runId) {
        String tenant = TenantContext.normalize(tenantId);
        EvalRunRecord run = requireRun(tenant, runId);
        return toRunVO(run, evalResultMapper.findByTenantAndRunId(tenant, runId));
    }

    /** 把某轮运行标记为数据集基线，供 compareLatest 对比。 */
    public EvalRunVO markBaseline(String tenantId, String runId) {
        String tenant = TenantContext.normalize(tenantId);
        EvalRunRecord run = requireRun(tenant, runId);
        int updated = evalDatasetMapper.updateBaselineRunId(tenant, run.getDatasetId(), runId);
        if (updated <= 0) {
            throw new IllegalArgumentException("评测集不存在");
        }
        return getRun(tenant, runId);
    }

    /** 最新一轮与基线对比：优先用数据集标记的基线运行，未标记则退化为最近两轮互比。 */
    public EvalComparisonVO compareLatest(String tenantId, String datasetId) {
        String tenant = TenantContext.normalize(tenantId);
        EvalDatasetRecord dataset = requireDataset(tenant, datasetId);
        List<EvalRunRecord> recent = evalRunMapper.findRecentByDatasetId(tenant, datasetId, 2);
        EvalRunVO current = recent.isEmpty()
                ? null
                : toRunVO(recent.get(0), evalResultMapper.findByTenantAndRunId(tenant, recent.get(0).getRunId()));

        EvalRunVO baseline = null;
        if (StringUtils.hasText(dataset.getBaselineRunId())) {
            EvalRunRecord baselineRun = evalRunMapper.findByTenantAndRunId(tenant, dataset.getBaselineRunId());
            if (baselineRun != null) {
                baseline = toRunVO(baselineRun, evalResultMapper.findByTenantAndRunId(tenant, baselineRun.getRunId()));
            }
        }
        if (baseline == null && recent.size() > 1) {
            EvalRunRecord previous = recent.get(1);
            baseline = toRunVO(previous, evalResultMapper.findByTenantAndRunId(tenant, previous.getRunId()));
        }

        return EvalComparisonVO.builder()
                .dataset(toDatasetVO(dataset, evalCaseMapper.findByTenantAndDatasetId(tenant, datasetId).size()))
                .baseline(baseline)
                .current(current)
                .build();
    }

    /** 导出 Markdown 评测报告（委托 EvaluationReportRenderer）。 */
    public String exportReport(String tenantId, String runId) {
        return evaluationReportRenderer.render(getRun(tenantId, runId));
    }

    /**
     * 执行单个用例：真实走一遍 RAG 回答链路，空答案或异常都记 FAILED；
     * 无论成败都打分并落一条 eval_result。
     */
    private EvalResultRecord runCase(String tenant,
                                     String runId,
                                     String datasetId,
                                     EvalCaseRecord evalCase,
                                     EvalRunRequestVO request,
                                     int index) {
        long startedNs = System.nanoTime();
        String status = "SUCCESS";
        String answer = "";
        String errorMessage = "";
        List<String> citations = List.of();
        List<String> evidence = List.of();
        List<RetrievalResultItem> retrievalResults = List.of();

        try {
            String chatId = resolveChatId(runId, evalCase, request, index);
            HybridRagAnswerService.HybridRagResult result = hybridRagAnswerService.answer(
                    evalCase.getQuestionText(),
                    tenant,
                    chatId,
                    ConversationIdHelper.build("eval", chatId),
                    request == null ? null : request.getModelProfile()
            );
            answer = emptyIfBlank(result.getAnswer());
            citations = EvalCitationFormatter.toCitationStrings(result.getCitations());
            evidence = EvalCitationFormatter.toEvidenceStrings(result.getEvidence());
            retrievalResults = result.getRetrievalResults() == null
                    ? List.of() : result.getRetrievalResults();
            if (!StringUtils.hasText(answer)) {
                status = "FAILED";
                errorMessage = "empty answer";
            }
        } catch (RuntimeException ex) {
            status = "FAILED";
            errorMessage = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "evaluation case failed";
        }

        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
        EvaluationScorer.CaseScores scores = evaluationScorer.scoreCase(
                evalCase,
                answer,
                citations,
                evidence,
                retrievalResults,
                "FAILED".equals(status)
        );
        EvalResultRecord record = EvalResultRecord.builder()
                .resultId("eval-result-" + shortUuid())
                .runId(runId)
                .datasetId(datasetId)
                .caseId(evalCase.getCaseId())
                .tenantId(tenant)
                .status(status)
                .questionText(evalCase.getQuestionText())
                .answerText(answer)
                .citationsJson(writeJsonList(citations))
                .evidenceJson(writeJsonList(evidence))
                .retrievedResultsJson(writeRetrievalResults(retrievalResults))
                .retrievalHit(scores.retrievalHit())
                .retrievalMetricsApplicable(scores.retrievalMetricsApplicable())
                .retrievalMetricLevel(scores.retrievalMetricLevel())
                .recallAtK(scores.recallAtK())
                .mrrAtK(scores.mrrAtK())
                .precisionAtK(scores.precisionAtK())
                .citationCoverage(scores.citationCoverage())
                .keywordScore(scores.keywordScore())
                .citationMarkerCoverage(scores.citationMarkerCoverage())
                .score(scores.score())
                .keywordScoreApplicable(scores.keywordScoreApplicable())
                .citationCoverageApplicable(scores.citationCoverageApplicable())
                .latencyMs(latencyMs)
                .errorMessage(errorMessage)
                .createdAt(LocalDateTime.now())
                .build();
        evalResultMapper.insert(record);
        return record;
    }

    private EvalDatasetRecord requireDataset(String tenant, String datasetId) {
        if (!StringUtils.hasText(datasetId)) {
            throw new IllegalArgumentException("评测集 ID 不能为空");
        }
        EvalDatasetRecord dataset = evalDatasetMapper.findByTenantAndDatasetId(tenant, datasetId.trim());
        if (dataset == null) {
            throw new IllegalArgumentException("评测集不存在");
        }
        return dataset;
    }

    private EvalRunRecord requireRun(String tenant, String runId) {
        if (!StringUtils.hasText(runId)) {
            throw new IllegalArgumentException("运行记录 ID 不能为空");
        }
        EvalRunRecord run = evalRunMapper.findByTenantAndRunId(tenant, runId.trim());
        if (run == null) {
            throw new IllegalArgumentException("运行记录不存在");
        }
        return run;
    }

    private EvalDatasetVO toDatasetVO(EvalDatasetRecord record, int caseCount) {
        return EvalDatasetVO.builder()
                .datasetId(record.getDatasetId())
                .tenantId(record.getTenantId())
                .name(record.getName())
                .description(record.getDescription())
                .baselineRunId(record.getBaselineRunId())
                .caseCount(caseCount)
                .createdAt(formatTime(record.getCreatedAt()))
                .updatedAt(formatTime(record.getUpdatedAt()))
                .build();
    }

    private EvalRunVO toRunVO(EvalRunRecord run, List<EvalResultRecord> results) {
        EvalMetricSummaryVO metrics = EvalMetricSummaryVO.builder()
                .totalCases(intOrZero(run.getTotalCases()))
                .passedCases(intOrZero(run.getPassedCases()))
                .runScore(valueOrZero(run.getRunScore()))
                .retrievalHitRate(valueOrZero(run.getRetrievalHitRate()))
                .retrievalMetricsCases(intOrZero(run.getRetrievalMetricsCases()))
                .retrievalMetricLevel(emptyIfBlank(run.getRetrievalMetricLevel()))
                .recallAtKRate(valueOrZero(run.getRecallAtKRate()))
                .mrrAtK(valueOrZero(run.getMrrAtK()))
                .precisionAtKRate(valueOrZero(run.getPrecisionAtKRate()))
                .citationCoverageRate(valueOrZero(run.getCitationCoverageRate()))
                .citationMarkerCoverageRate(valueOrZero(run.getCitationMarkerCoverageRate()))
                .avgLatencyMs(valueOrZero(run.getAvgLatencyMs()))
                .failureRate(valueOrZero(run.getFailureRate()))
                .build();
        return EvalRunVO.builder()
                .runId(run.getRunId())
                .datasetId(run.getDatasetId())
                .tenantId(run.getTenantId())
                .status(run.getStatus())
                .modelProfile(run.getModelProfile())
                .metrics(metrics)
                .results(results == null ? List.of() : results.stream().map(this::toResultVO).toList())
                .errorMessage(run.getErrorMessage())
                .startedAt(formatTime(run.getStartedAt()))
                .finishedAt(formatTime(run.getFinishedAt()))
                .createdAt(formatTime(run.getCreatedAt()))
                .build();
    }

    private EvalResultVO toResultVO(EvalResultRecord record) {
        return EvalResultVO.builder()
                .resultId(record.getResultId())
                .caseId(record.getCaseId())
                .status(record.getStatus())
                .question(record.getQuestionText())
                .answer(record.getAnswerText())
                .citations(readJsonList(record.getCitationsJson()))
                .evidence(readJsonList(record.getEvidenceJson()))
                .retrievedResults(readRetrievalResults(record.getRetrievedResultsJson()))
                .retrievalHit(valueOrZero(record.getRetrievalHit()))
                .retrievalMetricsApplicable(Boolean.TRUE.equals(record.getRetrievalMetricsApplicable()))
                .retrievalMetricLevel(emptyIfBlank(record.getRetrievalMetricLevel()))
                .recallAtK(valueOrZero(record.getRecallAtK()))
                .mrrAtK(valueOrZero(record.getMrrAtK()))
                .precisionAtK(valueOrZero(record.getPrecisionAtK()))
                .citationCoverage(valueOrZero(record.getCitationCoverage()))
                .keywordScore(valueOrZero(record.getKeywordScore()))
                .citationMarkerCoverage(valueOrZero(record.getCitationMarkerCoverage()))
                .score(valueOrZero(record.getScore()))
                .keywordScoreApplicable(Boolean.TRUE.equals(record.getKeywordScoreApplicable()))
                .citationCoverageApplicable(Boolean.TRUE.equals(record.getCitationCoverageApplicable()))
                .latencyMs(record.getLatencyMs() == null ? 0 : record.getLatencyMs())
                .errorMessage(record.getErrorMessage())
                .build();
    }

    /** 每轮评测追加 runId 前缀，避免固定 chatId 的历史记忆污染下一轮结果。 */
    private String resolveChatId(String runId, EvalCaseRecord evalCase,
                                   EvalRunRequestVO request, int index) {
        String configured;
        if (StringUtils.hasText(evalCase.getChatId())) {
            configured = evalCase.getChatId().trim();
        } else if (request != null && StringUtils.hasText(request.getChatIdPrefix())) {
            configured = request.getChatIdPrefix().trim() + "-"
                    + String.format(Locale.ROOT, "%03d", index + 1);
        } else {
            configured = evalCase.getCaseId();
        }
        return runId + "-" + configured;
    }

    private String writeJsonList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private String writeRetrievalResults(List<RetrievalResultItem> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private List<String> readJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private List<RetrievalResultItem> readRetrievalResults(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<RetrievalResultItem>>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? "" : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
