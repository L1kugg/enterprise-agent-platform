// 评测（RAG Evaluation Studio）：评测集 CRUD、跑评测、基线、报告导出。
// 从 App.vue 单体拆出，字段与原定义逐字一致。依赖 useAuthState 凭证与
// useChatState.modelProfile（跑评测时带上当前模型档位）。
import { computed, ref } from 'vue';

import { ElMessage, ElMessageBox } from 'element-plus';

import {
  createEvalDataset,
  deleteEvalDataset,
  exportEvalRunReport,
  getEvalComparison,
  listEvalDatasets,
  markEvalRunBaseline,
  triggerEvalRun,
} from '../api/client';
import type {
  EvalCaseCreate,
  EvalComparison,
  EvalDataset,
  EvalDatasetDeleteResult,
  EvalRun,
} from '../types/react';
import type { EvalMetricCard } from '../types/console';
import { DEFAULT_EVAL_DATASET } from '../utils/constants';
import { metricCard, normalizeEvalCase } from '../utils/evalFormat';
import { modelProfile } from './useChatState';
import { persistState, readBootstrap, registerPersistSlice } from './persistence';
import { authContext } from './useAuthState';

const bootstrap = readBootstrap();

export const evalDatasets = ref<EvalDataset[]>([]);
export const evalSelectedDatasetId = ref(
  (bootstrap.evalSelectedDatasetId as string | undefined) ?? '',
);
export const evalComparison = ref<EvalComparison | null>(null);
export const evalLoading = ref(false);
export const evalCreating = ref(false);
export const evalRunning = ref(false);
export const evalReportExporting = ref(false);
export const evalDatasetName = ref('RAG Evaluation Studio Demo');
export const evalDatasetDescription = ref('RAG baseline regression set');
export const evalDatasetJson = ref(JSON.stringify(DEFAULT_EVAL_DATASET, null, 2));

registerPersistSlice(() => ({
  evalSelectedDatasetId: evalSelectedDatasetId.value,
}));

export const selectedEvalDataset = computed(() =>
  evalDatasets.value.find((dataset) => dataset.datasetId === evalSelectedDatasetId.value),
);

export const evalCurrentRun = computed<EvalRun | null>(() => evalComparison.value?.current ?? null);

export const evalBaselineRun = computed<EvalRun | null>(() => evalComparison.value?.baseline ?? null);

export const evalMetricCards = computed<EvalMetricCard[]>(() => {
  const current = evalCurrentRun.value?.metrics;
  const baseline = evalBaselineRun.value?.metrics;
  return [
    metricCard('runScore', '总分', current, baseline, 'percent'),
    metricCard('retrievalHitRate', '检索命中率', current, baseline, 'percent'),
    metricCard('citationCoverageRate', '引用覆盖率', current, baseline, 'percent'),
    metricCard('answerFaithfulnessScore', '忠实度', current, baseline, 'percent'),
    metricCard('avgLatencyMs', '平均耗时', current, baseline, 'ms', true),
    metricCard('failureRate', '失败率', current, baseline, 'percent', true),
  ];
});

function parseEvalDatasetJson(): EvalCaseCreate[] {
  const parsed = JSON.parse(evalDatasetJson.value) as unknown;
  let rawCases: unknown[] = [];
  if (Array.isArray(parsed)) {
    rawCases = parsed;
  } else if (parsed && typeof parsed === 'object') {
    const objectValue = parsed as Record<string, unknown>;
    if (Array.isArray(objectValue.cases)) {
      rawCases = objectValue.cases;
    } else if (objectValue.paths && typeof objectValue.paths === 'object') {
      Object.values(objectValue.paths as Record<string, unknown>).forEach((pathValue) => {
        if (pathValue && typeof pathValue === 'object') {
          const cases = (pathValue as Record<string, unknown>).cases;
          if (Array.isArray(cases)) {
            rawCases.push(...cases);
          }
        }
      });
    }
  }

  const cases = rawCases
    .filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object'))
    .map(normalizeEvalCase)
    .filter((item) => item.question);
  if (cases.length === 0) {
    throw new Error('评测集 JSON 没有可用 case');
  }
  return cases;
}

export async function loadEvalDatasets(): Promise<void> {
  evalLoading.value = true;
  try {
    evalDatasets.value = await listEvalDatasets(authContext());
    if (!evalSelectedDatasetId.value && evalDatasets.value.length > 0) {
      evalSelectedDatasetId.value = evalDatasets.value[0].datasetId;
    }
    if (evalSelectedDatasetId.value) {
      await loadEvalComparison(evalSelectedDatasetId.value);
    }
    persistState();
  } catch (error) {
    const message = error instanceof Error ? error.message : '评测集加载失败';
    ElMessage.error(message);
  } finally {
    evalLoading.value = false;
  }
}

