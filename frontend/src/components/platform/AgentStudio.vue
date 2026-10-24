<template>
  <section class="agent-studio">
    <section class="studio-list panel">
      <div class="panel-head compact list-head">
        <div class="list-actions">
          <el-input
            v-model="search"
            size="small"
            clearable
            placeholder="搜索名称"
            @keyup.enter="loadAgents"
            @clear="loadAgents"
          />
          <el-button size="small" @click="loadAgents">查询</el-button>
          <el-button size="small" type="primary" @click="createDraft">新建</el-button>
        </div>
      </div>
      <el-empty v-if="agents.length === 0 && !loading" description="暂无智能体" :image-size="70" />
      <div v-else v-loading="loading" class="agent-list">
        <button
          v-for="item in agents"
          :key="item.id"
          type="button"
          class="agent-item"
          :class="{ active: selectedId === item.id }"
          @click="selectAgent(item)"
        >
          <span>
            <strong>{{ item.name }}</strong>
            <small>{{ item.description || '暂无简介' }}</small>
          </span>
          <el-tag size="small" :type="statusTag(item.status)">{{ item.status || 'DRAFT' }}</el-tag>
        </button>
      </div>
    </section>

    <section class="studio-editor panel">
      <div class="studio-head">
        <div>
          <h3>{{ form.name || '未命名智能体' }}</h3>
        </div>
        <div class="studio-actions">
          <el-tag v-if="unsavedCheck()" size="small" type="warning" effect="light">未保存</el-tag>
          <el-button size="small" :disabled="!editingId" :loading="saving" @click="saveAgent(true)"
            >保存草稿</el-button
          >
          <el-button size="small" type="primary" :loading="saving" @click="saveAgent(false)">{{
            editingId ? '保存配置' : '创建智能体'
          }}</el-button>
        </div>
      </div>

      <div class="studio-body">
        <nav class="config-nav" aria-label="智能体配置导航">
          <button
            v-for="item in sections"
            :key="item.key"
            type="button"
            :class="{ active: activeSection === item.key }"
            @click="activeSection = item.key"
          >
            <span>{{ item.label }}</span>
            <small>{{ item.description }}</small>
          </button>
        </nav>

        <el-form v-if="activeSection === 'basic'" label-position="top" class="config-form">
          <el-form-item label="名称" required>
            <el-input v-model="form.name" maxlength="128" show-word-limit />
          </el-form-item>
          <el-form-item label="简介">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="3"
              maxlength="512"
              show-word-limit
            />
          </el-form-item>
          <el-form-item label="开场白">
            <el-input v-model="form.openingMessage" type="textarea" :rows="3" />
          </el-form-item>
          <el-form-item label="引导问题">
            <el-input
              v-model="form.openingQuestions"
              type="textarea"
              :rows="4"
              placeholder="每行一个问题"
            />
          </el-form-item>
        </el-form>

        <el-form v-else-if="activeSection === 'model'" label-position="top" class="config-form">
          <el-form-item label="模型服务" required>
            <el-select
              v-model="form.modelId"
              filterable
              clearable
              placeholder="选择模型服务"
              class="full-width"
            >
              <el-option
                v-for="item in models"
                :key="item.id"
                :label="item.name"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="Temperature">
            <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" />
          </el-form-item>
          <el-form-item label="系统提示词" required>
            <el-input
              v-model="form.prompt"
              type="textarea"
              :rows="9"
              placeholder="定义智能体角色、边界和输出要求"
            />
          </el-form-item>
        </el-form>

        <el-form v-else-if="activeSection === 'resources'" label-position="top" class="config-form">
          <el-form-item label="工作流">
            <el-select v-model="form.workflowIds" multiple filterable class="full-width">
              <el-option
                v-for="item in workflows"
                :key="item.id"
                :label="item.name"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="工具">
            <el-select v-model="form.toolIds" multiple filterable class="full-width">
              <el-option v-for="item in tools" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="知识库">
            <el-select v-model="form.knowledgeBaseIds" multiple filterable class="full-width">
              <el-option
                v-for="item in knowledgeBases"
                :key="item.id"
                :label="kbOptionLabel(item)"
                :value="item.id"
              />
            </el-select>
            <p class="binding-hint">
              绑定后智能体只在所选知识库内召回（FR-R5-01）；不绑定则检索本租户全部文档。
            </p>
          </el-form-item>
          <el-form-item label="安全防护">
            <el-select v-model="form.safetyGuardIds" multiple filterable class="full-width">
              <el-option
                v-for="item in safetyGuards"
                :key="item.id"
                :label="item.name"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
        </el-form>

        <el-form v-else-if="activeSection === 'execution'" label-position="top" class="config-form">
          <el-form-item label="工具描述">
            <el-input
              v-model="form.toolDescription"
              type="textarea"
              :rows="4"
              placeholder="说明已绑定工具的用途，帮助模型理解调用时机"
            />
          </el-form-item>
          <el-form-item label="执行前确认">
            <el-switch
              v-model="form.executionConfirm"
              active-text="需要确认"
              inactive-text="自动执行"
            />
          </el-form-item>
          <el-form-item label="短期记忆对话轮数">
            <el-input-number v-model="form.memoryTurns" :min="1" :max="100" />
          </el-form-item>
          <el-form-item label="规划步数上限">
            <el-input-number v-model="form.maxSteps" :min="1" :max="10" />
            <span class="field-hint">ReAct 单次任务最多执行的动作步数，复杂任务可调高；留空用平台默认（4）</span>
          </el-form-item>
        </el-form>

        <!-- 分析看板（R2.2）：全部数字来自 agent_invocation_log 真实聚合 -->
        <div v-else-if="activeSection === 'board'" class="config-form board-panel">
          <div class="board-toolbar">
            <el-select v-model="boardDays" size="small" class="board-days" @change="loadBoard">
              <el-option label="近 7 天" :value="7" />
              <el-option label="近 30 天" :value="30" />
            </el-select>
            <el-button size="small" @click="loadBoard">刷新</el-button>
            <span v-if="boardEmpty" class="board-empty-note">窗口内暂无调用数据</span>
          </div>

          <div v-loading="boardLoading" class="board-content">
            <div class="board-cards">
              <div class="board-card">
                <small>总调用</small>
                <strong>{{ boardStats?.total ?? 0 }}</strong>
              </div>
              <div class="board-card">
                <small>成功率</small>
                <strong>{{ successRateText }}</strong>
              </div>
              <div class="board-card">
                <small>平均耗时</small>
                <strong>{{ boardStats?.avgLatencyMs ?? 0 }}ms</strong>
              </div>
              <div class="board-card">
                <small>Token（入/出）</small>
                <strong>{{ boardStats?.inputTokens ?? 0 }} / {{ boardStats?.outputTokens ?? 0 }}</strong>
              </div>
            </div>

            <h4 class="board-sub">按天趋势</h4>
            <div class="board-trend">
              <div
                v-for="point in boardTrend"
                :key="point.date"
                class="board-trend-col"
                :title="`${point.date}：共 ${point.total} 次，成功 ${point.successCount}，拦截 ${point.blockedCount}，失败 ${point.errorCount}，平均 ${point.avgLatencyMs}ms`"
              >
                <div class="board-trend-bar-wrap">
                  <div class="board-trend-bar" :style="{ height: trendBarHeight(point.total) }"></div>
                </div>
                <small>{{ point.date.slice(5) }}</small>
              </div>
            </div>

            <h4 class="board-sub">常见错误 / 拦截 Top5</h4>
            <p v-if="!boardStats?.topErrors.length" class="board-hint">窗口内无错误记录。</p>
            <ul v-else class="board-errors">
              <li v-for="item in boardStats.topErrors" :key="item.summary">
                <span class="board-error-count">×{{ item.count }}</span
                >{{ item.summary }}
              </li>
            </ul>

            <h4 class="board-sub">调用日志</h4>
            <div class="board-toolbar">
              <el-select
                v-model="boardStatus"
                size="small"
                class="board-days"
                clearable
                placeholder="全部状态"
                @change="reloadBoardLogs"
              >
                <el-option label="全部状态" value="" />
                <el-option label="成功" value="SUCCESS" />
                <el-option label="已拦截" value="BLOCKED" />
                <el-option label="失败" value="ERROR" />
              </el-select>
            </div>
            <el-table :data="boardLogs" size="small" empty-text="暂无调用记录">
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="logStatusTag(row.status)">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="渠道" width="80">
                <template #default="{ row }">{{ row.channel }}</template>
              </el-table-column>
              <el-table-column label="模型" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ row.modelName || '—' }}
                  <el-tag v-if="row.fallbackApplied" size="small" type="warning">回退</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="90">
                <template #default="{ row }">{{ row.latencyMs != null ? row.latencyMs + 'ms' : '—' }}</template>
              </el-table-column>
              <el-table-column label="Token" width="110">
                <template #default="{ row }">{{
                  row.inputTokens != null ? `${row.inputTokens}/${row.outputTokens ?? 0}` : '—'
                }}</template>
              </el-table-column>
              <el-table-column label="请求摘录" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ row.promptExcerpt || '—' }}</template>
              </el-table-column>
              <el-table-column label="错误" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">{{ row.errorSummary || '—' }}</template>
              </el-table-column>
              <el-table-column label="时间" width="150">
                <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
              </el-table-column>
            </el-table>
            <el-pagination
              v-model:current-page="boardPage"
              :page-size="boardPageSize"
              :total="boardTotal"
              layout="total, prev, pager, next"
              small
              @current-change="reloadBoardLogs"
            />
          </div>
        </div>

        <div v-else class="config-form json-panel">
          <p>高级 JSON</p>
          <el-input
            v-model="advancedJson"
            type="textarea"
            :rows="16"
            spellcheck="false"
            class="mono"
          />
          <el-button size="small" class="json-apply" @click="applyAdvancedJson"
            >应用 JSON</el-button
          >
        </div>
      </div>
    </section>

    <aside class="studio-debug panel">
      <div class="panel-head compact">
        <div>
          <p class="section-label">Debug</p>
          <h3>调试</h3>
        </div>
        <el-tag size="small" :type="selected?.status === 'PUBLISHED' ? 'success' : 'info'">
          {{ selected?.status || 'DRAFT' }}
        </el-tag>
      </div>
      <div class="debug-body">
        <p v-if="formDirty" class="debug-dirty">表单有未保存修改，调试按「已保存配置」执行。</p>
        <el-input
          v-model="debugMessage"
          type="textarea"
          :rows="3"
          :disabled="!editingId"
          placeholder="输入调试消息（用已保存配置真跑一次会话）"
        />
        <el-button
          type="primary"
          :loading="debugRunning"
          :disabled="!editingId || !debugMessage.trim()"
          @click="runDebug"
          >运行调试</el-button
        >
        <template v-if="debugResult">
          <div class="debug-result">
            <el-tag size="small" :type="debugStatusTag(debugResult.status)">
              {{ debugResult.status }}
            </el-tag>
            <span v-if="debugResult.latencyMs != null" class="debug-latency">
              {{ debugResult.latencyMs }}ms
            </span>
          </div>
          <el-alert
            v-if="debugResult.status === 'ERROR'"
            type="error"
            :closable="false"
            :title="debugResult.error || '执行失败'"
            show-icon
          />
          <div v-if="debugResult.answer" class="debug-answer">{{ debugResult.answer }}</div>
          <div class="debug-meta">
            <p>模型</p>
            <span class="debug-meta-text">{{ modelSummaryText }}</span>
            <p>安全拦截</p>
            <span class="debug-meta-text">{{ safetySummaryText }}</span>
            <p>记忆注入</p>
            <span class="debug-meta-text">{{ memorySummaryText }}</span>
            <template v-if="knowledgeSummaryText">
              <p>知识召回</p>
              <span class="debug-meta-text">{{ knowledgeSummaryText }}</span>
            </template>
          </div>
          <el-collapse v-if="debugResult.steps?.length" class="debug-steps">
            <el-collapse-item :title="`执行步骤（${debugResult.steps.length}）`">
              <div v-for="step in debugResult.steps" :key="step.step" class="debug-step">
                <span>#{{ step.step }} {{ step.action }}</span>
                <small>{{ step.status }} · {{ step.elapsedMs ?? '-' }}ms</small>
                <div v-if="step.citations?.length" class="debug-step-citations">
                  <small v-for="citation in step.citations" :key="citation">{{ citation }}</small>
                </div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </template>
        <div v-else class="debug-meta">
          <p>当前状态</p>
          <span>{{ selected?.status || 'DRAFT' }}</span>
          <p>已绑定资源</p>
          <span>{{
            form.workflowIds.length +
            form.toolIds.length +
            form.knowledgeBaseIds.length +
            form.safetyGuardIds.length
          }}</span>
        </div>
      </div>
    </aside>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  getAgentLogs,
  getAgentStats,
  getAgentTrend,
  createPlatformAsset,
  debugPlatformAgent,
  listPlatformAssets,
  updatePlatformAsset,
} from '../../api/platform';
import type {
  AgentDebugResult,
  AgentInvocationLogEntry,
  AgentStatsSummary,
  AgentTrendPoint,
  PlatformAsset,
} from '../../types/platform';
import { authContext } from '../../composables/useAuthState';
import { statusTag } from '../../composables/usePlatformAssets';
import { confirmLeaveUnsaved, registerUnsavedCheck, unregisterUnsavedCheck } from '../../composables/useUnsavedGuard';
import { formatTime } from '../../utils/format';

