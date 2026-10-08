// 评测指标格式化纯函数：从 App.vue 单体拆出，实现逐字一致。
import type { EvalCaseCreate, EvalMetricSummary } from '../types/react';
import type { EvalMetricCard } from '../types/console';

export function metricCard(
  key: keyof EvalMetricSummary,
  label: string,
  current: EvalMetricSummary | undefined,
  baseline: EvalMetricSummary | undefined,
  unit: 'percent' | 'ms',
  lowerIsBetter = false,
  applicable = true,
): EvalMetricCard {
  const currentValue = current?.[key];
  const baselineValue = baseline?.[key];
  const hasCurrent = applicable && typeof currentValue === 'number';
  const hasBaseline = applicable && typeof baselineValue === 'number';
  let delta = '';
  let deltaClass = 'neutral';
  if (hasCurrent && hasBaseline) {
    const diff = currentValue - baselineValue;
    const good = lowerIsBetter ? diff <= 0 : diff >= 0;
    delta = `${diff >= 0 ? '+' : ''}${formatMetricValue(diff, unit)}`;
    deltaClass = good ? 'good' : 'bad';
  }
  return {
    key,
    label,
    current: hasCurrent ? formatMetricValue(currentValue, unit) : '-',
    delta,
    deltaClass,
  };
}

export function formatMetricValue(value: number, unit: 'percent' | 'ms'): string {
  if (unit === 'ms') {
    return `${value.toFixed(0)}ms`;
  }
  return formatPercent(value);
}

export function formatPercent(value: number | undefined): string {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return '-';
  }
  return `${(value * 100).toFixed(1)}%`;
}

export function formatRunScore(value: number | undefined): string {
  return typeof value === 'number' ? formatPercent(value) : '-';
}

export function normalizeEvalCase(raw: Record<string, unknown>, index: number): EvalCaseCreate {
  const expectedKeywords = raw.expectedKeywords ?? raw.expected_keywords;
  const expectedCitations = raw.expectedCitations ?? raw.expected_citations;
  const expectedDocumentIds = raw.expectedDocumentIds ?? raw.expected_document_ids;
  const expectedChunkIds = raw.expectedChunkIds ?? raw.expected_chunk_ids;
  const forbiddenKeywords = raw.forbiddenKeywords ?? raw.forbidden_keywords;
  return {
    caseId: String(raw.caseId ?? raw.id ?? `case-${index + 1}`).trim(),
    category: String(raw.category ?? 'rag').trim(),
    chatId: String(raw.chatId ?? raw.chat_id ?? '').trim(),
    question: String(raw.question ?? '').trim(),
    expectedKeywords: Array.isArray(expectedKeywords) ? expectedKeywords.map(String) : [],
    expectedCitations: Array.isArray(expectedCitations) ? expectedCitations.map(String) : [],
    expectedDocumentIds: Array.isArray(expectedDocumentIds) ? expectedDocumentIds.map(String) : [],
    expectedChunkIds: Array.isArray(expectedChunkIds) ? expectedChunkIds.map(String) : [],
    forbiddenKeywords: Array.isArray(forbiddenKeywords) ? forbiddenKeywords.map(String) : [],
  };
}