async function loadEvalComparison(datasetId: string): Promise<void> {
  try {
    evalComparison.value = await getEvalComparison(datasetId, authContext());
  } catch (error) {
    evalComparison.value = null;
    const message = error instanceof Error ? error.message : '评测结果加载失败';
    ElMessage.error(message);
  }
}

export async function selectEvalDataset(datasetId: string): Promise<void> {
  evalSelectedDatasetId.value = datasetId;
  persistState();
  await loadEvalComparison(datasetId);
}

/** 删除评测集：二次确认后调删除接口，清本地选中态；成功提示带各层清理条数。 */
export async function removeEvalDataset(dataset: EvalDataset): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除评测集「${dataset.name}」？它的 ${dataset.caseCount} 道题、历史评测运行与结果明细会一并删除，不可恢复。`,
      '删除评测集',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    const removed: EvalDatasetDeleteResult = await deleteEvalDataset(
      dataset.datasetId,
      authContext(),
    );
    evalDatasets.value = evalDatasets.value.filter((item) => item.datasetId !== dataset.datasetId);
    if (evalSelectedDatasetId.value === dataset.datasetId) {
      evalSelectedDatasetId.value = evalDatasets.value[0]?.datasetId ?? '';
      if (evalSelectedDatasetId.value) {
        await loadEvalComparison(evalSelectedDatasetId.value);
      } else {
        evalComparison.value = null;
      }
    }
    persistState();
    ElMessage.success(
      `已删除「${removed.datasetName}」（${removed.cases} 题 / ${removed.runs} 轮 / ${removed.results} 条结果）`,
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : '评测集删除失败';
    ElMessage.error(message);
  }
}

export async function createEvalDatasetFromJson(): Promise<void> {
  evalCreating.value = true;
  try {
    const created = await createEvalDataset(
      {
        name: evalDatasetName.value.trim(),
        description: evalDatasetDescription.value.trim(),
        cases: parseEvalDatasetJson(),
      },
      authContext(),
    );
    evalDatasets.value = [created, ...evalDatasets.value];
    evalSelectedDatasetId.value = created.datasetId;
    await loadEvalComparison(created.datasetId);
    persistState();
    ElMessage.success('评测集已创建');
  } catch (error) {
    const message = error instanceof Error ? error.message : '评测集创建失败';
    ElMessage.error(message);
  } finally {
    evalCreating.value = false;
  }
}

export async function runSelectedEvalDataset(): Promise<void> {
  if (!evalSelectedDatasetId.value) {
    return;
  }
  evalRunning.value = true;
  try {
    const run = await triggerEvalRun(
      evalSelectedDatasetId.value,
      {
        modelProfile: modelProfile.value,
        chatIdPrefix: 'eval-studio',
      },
      authContext(),
    );
    evalComparison.value = {
      dataset: selectedEvalDataset.value ??
        evalComparison.value?.dataset ?? {
          datasetId: run.datasetId,
          tenantId: run.tenantId,
          name: run.datasetId,
          caseCount: run.metrics.totalCases,
          createdAt: run.createdAt,
          updatedAt: run.createdAt,
        },
      baseline: evalComparison.value?.baseline ?? null,
      current: run,
    };
    ElMessage.success('评测完成');
  } catch (error) {
    const message = error instanceof Error ? error.message : '评测运行失败';
    ElMessage.error(message);
  } finally {
    evalRunning.value = false;
  }
}

export async function markCurrentEvalRunBaseline(): Promise<void> {
  const run = evalCurrentRun.value;
  if (!run) {
    return;
  }
  try {
    await markEvalRunBaseline(run.runId, authContext());
    await loadEvalComparison(run.datasetId);
    ElMessage.success('Baseline 已更新');
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Baseline 更新失败';
    ElMessage.error(message);
  }
}

export async function downloadEvalReport(): Promise<void> {
  const run = evalCurrentRun.value;
  if (!run) {
    return;
  }
  evalReportExporting.value = true;
  try {
    const report = await exportEvalRunReport(run.runId, authContext());
    const blob = new Blob([report], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `rag-evaluation-${run.runId}.md`;
    link.click();
    URL.revokeObjectURL(url);
    ElMessage.success('报告已导出');
  } catch (error) {
    const message = error instanceof Error ? error.message : '报告导出失败';
    ElMessage.error(message);
  } finally {
    evalReportExporting.value = false;
  }
}
