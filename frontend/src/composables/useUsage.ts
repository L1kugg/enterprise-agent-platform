// 用量（本租户 token 统计 + 每日趋势 + SVG 图表几何）：从 App.vue 单体拆出，
// 字段与原定义逐字一致。依赖 useAuthState 的凭证。
// watch(usageRange) 放在 registerUsageEffects()，只允许被调用一次。
import { computed, ref, watch } from 'vue';

import { ElMessage } from 'element-plus';

import { getTenantCostSummary, getTenantCostTrend } from '../api/client';
import type { TenantCostSummary, TenantCostTrendPoint } from '../types/react';
import { USAGE_CHART_H, USAGE_CHART_W } from '../utils/constants';
import { isAuthError } from '../utils/format';
import { apiKeyInput, authContext, canUseRemoteSync, token } from './useAuthState';

export const costSummary = ref<TenantCostSummary | null>(null);

const usageRange = ref<7 | 14 | 30>(14);
const usagePoints = ref<TenantCostTrendPoint[]>([]);
const usageLoading = ref(false);
const usageNeedsAuth = ref(false);

// 指标卡直接复用右上角同一份 costSummary 数据，不重复请求
const usageMetricCards = computed(() => {
  const summary = costSummary.value;
  return [
    {
      label: '本月费用',
      value: summary ? `$${summary.monthCostUsd.toFixed(4)}` : '—',
      tone: 'neutral',
    },
    {
      label: '预算余量',
      value: summary ? `$${summary.budgetRemainingUsd.toFixed(4)}` : '—',
      tone: summary?.budgetExceeded ? 'bad' : 'neutral',
    },
    {
      label: '本月请求',
      value: summary ? summary.monthRequestCount.toLocaleString() : '—',
      tone: 'neutral',
    },
    {
      label: '本月输入 token',
      value: summary ? summary.monthInputTokens.toLocaleString() : '—',
      tone: 'neutral',
    },
    {
      label: '本月输出 token',
      value: summary ? summary.monthOutputTokens.toLocaleString() : '—',
      tone: 'neutral',
    },
    {
      label: '今日费用',
      value: summary ? `$${summary.todayCostUsd.toFixed(4)}` : '—',
      tone: 'neutral',
    },
  ];
});

/** 向上取整到 1/2/2.5/5 × 10^k，让 y 轴刻度是整数。 */
function niceCeil(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const magnitude = 10 ** Math.floor(Math.log10(value));
  for (const multiple of [1, 2, 2.5, 5, 10]) {
    if (value <= multiple * magnitude) {
      return multiple * magnitude;
    }
  }
  return 10 * magnitude;
}

const usageHoverIndex = ref<number | null>(null);

/** y 轴刻度：≥1万 用「万」，≥1000 用 k，其余原样。 */
function formatTokenTick(value: number): string {
  if (value >= 10000) {
    const wan = value / 10000;
    return `${Number.isInteger(wan) ? wan : wan.toFixed(1)}万`;
  }
  if (value >= 1000) {
    return `${Math.round(value / 100) / 10}k`;
  }
  return String(value);
}

/** 费用刻度：≥1 美元两位小数，≥1 美分两位小数，更小用三位小数。 */
function formatCostTick(value: number): string {
  if (value >= 1) {
    return `$${value.toFixed(2)}`;
  }
  if (value >= 0.01) {
    return `$${value.toFixed(2)}`;
  }
  return `$${value.toFixed(3)}`;
}