const sections = [
  { key: 'basic', label: '基本信息', description: '名称、简介与开场引导' },
  { key: 'model', label: '模型与提示词', description: '模型、参数与系统提示词' },
  { key: 'resources', label: '资源编排', description: '工作流、工具、知识库、防护' },
  { key: 'execution', label: '执行与对话', description: '确认策略、描述与记忆轮数' },
  { key: 'board', label: '分析看板', description: '调用量、成功率、耗时与错误' },
  { key: 'advanced', label: '高级 JSON', description: '兼容既有配置结构' },
] as const;

type SectionKey = (typeof sections)[number]['key'];
const activeSection = ref<SectionKey>('basic');
const agents = ref<PlatformAsset[]>([]);
const models = ref<PlatformAsset[]>([]);
const workflows = ref<PlatformAsset[]>([]);
const tools = ref<PlatformAsset[]>([]);
const knowledgeBases = ref<PlatformAsset[]>([]);
const safetyGuards = ref<PlatformAsset[]>([]);
const loading = ref(false);
const saving = ref(false);
const search = ref('');
const selectedId = ref<number | null>(null);
const selected = ref<PlatformAsset | null>(null);
const editingId = ref<number | null>(null);
const advancedJson = ref('{}');

const form = reactive({
  name: '',
  description: '',
  openingMessage: '',
  openingQuestions: '',
  prompt: '',
  modelId: null as number | null,
  temperature: 0.7,
  workflowIds: [] as number[],
  toolIds: [] as number[],
  knowledgeBaseIds: [] as number[],
  safetyGuardIds: [] as number[],
  toolDescription: '',
  executionConfirm: true,
  memoryTurns: 10,
  maxSteps: null as number | null,
});

