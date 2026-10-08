<template>
  <section class="evaluation-page">
    <aside class="eval-side-panel">
      <div class="eval-panel-head">
        <div>
          <p class="section-label">评测集</p>
          <strong>{{ evalDatasets.length }}</strong>
        </div>
      </div>

      <div class="eval-dataset-list">
        <div v-for="dataset in evalDatasets" :key="dataset.datasetId" class="eval-dataset-row">
          <button
            type="button"
            :class="{ active: dataset.datasetId === evalSelectedDatasetId }"
            @click="selectEvalDataset(dataset.datasetId)"
          >
            <span>{{ dataset.name }}</span>
            <small>{{ dataset.caseCount }} 道题 · {{ shortId(dataset.datasetId) }}</small>
          </button>
          <button
            type="button"
            class="eval-dataset-delete"
            :title="`删除评测集 ${dataset.name}`"
            @click="removeEvalDataset(dataset)"
          >
            ✕
          </button>
        </div>
        <div v-if="!evalDatasets.length" class="session-empty">暂无评测集</div>
      </div>

      <div class="eval-create-panel">
        <p class="section-label">创建评测集</p>
        <el-input v-model="evalDatasetName" size="small" placeholder="评测集名称" />
        <el-input v-model="evalDatasetDescription" size="small" placeholder="描述" />
        <div class="eval-editor-toggle">
          <el-radio-group v-model="evalEditorMode" size="small">
            <el-radio-button value="form">表单</el-radio-button>
            <el-radio-button value="json">JSON</el-radio-button>
          </el-radio-group>
        </div>
        <template v-if="evalEditorMode === 'form'">
          <div v-for="(draft, i) in evalCaseDrafts" :key="i" class="eval-case-draft">
            <div class="eval-case-head">
              <span>题目 {{ i + 1 }}</span>
              <button
                type="button"
                :disabled="evalCaseDrafts.length <= 1"
                @click="removeEvalCase(i)"
              >
                删除
              </button>
            </div>
            <el-input v-model="draft.question" size="small" placeholder="问题，如：高温作业有哪些风险？" />
            <el-input
              v-model="draft.expected"
              size="small"
              placeholder="期望关键词（逗号分隔），如：高温,风险"
            />
            <el-input
              v-model="draft.forbidden"
              size="small"
              placeholder="禁用关键词（可选，逗号分隔）"
            />
          </div>
          <div class="eval-case-actions">
            <el-button size="small" @click="addEvalCase">添加题目</el-button>
            <el-button size="small" type="primary" plain @click="syncDraftsToJson">生成 JSON</el-button>
          </div>
        </template>
        <el-input
          v-else
          v-model="evalDatasetJson"
          class="eval-json-input"
          type="textarea"
          :rows="11"
          resize="none"
          spellcheck="false"
        />
        <el-button
          type="primary"
          :loading="evalCreating"
          :disabled="!evalDatasetName.trim() || !evalDatasetJson.trim()"
          @click="createEvalDatasetFromJson"
          >创建评测集</el-button
        >
      </div>
    </aside>

    <section class="eval-main-panel">
      <div class="eval-score-strip">
        <div v-for="metric in evalMetricCards" :key="metric.key" class="eval-metric-card">
          <span>{{ metric.label }}</span>
          <strong>{{ metric.current }}</strong>
          <small v-if="metric.delta" :class="metric.deltaClass">{{ metric.delta }}</small>
        </div>
      </div>

      <div class="eval-run-grid">
        <div class="eval-run-summary">
          <p class="section-label">基线</p>
          <strong>{{ evalBaselineRun?.runId || '未设置' }}</strong>
          <span>{{ formatRunScore(evalBaselineRun?.metrics.runScore) }}</span>
        </div>
        <div class="eval-run-summary current">
          <p class="section-label">本次运行</p>
          <strong>{{ evalCurrentRun?.runId || '无' }}</strong>
          <span>{{ formatRunScore(evalCurrentRun?.metrics.runScore) }}</span>
        </div>
      </div>

      <el-table
        :data="evalCurrentRun?.results ?? []"
        class="eval-result-table"
        height="100%"
        empty-text="暂无评测结果"
      >
        <el-table-column type="expand" width="44">
          <template #default="{ row }">
            <div class="eval-expand">
              <div class="eval-expand-scores">
                <span>Recall@K {{ formatApplicablePercent(row.recallAtK, row.retrievalMetricsApplicable) }}</span>
                <span>MRR@K {{ formatApplicablePercent(row.mrrAtK, row.retrievalMetricsApplicable) }}</span>
                <span>Precision@K {{ formatApplicablePercent(row.precisionAtK, row.retrievalMetricsApplicable) }}</span>
                <span>引用覆盖 {{ formatApplicablePercent(row.citationCoverage, row.citationCoverageApplicable) }}</span>
                <span>关键词 {{ formatApplicablePercent(row.keywordScore, row.keywordScoreApplicable) }}</span>
                <span>引用标记 {{ formatPercent(row.citationMarkerCoverage) }}</span>
              </div>
              <p class="eval-expand-label">模型回答</p>
              <div class="eval-expand-answer">{{ row.answer || '（无回答）' }}</div>
              <template v-if="row.citations?.length">
                <p class="eval-expand-label">引用来源</p>
                <ul class="eval-expand-citations">
                  <li v-for="(cite, citeIndex) in row.citations" :key="citeIndex">
                    {{ cite }}
                  </li>
                </ul>
              </template>
              <p v-if="row.errorMessage" class="eval-expand-error">
                失败原因：{{ row.errorMessage }}
              </p>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="caseId" label="题目ID" min-width="120" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="得分" width="110">
          <template #default="{ row }">{{ formatPercent(row.score) }}</template>
        </el-table-column>
        <el-table-column label="Recall@K" width="120">
          <template #default="{ row }">{{ formatApplicablePercent(row.recallAtK, row.retrievalMetricsApplicable) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="120">
          <template #default="{ row }">{{ row.latencyMs }}ms</template>
        </el-table-column>
        <el-table-column prop="question" label="问题" min-width="280" show-overflow-tooltip />
      </el-table>
    </section>
  </section>
