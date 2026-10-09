<template>
  <section class="agent-studio">
    <section class="studio-list panel">
      <div class="panel-head compact">
        <div>
          <p class="section-label">Agents</p>
          <h3>智能体</h3>
        </div>
        <div class="list-actions">
          <el-input v-model="search" size="small" clearable placeholder="搜索名称" @keyup.enter="loadAgents" @clear="loadAgents" />
          <el-button size="small" @click="loadAgents">查询</el-button>
          <el-button size="small" type="primary" @click="createDraft">新建</el-button>
        </div>
      </div>
      <el-empty v-if="agents.length === 0 && !loading" description="暂无智能体" :image-size="70" />
      <div v-else v-loading="loading" class="agent-list">
        <button v-for="item in agents" :key="item.id" type="button" class="agent-item" :class="{ active: selectedId === item.id }" @click="selectAgent(item)">
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
          <p class="section-label">{{ editingId ? 'Edit Agent' : 'New Agent' }}</p>
          <h3>{{ form.name || '未命名智能体' }}</h3>
        </div>
        <div class="studio-actions">
          <el-button size="small" :disabled="!editingId" :loading="saving" @click="saveAgent(true)">保存草稿</el-button>
          <el-button size="small" type="primary" :loading="saving" @click="saveAgent(false)">{{ editingId ? '保存配置' : '创建智能体' }}</el-button>
        </div>
      </div>

      <div class="studio-body">
        <nav class="config-nav" aria-label="智能体配置导航">
          <button v-for="item in sections" :key="item.key" type="button" :class="{ active: activeSection === item.key }" @click="activeSection = item.key">
            <span>{{ item.label }}</span>
            <small>{{ item.description }}</small>
          </button>
        </nav>

        <el-form v-if="activeSection === 'basic'" label-position="top" class="config-form">
          <el-form-item label="名称" required>
            <el-input v-model="form.name" maxlength="128" show-word-limit />
          </el-form-item>
          <el-form-item label="简介">
            <el-input v-model="form.description" type="textarea" :rows="3" maxlength="512" show-word-limit />
          </el-form-item>
          <el-form-item label="开场白">
            <el-input v-model="form.openingMessage" type="textarea" :rows="3" />
          </el-form-item>
          <el-form-item label="引导问题">
            <el-input v-model="form.openingQuestions" type="textarea" :rows="4" placeholder="每行一个问题" />
          </el-form-item>
        </el-form>

        <el-form v-else-if="activeSection === 'model'" label-position="top" class="config-form">
          <el-form-item label="模型服务" required>
            <el-select v-model="form.modelId" filterable clearable placeholder="选择模型服务" class="full-width">
              <el-option v-for="item in models" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="Temperature">
            <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" />
          </el-form-item>
          <el-form-item label="系统提示词" required>
            <el-input v-model="form.prompt" type="textarea" :rows="9" placeholder="定义智能体角色、边界和输出要求" />
          </el-form-item>
        </el-form>

        <el-form v-else-if="activeSection === 'resources'" label-position="top" class="config-form">
          <el-form-item label="工作流">
            <el-select v-model="form.workflowIds" multiple filterable class="full-width">
              <el-option v-for="item in workflows" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="工具">
            <el-select v-model="form.toolIds" multiple filterable class="full-width">
              <el-option v-for="item in tools" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="知识库">
            <el-select v-model="form.knowledgeBaseIds" multiple filterable class="full-width">
              <el-option v-for="item in knowledgeBases" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="安全防护">
            <el-select v-model="form.safetyGuardIds" multiple filterable class="full-width">
              <el-option v-for="item in safetyGuards" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
        </el-form>

        <el-form v-else-if="activeSection === 'execution'" label-position="top" class="config-form">
          <el-form-item label="工具描述">
            <el-input v-model="form.toolDescription" type="textarea" :rows="4" placeholder="说明已绑定工具的用途，帮助模型理解调用时机" />
          </el-form-item>
          <el-form-item label="执行前确认">
            <el-switch v-model="form.executionConfirm" active-text="需要确认" inactive-text="自动执行" />
          </el-form-item>
          <el-form-item label="短期记忆对话轮数">
            <el-input-number v-model="form.memoryTurns" :min="1" :max="100" />
          </el-form-item>
        </el-form>

        <div v-else class="config-form json-panel">
          <p>高级 JSON</p>
          <el-input v-model="advancedJson" type="textarea" :rows="16" spellcheck="false" class="mono" />
          <el-button size="small" class="json-apply" @click="applyAdvancedJson">应用 JSON</el-button>
        </div>
      </div>
    </section>

    <aside class="studio-debug panel">
      <div class="panel-head compact">
        <div>
          <p class="section-label">Debug Preview</p>
          <h3>调试预览</h3>
        </div>
        <el-tag size="small" type="info">未接入运行时</el-tag>
      </div>
      <div class="debug-body">
        <el-alert type="warning" :closable="false" title="调试能力待接入" description="当前平台配置资源已保存，但智能体配置尚未绑定到聊天执行接口；为避免伪造结果，此处不模拟成功响应。" show-icon />
        <el-button type="primary" plain disabled>运行调试</el-button>
        <div class="debug-meta">
          <p>当前状态</p>
          <span>{{ selected?.status || 'DRAFT' }}</span>
          <p>已绑定资源</p>
          <span>{{ form.workflowIds.length + form.toolIds.length + form.knowledgeBaseIds.length + form.safetyGuardIds.length }}</span>
        </div>
      </div>
    </aside>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import {
  createPlatformAsset,
  listPlatformAssets,
  updatePlatformAsset,
} from '../../api/platform';
import type { PlatformAsset } from '../../types/platform';
import { authContext } from '../../composables/useAuthState';
import { statusTag } from '../../composables/usePlatformAssets';