/** 绑定选项展示真实可用状态（FR-R5-01）：非 ACTIVE 的知识库标注不可用，绑定前可辨。 */
function kbOptionLabel(item: PlatformAsset): string {
  const active = (item.status ?? '').toUpperCase() === 'ACTIVE';
  return active ? item.name : `${item.name}（${item.status || '未知状态'}，不可用）`;
}

const configObject = computed(() => {
  const questions = form.openingQuestions
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean);
  return {
    prompt: form.prompt,
    modelId: form.modelId,
    modelParams: { temperature: form.temperature },
    openingMessage: form.openingMessage,
    openingQuestions: questions,
    workflowIds: form.workflowIds,
    tools: form.toolIds.map((id) => ({ id, description: form.toolDescription })),
    knowledgeBaseIds: form.knowledgeBaseIds,
    safetyGuardIds: form.safetyGuardIds,
    executionConfirm: form.executionConfirm,
    memoryTurns: form.memoryTurns,
    ...(form.maxSteps ? { maxSteps: form.maxSteps } : {}),
  };
});

// ---------- 调试（R2.1）：用已保存配置真跑一次会话 ----------
const debugMessage = ref('');
const debugRunning = ref(false);
const debugResult = ref<AgentDebugResult | null>(null);
/** 最近一次表单同步自的已保存配置（用于判断表单是否有未保存修改）。 */
const savedConfigJson = ref('{}');