// 单图双轴：左轴输入/输出 tokens（蓝/绿实线），右轴费用 $（红色虚线），悬停出竖参考线+提示框
const usageTrendChart = computed(() => {
  const padL = 52;
  const padR = 52;
  const padT = 14;
  const padB = 26;
  const plotH = USAGE_CHART_H - padT - padB;
  const plotW = USAGE_CHART_W - padL - padR;
  const baseY = padT + plotH;
  const points = usagePoints.value;
  const count = Math.max(1, points.length);
  const band = plotW / count;
  // 三条线各自独立（不堆叠），tokens 轴取输入/输出中的较大者；全零时除零保护
  const tokensMax = niceCeil(
    Math.max(1, ...points.flatMap((point) => [point.inputTokens, point.outputTokens])),
  );
  const costMax = niceCeil(Math.max(0.01, ...points.map((point) => point.costUsd)));
  const xAt = (index: number) => padL + index * band + band / 2;
  const yTok = (value: number) => baseY - (value / tokensMax) * plotH;
  const yCost = (value: number) => baseY - (value / costMax) * plotH;
  const joinPts = (coords: Array<{ x: number; y: number }>) =>
    coords.map((d) => `${d.x},${d.y}`).join(' ');
  const inputPts = points.map((point, index) => ({ x: xAt(index), y: yTok(point.inputTokens) }));
  const fracs = [0, 0.25, 0.5, 0.75, 1];
  return {
    W: USAGE_CHART_W,
    H: USAGE_CHART_H,
    padL,
    padR,
    padT,
    plotH,
    baseY,
    inputLine: joinPts(inputPts),
    outputLine: joinPts(
      points.map((point, index) => ({ x: xAt(index), y: yTok(point.outputTokens) })),
    ),
    costLine: joinPts(points.map((point, index) => ({ x: xAt(index), y: yCost(point.costUsd) }))),
    areaPath:
      inputPts.length >= 2
        ? `M ${inputPts[0].x} ${baseY} L ${inputPts.map((d) => `${d.x} ${d.y}`).join(' L ')} L ${inputPts[inputPts.length - 1].x} ${baseY} Z`
        : '',
    yTicksLeft: fracs.map((frac) => ({
      text: formatTokenTick(tokensMax * frac),
      y: yTok(tokensMax * frac),
    })),
    yTicksRight: fracs.map((frac) => ({
      text: formatCostTick(costMax * frac),
      y: yCost(costMax * frac),
    })),
    labelY: baseY + 16,
    days: points.map((point, index) => ({
      key: point.date,
      x: xAt(index),
      hitX: padL + index * band,
      hitWidth: band,
      showXLabel: count <= 7 || index % (count > 20 ? 5 : 2) === 0,
      shortDate: point.date.slice(5).replace('-', '/'),
      input: point.inputTokens,
      output: point.outputTokens,
      cost: point.costUsd,
    })),
  };
});

/** 悬停日 → 提示框几何（靠右时翻到竖线左边），null 表示未悬停。 */
const usageHoverDay = computed(() => {
  const index = usageHoverIndex.value;
  if (index === null) {
    return null;
  }
  const chart = usageTrendChart.value;
  const day = chart.days[index];
  if (!day) {
    return null;
  }
  const boxW = 176;
  const boxH = 104;
  const flip = day.x > chart.W - chart.padR - boxW - 12;
  return {
    ...day,
    boxW,
    boxH,
    boxX: flip ? day.x - boxW - 12 : day.x + 12,
    boxY: chart.padT + 4,
  };
});

async function loadUsageTrend(silent = false): Promise<void> {
  if (!token.value && !apiKeyInput.value) {
    // 压根没登录过，别去打接口，页面里提示即可
    usageNeedsAuth.value = true;
    usagePoints.value = [];
    return;
  }
  if (!silent) {
    usageLoading.value = true;
  }
  try {
    usagePoints.value = await getTenantCostTrend(usageRange.value, authContext());
    usageNeedsAuth.value = false;
  } catch (error) {
    if (isAuthError(error)) {
      // 登录过期（JWT 两小时失效），页面里提示，不弹报错
      usageNeedsAuth.value = true;
      usagePoints.value = [];
    } else if (!silent) {
      const message = error instanceof Error ? error.message : '用量趋势加载失败';
      ElMessage.error(message);
    }
  } finally {
    if (!silent) {
      usageLoading.value = false;
    }
  }
}

export async function refreshCostSummary(): Promise<void> {
  if (!canUseRemoteSync.value) {
    costSummary.value = null;
    return;
  }
  try {
    costSummary.value = await getTenantCostSummary(authContext());
  } catch {
    // 即使成本查询接口不可用，也保持界面可用。
  }
}

/** 用量副作用（切换统计范围即重新拉趋势），只允许被调用一次。 */
export function registerUsageEffects(): void {
  watch(usageRange, () => {
    void loadUsageTrend();
  });
}

export {
  loadUsageTrend,
  usageHoverDay,
  usageHoverIndex,
  usageLoading,
  usageMetricCards,
  usageNeedsAuth,
  usagePoints,
  usageRange,
  usageTrendChart,
};