const sections = [
  { key: 'basic', label: '基本信息', description: '名称、简介与开场引导' },
  { key: 'model', label: '模型与提示词', description: '模型、参数与系统提示词' },
  { key: 'resources', label: '资源编排', description: '工作流、工具、知识库、防护' },
  { key: 'execution', label: '执行与对话', description: '确认策略、描述与记忆轮数' },
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
});

const configObject = computed(() => {
  const questions = form.openingQuestions.split(/\r?\n/).map(item => item.trim()).filter(Boolean);
  return {
    prompt: form.prompt,
    modelId: form.modelId,
    modelParams: { temperature: form.temperature },
    openingMessage: form.openingMessage,
    openingQuestions: questions,
    workflowIds: form.workflowIds,
    tools: form.toolIds.map(id => ({ id, description: form.toolDescription })),
    knowledgeBaseIds: form.knowledgeBaseIds,
    safetyGuardIds: form.safetyGuardIds,
    executionConfirm: form.executionConfirm,
    memoryTurns: form.memoryTurns,
  };
});

async function loadAgents(): Promise<void> {
  loading.value = true;
  try {
    const page = await listPlatformAssets('agents', authContext(), 1, 50, search.value.trim() || undefined);
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
  return Array.isArray(value) ? value.filter((item): item is number => typeof item === 'number') : [];
}

function selectAgent(item: PlatformAsset): void {
  selected.value = item;
  selectedId.value = item.id;
  editingId.value = item.id;
  activeSection.value = 'basic';
  let config: Record<string, unknown> = {};
  try {
    config = item.configJson ? JSON.parse(item.configJson) as Record<string, unknown> : {};
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
  form.toolIds = boundTools.map(tool => {
    if (typeof tool === 'number') return tool;
    if (tool && typeof tool === 'object' && 'id' in tool) {
      const id = (tool as { id?: unknown }).id;
      if (typeof id === 'number') return id;
    }
    return -1;
  }).filter(id => id > 0);
  const firstTool = boundTools[0];
  form.toolDescription = firstTool && typeof firstTool === 'object' && 'description' in firstTool
    ? String((firstTool as { description?: unknown }).description ?? '')
    : '';
  form.knowledgeBaseIds = readNumberArray(config.knowledgeBaseIds);
  form.safetyGuardIds = readNumberArray(config.safetyGuardIds);
  form.executionConfirm = config.executionConfirm !== false;
  form.memoryTurns = typeof config.memoryTurns === 'number' ? config.memoryTurns : 10;
  advancedJson.value = JSON.stringify(config, null, 2);
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
  advancedJson.value = JSON.stringify(configObject.value, null, 2);
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
    await loadAgents();
    const current = agents.value.find(item => item.id === editingId.value);
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
  void loadReferenceData().then(createDraft).catch(() => ElMessage.error('关联资源加载失败'));
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

.list-actions .el-input { width: 120px; }

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
.agent-item:focus-visible { border-color: var(--ui-accent); background: color-mix(in oklab, var(--ui-accent) 8%, transparent); }
.agent-item small { display: block; margin-top: 3px; color: var(--ui-muted); }

.studio-editor { display: flex; flex-direction: column; min-width: 0; }
.studio-head { display: flex; justify-content: space-between; align-items: center; }
.studio-actions { display: flex; gap: 8px; }
.studio-body { flex: 1; min-height: 0; display: grid; grid-template-columns: 190px minmax(0, 1fr); margin-top: 12px; }

.config-nav { display: flex; flex-direction: column; gap: 7px; padding-right: 8px; border-right: 1px solid var(--ui-border); }
.config-nav button { border: 0; background: transparent; text-align: left; padding: 9px; border-radius: 7px; cursor: pointer; color: var(--ui-text); }
.config-nav button.active, .config-nav button:focus-visible { background: color-mix(in oklab, var(--ui-accent) 10%, transparent); }
.config-nav span { display: block; font-size: 13px; font-weight: 600; }
.config-nav small { display: block; margin-top: 3px; color: var(--ui-muted); font-size: 11px; }

.config-form { min-width: 0; padding-left: 14px; overflow-y: auto; }
.full-width { width: 100%; }
.mono :deep(textarea) { font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
.json-panel { display: flex; flex-direction: column; gap: 8px; }
.json-apply { align-self: flex-end; }

.studio-debug { display: flex; flex-direction: column; }
.debug-body { flex: 1; display: flex; flex-direction: column; justify-content: center; gap: 14px; text-align: center; }
.debug-meta { display: grid; grid-template-columns: 1fr; gap: 3px; padding-top: 10px; border-top: 1px solid var(--ui-border); font-size: 12px; color: var(--ui-muted); }
.debug-meta span { color: var(--ui-text); font-size: 15px; font-weight: 600; }

@media (max-width: 1250px) {
  .agent-studio { grid-template-columns: 220px minmax(0, 1fr); }
  .studio-debug { display: none; }
}

@media (max-width: 900px) {
  .agent-studio { grid-template-columns: minmax(0, 1fr); overflow-y: auto; }
  .studio-body { grid-template-columns: minmax(0, 1fr); }
  .config-nav { flex-direction: row; overflow-x: auto; border-right: 0; border-bottom: 1px solid var(--ui-border); }
}
</style>