/** 未保存守卫回调（注册进全局注册表）：含新建草稿——selected 为空也跟踪表单快照差异。 */
const unsavedCheck = () => JSON.stringify(configObject.value) !== savedConfigJson.value;

const formDirty = computed(() => selected.value !== null && JSON.stringify(configObject.value) !== savedConfigJson.value);

const modelSummaryText = computed(() => {
  const summary = debugResult.value?.modelSummary;
  if (!summary) return '-';
  const name = summary.modelName || '未知模型';
  const source = summary.source === 'bound' ? '绑定模型' : '档位路由';
  return summary.fallbackApplied ? `${name}（已回退·${source}）` : `${name}（${source}）`;
});

const safetySummaryText = computed(() => {
  const summary = debugResult.value?.safetyResult;
  if (!summary) return '-';
  return summary.blocked ? `已拦截：${summary.blockMessage ?? ''}` : '未命中';
});

const memorySummaryText = computed(() => {
  const summary = debugResult.value?.memorySummary;
  if (!summary) return '-';
  return `最近 ${summary.memoryTurns ?? '-'} 轮 · 注入 ${summary.injectedLabels?.length ?? 0} 条`;
});

/** 知识召回摘要（FR-R5-03）：圈定范围 + 本次命中的知识库数；未绑定智能体知识库时返回空串不占位。 */
const knowledgeSummaryText = computed(() => {
  const summary = debugResult.value?.knowledgeSummary;
  if (!summary?.scoped) return '';
  const boundNames = summary.knowledgeBaseNames ?? [];
  const recalledCount = summary.recalledKnowledgeBaseIds?.length ?? 0;
  const boundText = boundNames.length ? boundNames.join('、') : `${summary.knowledgeBaseIds?.length ?? 0} 个`;
  return `范围：${boundText} · 命中 ${recalledCount} 库`;
});

