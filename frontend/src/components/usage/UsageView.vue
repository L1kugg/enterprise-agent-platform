<template>
  <section class="usage-page">
    <section v-loading="usageLoading" class="eval-main-panel usage-main">
      <div class="knowledge-list-head">
        <p class="section-label">用量统计（本租户）</p>
        <div class="admin-docs-toolbar">
          <el-select v-model="usageRange" size="small" class="usage-range-select">
            <el-option label="近 7 天" :value="7" />
            <el-option label="近 14 天" :value="14" />
            <el-option label="近 30 天" :value="30" />
          </el-select>
          <el-button size="small" @click="loadUsageTrend()">刷新</el-button>
        </div>
      </div>
      <el-alert
        v-if="usageNeedsAuth"
        class="kb-auth-alert"
        type="info"
        show-icon
        :closable="false"
        title="需要登录"
        description="请先完成鉴权后查看本租户用量统计。"
      />
      <div class="usage-cards">
        <div v-for="card in usageMetricCards" :key="card.label" class="eval-metric-card">
          <span>{{ card.label }}</span>
          <strong :class="card.tone">{{ card.value }}</strong>
        </div>
      </div>
      <div class="usage-chart-block">
        <div class="usage-chart-head">
          <p class="usage-chart-title">使用趋势</p>
          <span class="usage-chart-range">近 {{ usageRange }} 天</span>
        </div>
        <svg
          v-if="usagePoints.length"
          class="usage-chart-svg"
          :viewBox="`0 0 ${usageTrendChart.W} ${usageTrendChart.H}`"
          role="img"
          aria-label="使用趋势图：输入、输出 tokens 与费用"
          @mouseleave="usageHoverIndex = null"
        >
          <line
            v-for="(tick, i) in usageTrendChart.yTicksLeft"
            :key="`grid-${i}`"
            class="grid-line"
            :x1="usageTrendChart.padL"
            :x2="usageTrendChart.W - usageTrendChart.padR"
            :y1="tick.y"
            :y2="tick.y"
          />
          <text
            v-for="(tick, i) in usageTrendChart.yTicksLeft"
            :key="`yl-${i}`"
            class="axis-text"
            :x="usageTrendChart.padL - 6"
            :y="tick.y + 3"
            text-anchor="end"
          >
            {{ tick.text }}
          </text>
          <text
            v-for="(tick, i) in usageTrendChart.yTicksRight"
            :key="`yr-${i}`"
            class="axis-text"
            :x="usageTrendChart.W - usageTrendChart.padR + 6"
            :y="tick.y + 3"
            text-anchor="start"
          >
            {{ tick.text }}
          </text>
          <path
            v-if="usageTrendChart.areaPath"
            class="area-input"
            :d="usageTrendChart.areaPath"
          />
          <polyline class="line-input" :points="usageTrendChart.inputLine" />
          <polyline class="line-output" :points="usageTrendChart.outputLine" />
          <polyline class="line-cost" :points="usageTrendChart.costLine" />
          <g v-for="day in usageTrendChart.days" :key="`xl-${day.key}`">
            <text
              v-if="day.showXLabel"
              class="axis-text"
              :x="day.x"
              :y="usageTrendChart.labelY"
              text-anchor="middle"
            >
              {{ day.shortDate }}
            </text>
          </g>
          <g v-if="usageHoverDay" class="usage-hover">
            <line
              class="hover-line"
              :x1="usageHoverDay.x"
              :x2="usageHoverDay.x"
              :y1="usageTrendChart.padT"
              :y2="usageTrendChart.baseY"
            />
            <rect
              class="tooltip-box"
              :x="usageHoverDay.boxX"
              :y="usageHoverDay.boxY"
              :width="usageHoverDay.boxW"
              :height="usageHoverDay.boxH"
              rx="8"
            />
            <text
              class="tooltip-title"
              :x="usageHoverDay.boxX + 12"
              :y="usageHoverDay.boxY + 22"
            >
              {{ usageHoverDay.key.replaceAll('-', '/') }}
            </text>
            <circle
              class="tt-dot input"
              :cx="usageHoverDay.boxX + 16"
              :cy="usageHoverDay.boxY + 42"
              r="4"
            />
            <text
              class="tooltip-text"
              :x="usageHoverDay.boxX + 26"
              :y="usageHoverDay.boxY + 46"
            >
              输入：{{ usageHoverDay.input.toLocaleString() }}
            </text>
            <circle
              class="tt-dot output"
              :cx="usageHoverDay.boxX + 16"
              :cy="usageHoverDay.boxY + 64"
              r="4"
            />
            <text
              class="tooltip-text"
              :x="usageHoverDay.boxX + 26"
              :y="usageHoverDay.boxY + 68"
            >
              输出：{{ usageHoverDay.output.toLocaleString() }}
            </text>
            <circle
              class="tt-dot cost"
              :cx="usageHoverDay.boxX + 16"
              :cy="usageHoverDay.boxY + 86"
              r="4"
            />
            <text
              class="tooltip-text"
              :x="usageHoverDay.boxX + 26"
              :y="usageHoverDay.boxY + 90"
            >
              成本：${{ usageHoverDay.cost.toFixed(6) }}
            </text>
          </g>
          <rect
            v-for="(day, i) in usageTrendChart.days"
            :key="`hit-${day.key}`"
            :x="day.hitX"
            :y="usageTrendChart.padT"
            :width="day.hitWidth"
            :height="usageTrendChart.plotH"
            fill="transparent"
            @mouseenter="usageHoverIndex = i"
            @mouseleave="usageHoverIndex = null"
          />
        </svg>
        <p v-else class="usage-chart-empty">暂无用量数据</p>
        <div class="usage-legend-bottom">
          <span class="usage-legend"><i class="usage-dot blue"></i>输入</span>
          <span class="usage-legend"><i class="usage-dot green"></i>输出</span>
          <span class="usage-legend"><i class="usage-dot red"></i>成本</span>
        </div>
      </div>
      <el-table :data="usagePoints" height="100%" empty-text="暂无用量数据">
        <el-table-column prop="date" label="日期" min-width="100" />
        <el-table-column label="请求数" width="90">
          <template #default="{ row }">{{ row.requestCount.toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="输入 tokens" min-width="110">
          <template #default="{ row }">{{ row.inputTokens.toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="输出 tokens" min-width="110">
          <template #default="{ row }">{{ row.outputTokens.toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="费用" width="110">
          <template #default="{ row }">${{ row.costUsd.toFixed(4) }}</template>
        </el-table-column>
      </el-table>
    </section>
  </section>