</template>

<script setup lang="ts">
// RAG 评测工作台：模板与样式从 App.vue 原文搬入，行为零变化。
// 显隐用 App.vue 组件标签上的裸 v-else（不是 v-else-if）：普通用户误入 admin
// 视图时这里兜底渲染评测页，语义必须保持。数据加载由 useViewActivation /
// App.vue onMounted 触发；全部状态在 useEvaluation 单例。
import {
  createEvalDatasetFromJson,
  evalBaselineRun,
  evalCreating,
  evalCurrentRun,
  evalDatasetDescription,
  evalDatasetJson,
  evalDatasetName,
  evalDatasets,
  evalMetricCards,
  evalSelectedDatasetId,
  removeEvalDataset,
  selectEvalDataset,
} from '../../composables/useEvaluation';
import { formatPercent, formatRunScore } from '../../utils/evalFormat';
import { shortId } from '../../utils/format';
import { ref, watch } from 'vue';

// 结构化题目编辑器：业务用户不写 JSON，表单填完一键生成；JSON 模式保留给高级用户
const evalEditorMode = ref<'form' | 'json'>('json');
const evalCaseDrafts = ref([{ question: '', expected: '', forbidden: '' }]);

function addEvalCase() {
  evalCaseDrafts.value.push({ question: '', expected: '', forbidden: '' });
}

function formatApplicablePercent(value: number, applicable?: boolean) {
  return applicable === false ? '-' : formatPercent(value);
}

function removeEvalCase(index: number) {
  evalCaseDrafts.value.splice(index, 1);
}

function splitCsv(value: string): string[] {
  return value
    .split(/[,，]/)
    .map((v) => v.trim())
    .filter(Boolean);
}

function syncDraftsToJson() {
  const cases = evalCaseDrafts.value
    .filter((d) => d.question.trim())
    .map((d, i) => ({
      caseId: `case_${String(i + 1).padStart(3, '0')}`,
      category: 'rag_recall',
      chatId: `eval-case-${i + 1}`,
      question: d.question.trim(),
      expectedKeywords: splitCsv(d.expected),
      ...(splitCsv(d.forbidden).length ? { forbiddenKeywords: splitCsv(d.forbidden) } : {}),
    }));
  evalDatasetJson.value = JSON.stringify(cases, null, 2);
  evalEditorMode.value = 'json';
}

// 切到表单模式时尝试把现有 JSON 反解析成草稿，双向不割裂
watch(evalEditorMode, (mode) => {
  if (mode !== 'form') return;
  try {
    const parsed = JSON.parse(evalDatasetJson.value);
    if (Array.isArray(parsed) && parsed.length) {
      evalCaseDrafts.value = parsed.map((c) => ({
        question: String(c.question ?? ''),
        expected: Array.isArray(c.expectedKeywords) ? c.expectedKeywords.join(',') : '',
        forbidden: Array.isArray(c.forbiddenKeywords) ? c.forbiddenKeywords.join(',') : '',
      }));
    }
  } catch {
    // JSON 非法或为空：保留现有草稿，用户从头填
  }
});
</script>