function debugStatusTag(status: AgentDebugResult['status']): 'success' | 'warning' | 'danger' {
  if (status === 'SUCCESS') return 'success';
  if (status === 'BLOCKED') return 'warning';
  return 'danger';
}

// ---------- 分析看板（R2.2）：数字全部来自 agent_invocation_log 真实聚合 ----------

const boardDays = ref(7);
const boardLoading = ref(false);
const boardStats = ref<AgentStatsSummary | null>(null);
const boardTrend = ref<AgentTrendPoint[]>([]);
const boardLogs = ref<AgentInvocationLogEntry[]>([]);
const boardTotal = ref(0);
const boardPage = ref(1);
const boardPageSize = 10;
const boardStatus = ref('');

const boardEmpty = computed(
  () => !boardLoading.value && (boardStats.value?.total ?? 0) === 0,
);
const successRateText = computed(
  () => `${Math.round((boardStats.value?.successRate ?? 0) * 100)}%`,
);

function trendBarHeight(total: number): string {
  const max = Math.max(...boardTrend.value.map((point) => point.total), 1);
  return `${Math.max(4, Math.round((total / max) * 100))}%`;
}

function logStatusTag(status: string): 'success' | 'warning' | 'danger' {
  if (status === 'SUCCESS') return 'success';
  if (status === 'BLOCKED') return 'warning';
  return 'danger';
}

async function loadBoard(): Promise<void> {
  if (!editingId.value) {
    return;
  }
  boardLoading.value = true;
  try {
    const [stats, trend] = await Promise.all([
      getAgentStats(editingId.value, boardDays.value, authContext()),
      getAgentTrend(editingId.value, boardDays.value, authContext()),
    ]);
    boardStats.value = stats;
    boardTrend.value = trend;
    boardPage.value = 1;
    await reloadBoardLogs();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '看板数据加载失败');
  } finally {
    boardLoading.value = false;
  }
}

async function reloadBoardLogs(): Promise<void> {
  if (!editingId.value) {
    return;
  }
  try {
    const page = await getAgentLogs(editingId.value, authContext(), {
      page: boardPage.value,
      pageSize: boardPageSize,
      status: boardStatus.value || undefined,
    });
    boardLogs.value = page.items;
    boardTotal.value = page.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '调用日志加载失败');
  }
}

