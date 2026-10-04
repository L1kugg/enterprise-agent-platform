import type { ReactTraceStep } from '../types/react';

export interface RetrievalLane {
  key: string;
  label: string;
  cls: string;
  percent: number;
}

const LANE_META: Record<string, { label: string; cls: string }> = {
  vector: { label: 'Vector', cls: 'vector' },
  keyword: { label: 'Keyword', cls: 'keyword' },
  graph: { label: 'Graph', cls: 'graph' },
  web: { label: 'Web', cls: 'web' },
};

/**
 * 从轨迹步的 observation 里取当次实际生效的召回路权重（后端随 rag_search 观测回传）。
 * 旧会话/非检索动作没有 weights 字段，返回 null —— 前端不画色条，不再显示写死的四路比例。
 */
export function retrievalLanes(step: ReactTraceStep | null | undefined): RetrievalLane[] | null {
  const obs = step?.observation;
  if (!obs || typeof obs !== 'object' || Array.isArray(obs)) return null;
  const weights = (obs as Record<string, unknown>).weights;
  if (!weights || typeof weights !== 'object' || Array.isArray(weights)) return null;
  const lanes: RetrievalLane[] = [];
  for (const [key, value] of Object.entries(weights as Record<string, unknown>)) {
    const meta = LANE_META[key];
    const num = typeof value === 'number' ? value : Number(value);
    if (!meta || !Number.isFinite(num) || num <= 0) continue;
    lanes.push({ key, label: meta.label, cls: meta.cls, percent: Math.round(num * 100) });
  }
  return lanes.length ? lanes : null;
}