</template>

<script setup lang="ts">
// 用量统计页：模板与样式从 App.vue 原文搬入，行为零变化。
// 范围切换/悬浮/图表几何全部在 useUsage 单例（registerUsageEffects 监听
// usageRange 变化，仍由 App.vue setup 只调一次）。数据加载只在
// useViewActivation / App.vue onMounted 触发，本组件纯展示。
import {
  loadUsageTrend,
  usageHoverDay,
  usageHoverIndex,
  usageLoading,
  usageMetricCards,
  usageNeedsAuth,
  usagePoints,
  usageRange,
  usageTrendChart,
} from '../../composables/useUsage';
</script>


<style scoped>
/* ---------- 用量统计页 ---------- */
.usage-page {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  gap: 14px;
  padding: 14px;
}

.usage-range-select {
  width: 120px;
}

.usage-cards {
  display: grid;
  grid-template-columns: repeat(6, minmax(112px, 1fr));
  gap: 8px;
}

.usage-cards strong.bad {
  color: #dc2626;
}

.usage-chart-block {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.usage-chart-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.usage-legend {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ui-muted);
}

.usage-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  display: inline-block;
}

.usage-chart-svg {
  flex: 1;
  width: 100%;
  min-height: 0;
}

.usage-chart-svg .grid-line {
  stroke: var(--ui-border);
  stroke-width: 1;
}

.usage-chart-svg .axis-text {
  fill: var(--ui-muted);
  font-size: 10px;
}

/* 折线配色取自参考图：输入蓝 / 输出绿 / 成本红虚线（颜色+线型双通道编码，色弱也能分） */
.usage-chart-svg .line-input {
  fill: none;
  stroke: #3b82f6;
  stroke-width: 2;
}

.usage-chart-svg .line-output {
  fill: none;
  stroke: #22c55e;
  stroke-width: 2;
}

.usage-chart-svg .line-cost {
  fill: none;
  stroke: #ef4444;
  stroke-width: 2;
  stroke-dasharray: 6 4;
}

.usage-chart-svg .area-input {
  fill: color-mix(in oklab, #3b82f6 12%, transparent);
  stroke: none;
}

.usage-chart-svg .hover-line {
  stroke: var(--ui-border);
  stroke-width: 1;
}

.usage-chart-svg .tooltip-box {
  fill: var(--ui-panel);
  stroke: var(--ui-border);
  filter: drop-shadow(0 4px 10px rgb(0 0 0 / 18%));
}

.usage-chart-svg .tooltip-title {
  fill: var(--ui-text);
  font-size: 12px;
  font-weight: 700;
}

.usage-chart-svg .tooltip-text {
  fill: var(--ui-text);
  font-size: 11px;
}

.usage-chart-svg .tt-dot.input,
.usage-dot.blue {
  fill: #3b82f6;
  background: #3b82f6;
}

.usage-chart-svg .tt-dot.output,
.usage-dot.green {
  fill: #22c55e;
  background: #22c55e;
}

.usage-chart-svg .tt-dot.cost,
.usage-dot.red {
  fill: #ef4444;
  background: #ef4444;
}

.usage-chart-title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
}

.usage-chart-range {
  font-size: 12px;
  color: var(--ui-muted);
}

.usage-legend-bottom {
  display: flex;
  justify-content: center;
  gap: 18px;
}

.usage-chart-empty {
  margin: 0;
  font-size: 12px;
  color: var(--ui-muted);
}

@media (max-width: 1160px) {
  .usage-cards {
    grid-template-columns: repeat(3, minmax(112px, 1fr));
  }
}

@media (max-width: 680px) {
  .usage-cards {
    grid-template-columns: 1fr;
  }
}
</style>