// 进入看板分区或切换智能体时拉取（以选中/编辑中的智能体为准）
watch([activeSection, selectedId], ([section]) => {
  if (section === 'board' && editingId.value) {
    void loadBoard();
  }
});

async function runDebug(): Promise<void> {
  if (!editingId.value || !debugMessage.value.trim()) {
    return;
  }
  debugRunning.value = true;
  try {
    debugResult.value = await debugPlatformAgent(
      editingId.value,
      debugMessage.value.trim(),
      undefined,
      authContext(),
    );
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '调试执行失败');
  } finally {
    debugRunning.value = false;
  }
}

function markSavedConfig(): void {
  savedConfigJson.value = JSON.stringify(configObject.value);
}

async function loadAgents(): Promise<void> {
  loading.value = true;
  try {
    const page = await listPlatformAssets(
      'agents',
      authContext(),
      1,
      50,
      search.value.trim() || undefined,
    );
    agents.value = page.items;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '智能体列表加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadReferenceData(): Promise<void> {
  const [modelPage, workflowPage, toolPage, knowledgePage, safetyPage] = await Promise.all([
    listPlatformAssets('model-services', authContext(), 1, 100),
    listPlatformAssets('workflows', authContext(), 1, 100),
    listPlatformAssets('tools', authContext(), 1, 100),
    listPlatformAssets('knowledge-bases', authContext(), 1, 100),
    listPlatformAssets('safety-guards', authContext(), 1, 100),
  ]);
  models.value = modelPage.items;
  workflows.value = workflowPage.items;
  tools.value = toolPage.items;
  knowledgeBases.value = knowledgePage.items;
  safetyGuards.value = safetyPage.items;
}

function readNumberArray(value: unknown): number[] {
  return Array.isArray(value)
    ? value.filter((item): item is number => typeof item === 'number')
    : [];
}

async function selectAgent(item: PlatformAsset): Promise<void> {
  // 未保存守卫：切换/新建前确认丢弃（formDirty 与调试提示同一数据源）
  if (
    JSON.stringify(configObject.value) !== savedConfigJson.value &&
    !(await confirmLeaveUnsaved('切换智能体将丢弃当前未保存的修改，确定？'))
  ) {
    return;
  }
  selected.value = item;
  selectedId.value = item.id;
  editingId.value = item.id;
  activeSection.value = 'basic';
  let config: Record<string, unknown> = {};
  try {
    config = item.configJson ? (JSON.parse(item.configJson) as Record<string, unknown>) : {};
  } catch {
    config = {};
  }
  form.name = item.name;
  form.description = item.description ?? '';
  form.openingMessage = String(config.openingMessage ?? '');
  const questions = Array.isArray(config.openingQuestions) ? config.openingQuestions : [];
  form.openingQuestions = questions.map(String).join('\n');
  form.prompt = String(config.prompt ?? '');
  form.modelId = typeof config.modelId === 'number' ? config.modelId : null;
  const params = (config.modelParams ?? {}) as Record<string, unknown>;
  form.temperature = typeof params.temperature === 'number' ? params.temperature : 0.7;
  form.workflowIds = readNumberArray(config.workflowIds);
  const boundTools = Array.isArray(config.tools) ? config.tools : [];
  form.toolIds = boundTools
    .map((tool) => {
      if (typeof tool === 'number') return tool;
      if (tool && typeof tool === 'object' && 'id' in tool) {
        const id = (tool as { id?: unknown }).id;
        if (typeof id === 'number') return id;
      }
      return -1;
    })
    .filter((id) => id > 0);
  const firstTool = boundTools[0];
  form.toolDescription =
    firstTool && typeof firstTool === 'object' && 'description' in firstTool
      ? String((firstTool as { description?: unknown }).description ?? '')
      : '';
  form.knowledgeBaseIds = readNumberArray(config.knowledgeBaseIds);
  form.safetyGuardIds = readNumberArray(config.safetyGuardIds);
  form.executionConfirm = config.executionConfirm !== false;
  form.memoryTurns = typeof config.memoryTurns === 'number' ? config.memoryTurns : 10;
  form.maxSteps = typeof config.maxSteps === 'number' ? config.maxSteps : null;
  advancedJson.value = JSON.stringify(config, null, 2);
  markSavedConfig();
  // 切换智能体后上一次调试结果不再属于当前对象，清空避免误导
  debugResult.value = null;
}

function createDraft(): void {
  selected.value = null;
  selectedId.value = null;
  editingId.value = null;
  activeSection.value = 'basic';
  form.name = '新建智能体';
  form.description = '';
  form.openingMessage = '';
  form.openingQuestions = '';
  form.prompt = '';
  form.modelId = models.value[0]?.id ?? null;
  form.temperature = 0.7;
  form.workflowIds = [];
  form.toolIds = [];
  form.knowledgeBaseIds = [];
  form.safetyGuardIds = [];
  form.toolDescription = '';
  form.executionConfirm = true;
  form.memoryTurns = 10;
  form.maxSteps = null;
  advancedJson.value = JSON.stringify(configObject.value, null, 2);
  markSavedConfig();
  debugResult.value = null;
}

function validate(): string | null {
  if (!form.name.trim()) return '请填写智能体名称';
  if (!form.prompt.trim()) return '请填写系统提示词';
  if (!form.modelId) return '请选择模型服务';
  return null;
}

async function saveAgent(draft: boolean): Promise<void> {
  const error = validate();
  if (error) {
    ElMessage.warning(error);
    activeSection.value = error.includes('提示词') || error.includes('模型') ? 'model' : 'basic';
    return;
  }
  saving.value = true;
  try {
    const body = {
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      configJson: JSON.stringify(configObject.value),
      status: draft ? 'DRAFT' : selected.value?.status === 'PUBLISHED' ? 'PUBLISHED' : undefined,
    };
    if (editingId.value) {
      await updatePlatformAsset('agents', editingId.value, body, authContext());
    } else {
      const created = await createPlatformAsset('agents', body, authContext());
      editingId.value = created.id;
      selectedId.value = created.id;
    }
    ElMessage.success('配置已保存');
    markSavedConfig();
    await loadAgents();
    const current = agents.value.find((item) => item.id === editingId.value);
    if (current) selected.value = current;
  } catch (requestError) {
    ElMessage.error(requestError instanceof Error ? requestError.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

function applyAdvancedJson(): void {
  try {
    const parsed = JSON.parse(advancedJson.value) as Record<string, unknown>;
    const synthetic: PlatformAsset = {
      id: selectedId.value ?? 0,
      assetType: 'agents',
      name: String(parsed.name ?? form.name),
      description: String(parsed.description ?? form.description),
      configJson: advancedJson.value,
      status: selected.value?.status,
    };
    selectAgent(synthetic);
    ElMessage.success('高级配置已应用');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'JSON 不合法');
  }
}

onMounted(() => {
  void loadAgents();
  void loadReferenceData()
    .then(createDraft)
    .catch(() => ElMessage.error('关联资源加载失败'));
  registerUnsavedCheck(unsavedCheck);
});

onBeforeUnmount(() => {
  unregisterUnsavedCheck(unsavedCheck);
});
</script>

<style scoped>
.agent-studio {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 270px minmax(0, 1fr) 280px;
  gap: 12px;
}

.panel {
  min-height: 0;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: var(--ui-card);
  padding: 14px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 10px;
}

.panel-head.compact h3 {
  margin: 3px 0 0;
  font-size: 18px;
}

.studio-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.list-actions {
  display: flex;
  gap: 6px;
}

.list-actions .el-input {
  width: 120px;
}

.agent-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.agent-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  background: transparent;
  color: var(--ui-text);
  text-align: left;
  cursor: pointer;
}

.agent-item.active,
.agent-item:focus-visible {
  border-color: var(--ui-accent);
  background: color-mix(in oklab, var(--ui-accent) 8%, transparent);
}
.agent-item small {
  display: block;
  margin-top: 3px;
  color: var(--ui-muted);
}

.studio-editor {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.studio-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.studio-actions {
  display: flex;
  gap: 8px;
}
.studio-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  margin-top: 12px;
}

.config-nav {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding-right: 8px;
  border-right: 1px solid var(--ui-border);
}
.config-nav button {
  border: 0;
  background: transparent;
  text-align: left;
  padding: 9px;
  border-radius: 7px;
  cursor: pointer;
  color: var(--ui-text);
}
.config-nav button.active,
.config-nav button:focus-visible {
  background: color-mix(in oklab, var(--ui-accent) 10%, transparent);
}
.config-nav span {
  display: block;
  font-size: 13px;
  font-weight: 600;
}
.config-nav small {
  display: block;
  margin-top: 3px;
  color: var(--ui-muted);
  font-size: 11px;
}

.config-form {
  min-width: 0;
  padding-left: 14px;
  overflow-y: auto;
}
.full-width {
  width: 100%;
}

.binding-hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ui-muted);
}