<style scoped>
.evaluation-page {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  gap: 14px;
  padding: 14px;
}

.eval-dataset-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 260px;
  overflow-y: auto;
}

.eval-dataset-row {
  display: flex;
  gap: 6px;
  align-items: stretch;
}

.eval-dataset-row > button:first-child {
  flex: 1;
}

.eval-dataset-list .eval-dataset-delete {
  flex: none;
  width: 32px;
  padding: 0;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: transparent;
  color: var(--ui-muted);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}

.eval-dataset-list .eval-dataset-delete:hover {
  color: #dc2626;
  border-color: rgba(220, 38, 38, 0.45);
}

.eval-dataset-list button {
  width: 100%;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 9px 10px;
  text-align: left;
  color: var(--ui-text);
  background: color-mix(in oklab, var(--ui-panel) 82%, transparent);
  cursor: pointer;
}

.eval-dataset-list button.active {
  border-color: rgba(15, 118, 110, 0.55);
  box-shadow: inset 0 0 0 1px rgba(15, 118, 110, 0.28);
}

.eval-dataset-list span,
.eval-dataset-list small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.eval-dataset-list span {
  font-size: 13px;
  font-weight: 700;
}

.eval-dataset-list small {
  margin-top: 4px;
  color: var(--ui-muted);
}

.eval-create-panel {
  display: grid;
  gap: 8px;
}

.eval-json-input :deep(.el-textarea__inner) {
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.45;
}

.eval-editor-toggle {
  display: flex;
}

.eval-case-draft {
  display: grid;
  gap: 6px;
  padding: 8px;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  background: color-mix(in oklab, var(--ui-panel) 70%, transparent);
}

.eval-case-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--ui-muted);
}

.eval-case-head button {
  border: 1px solid var(--ui-border);
  background: transparent;
  color: var(--ui-muted);
  border-radius: 6px;
  padding: 2px 8px;
  font-size: 11px;
  cursor: pointer;
}

.eval-case-head button:hover:not(:disabled) {
  color: #dc2626;
  border-color: rgba(220, 38, 38, 0.45);
}

.eval-case-actions {
  display: flex;
  gap: 8px;
}

.eval-score-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(112px, 1fr));
  gap: 8px;
}

.eval-run-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.eval-run-summary {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 76%, transparent);
}

.eval-run-summary.current {
  border-color: rgba(15, 118, 110, 0.45);
}

.eval-run-summary strong,
.eval-run-summary span {
  display: block;
  margin-top: 6px;
}

.eval-run-summary strong {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.eval-run-summary span {
  font-size: 22px;
  font-weight: 800;
}

.eval-result-table {
  min-height: 0;
  border-radius: 10px;
  overflow: hidden;
}

.eval-expand {
  display: grid;
  gap: 8px;
  padding: 6px 14px 14px 44px;
}

.eval-expand-scores {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: color-mix(in oklab, currentColor 70%, transparent);
}

.eval-expand-scores span {
  padding: 2px 10px;
  border: 1px solid var(--ui-border);
  border-radius: 999px;
  background: color-mix(in oklab, var(--ui-card) 70%, transparent);
}

.eval-expand-label {
  margin: 6px 0 0;
  font-size: 12px;
  font-weight: 700;
  opacity: 0.75;
}

.eval-expand-answer {
  max-height: 260px;
  overflow: auto;
  padding: 10px 12px;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: color-mix(in oklab, var(--ui-card) 60%, transparent);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.eval-expand-citations {
  margin: 0;
  padding-left: 20px;
  font-size: 12px;
  line-height: 1.8;
  opacity: 0.8;
  word-break: break-all;
}

.eval-expand-error {
  margin: 0;
  font-size: 12px;
  color: var(--el-color-danger, #f56c6c);
}

/* 响应式覆盖与基础规则同住一个组件，保住"媒体覆盖压过基础规则"的原级联关系 */
@media (max-width: 1160px) {
  .eval-score-strip {
    grid-template-columns: repeat(3, minmax(112px, 1fr));
  }
}

@media (max-width: 980px) {
  .evaluation-page {
    grid-template-columns: 1fr;
  }

  .eval-dataset-list {
    max-height: 180px;
  }
}

@media (max-width: 680px) {
  .evaluation-page {
    padding: 12px;
  }

  .eval-score-strip,
  .eval-run-grid {
    grid-template-columns: 1fr;
  }
}
</style>