/* ---------- 分析看板（R2.2） ---------- */

.board-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.board-days {
  width: 130px;
}

.board-empty-note {
  font-size: 12px;
  color: var(--ui-muted);
}

.board-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-bottom: 14px;
}

.board-card {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.board-card small {
  color: var(--ui-muted);
  font-size: 12px;
}

.board-card strong {
  font-size: 18px;
  color: var(--ui-text);
}

.board-sub {
  margin: 12px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ui-text);
}

.board-trend {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  min-height: 130px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.board-trend-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  min-width: 28px;
  flex: 1;
}

.board-trend-col small {
  font-size: 10px;
  color: var(--ui-muted);
  white-space: nowrap;
}

.board-trend-bar-wrap {
  height: 100px;
  width: 100%;
  max-width: 42px;
  display: flex;
  align-items: flex-end;
  background: color-mix(in oklab, var(--ui-muted) 10%, transparent);
  border-radius: 6px;
  overflow: hidden;
}

.board-trend-bar {
  width: 100%;
  background: var(--el-color-primary);
  border-radius: 6px 6px 0 0;
  transition: height 0.2s ease;
}

.board-hint {
  margin: 0;
  font-size: 12px;
  color: var(--ui-muted);
}

.board-errors {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.board-errors li {
  font-size: 12px;
  color: var(--ui-text);
  line-height: 1.5;
}

.board-error-count {
  display: inline-block;
  margin-right: 8px;
  padding: 0 6px;
  border-radius: 8px;
  background: color-mix(in oklab, var(--el-color-danger) 14%, transparent);
  color: var(--el-color-danger);
  font-weight: 600;
}
.mono :deep(textarea) {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}
.json-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.json-apply {
  align-self: flex-end;
}

.studio-debug {
  display: flex;
  flex-direction: column;
}
.debug-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}
.debug-dirty {
  margin: 0;
  font-size: 12px;
  color: var(--ui-muted);
}
.debug-result {
  display: flex;
  align-items: center;
  gap: 8px;
}
.debug-latency {
  font-size: 12px;
  color: var(--ui-muted);
}
.debug-answer {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
  padding: 10px;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  background: color-mix(in oklab, var(--ui-panel) 70%, transparent);
  max-height: 220px;
  overflow-y: auto;
}
.debug-steps :deep(.el-collapse-item__content) {
  padding-bottom: 6px;
}
.debug-step {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 0;
  font-size: 12px;
}
.debug-step small {
  color: var(--ui-muted);
}
.debug-step-citations {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 2px;
}
.debug-meta {
  display: grid;
  grid-template-columns: 1fr;
  gap: 3px;
  padding-top: 10px;
  border-top: 1px solid var(--ui-border);
  font-size: 12px;
  color: var(--ui-muted);
}
.debug-meta span {
  color: var(--ui-text);
  font-size: 15px;
  font-weight: 600;
}
.debug-meta .debug-meta-text {
  font-size: 12px;
  font-weight: 400;
  word-break: break-word;
}

@media (max-width: 1250px) {
  .agent-studio {
    grid-template-columns: 220px minmax(0, 1fr);
  }
  .studio-debug {
    display: none;
  }
}

@media (max-width: 900px) {
  .agent-studio {
    grid-template-columns: minmax(0, 1fr);
    overflow-y: auto;
  }
  .studio-body {
    grid-template-columns: minmax(0, 1fr);
  }
  .config-nav {
    flex-direction: row;
    overflow-x: auto;
    border-right: 0;
    border-bottom: 1px solid var(--ui-border);
  }
}
</style>
