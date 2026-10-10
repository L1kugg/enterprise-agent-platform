<template>
  <section class="studio-page">
    <aside class="studio-list panel">
      <div class="panel-head">
        <div>
          <p class="section-label">Model Services</p>
          <h3>模型服务</h3>
        </div>
        <div class="list-actions">
          <el-input
            v-model="search"
            size="small"
            clearable
            placeholder="搜索名称"
            @keyup.enter="load"
            @clear="load"
          />
          <el-button size="small" @click="load">查询</el-button>
        </div>
      </div>
      <el-button size="small" type="primary" class="create-btn" @click="createDraft"
        >新增模型</el-button
      >
      <el-empty v-if="items.length === 0 && !loading" description="暂无模型服务" :image-size="70" />
      <div v-else v-loading="loading" class="item-scroll">
        <button
          v-for="item in items"
          :key="item.id"
          type="button"
          class="list-item"
          :class="{ active: selected?.id === item.id }"
          @click="selectItem(item)"
        >
          <span class="item-main">
            <strong>{{ item.name }}</strong>
            <small>{{ providerOf(item) || '未配置提供商' }}</small>
          </span>
          <el-tag size="small" :type="statusTag(item.status)">{{
            item.status || 'UNTESTED'
          }}</el-tag>
        </button>
      </div>
    </aside>

    <section class="studio-editor panel">
      <div class="editor-head">
        <div>
          <p class="section-label">{{ selected?.id ? 'Edit Model' : 'New Model' }}</p>
          <h3>{{ form.name || '未命名模型服务' }}</h3>
        </div>
        <div class="editor-actions">
          <el-button size="small" :loading="testing" :disabled="!selected?.id" @click="runTest"
            >连接测试</el-button
          >
          <el-button size="small" type="danger" :disabled="!selected?.id" @click="removeItem"
            >删除</el-button
          >
          <el-button size="small" type="primary" :loading="saving" @click="save">{{
            selected?.id ? '保存配置' : '创建模型服务'
          }}</el-button>
        </div>
      </div>

      <el-form label-position="top" class="editor-form">
        <div class="form-row">
          <el-form-item label="模型类别" required>
            <el-select v-model="form.category" class="fixed-select">
              <el-option label="LLM（生成）" value="LLM" />
              <el-option label="Embedding（向量化）" value="EMBEDDING" />
              <el-option label="Rerank（重排）" value="RERANK" />
            </el-select>
          </el-form-item>
          <el-form-item label="提供商" required>
            <el-select
              v-model="form.provider"
              filterable
              allow-create
              class="fixed-select"
              placeholder="选择或输入提供商"
            >
              <el-option label="OpenAI" value="OPENAI" />
              <el-option label="DeepSeek" value="DEEPSEEK" />
              <el-option label="Qwen" value="QWEN" />
              <el-option label="Ollama" value="OLLAMA" />
              <el-option label="自定义" value="CUSTOM" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="服务名称" required class="grow">
            <el-input v-model="form.name" maxlength="128" show-word-limit />
          </el-form-item>
          <el-form-item label="模型名" required>
            <el-input
              v-model="form.modelName"
              placeholder="例如 deepseek-chat"
              class="fixed-input"
            />
          </el-form-item>
        </div>
        <el-form-item label="API 地址" required>
          <el-input
            v-model="form.apiUrl"
            placeholder="https://api.example.com/v1"
            spellcheck="false"
          />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="认证方式">
            <el-select v-model="form.authType" class="fixed-select">
              <el-option label="Bearer Token" value="BEARER" />
              <el-option label="API Key（Header）" value="API_KEY_HEADER" />
              <el-option label="无认证" value="NONE" />
            </el-select>
          </el-form-item>
          <el-form-item label="API Key（加密存储，不回显）">
            <el-input
              v-model="form.apiKey"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="留空表示不修改已有密钥"
              class="fixed-input"
            />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="超时（毫秒）">
            <el-input-number v-model="form.timeoutMs" :min="1000" :max="120000" :step="1000" />
          </el-form-item>
          <el-form-item label="重试次数">
            <el-input-number v-model="form.retry" :min="0" :max="5" />
          </el-form-item>
        </div>
        <el-form-item label="默认参数（JSON）">
          <el-input
            v-model="form.modelParamsText"
            type="textarea"
            :rows="5"
            spellcheck="false"
            class="mono"
            placeholder='{"temperature": 0.7, "maxTokens": 2048}'
          />
        </el-form-item>
        <el-alert
          v-if="lastTest"
          :type="lastTest.status === 'AVAILABLE' ? 'success' : 'error'"
          show-icon
          :closable="false"
          :title="
            '最近测试：' +
            (lastTest.status === 'AVAILABLE' ? '可用' : '不可用') +
            '，延迟 ' +
            (lastTest.latencyMs ?? '-') +
            ' ms，时间 ' +
            (lastTest.testedAt ?? '-')
          "
        />
      </el-form>
    </section>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  createPlatformAsset,
  deletePlatformAsset,
  listPlatformAssets,
  testPlatformModel,
  updatePlatformAsset,
} from '../../api/platform';
import type { PlatformAsset } from '../../types/platform';
import { authContext, isAdmin } from '../../composables/useAuthState';

interface LastTest {
  status: string;
  latencyMs?: number;
  testedAt?: string;
}

const items = ref<PlatformAsset[]>([]);
const loading = ref(false);
const search = ref('');
const selected = ref<PlatformAsset | null>(null);
const saving = ref(false);
const testing = ref(false);
const lastTest = ref<LastTest | null>(null);

const defaultForm = () => ({
  name: '',
  category: 'LLM',
  provider: 'OPENAI',
  modelName: '',
  apiUrl: '',
  authType: 'BEARER',
  apiKey: '',
  timeoutMs: 30000,
  retry: 1,
  modelParamsText: '{}',
});
const form = ref(defaultForm());

function statusTag(status?: string | null): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'AVAILABLE' || status === 'ENABLED') return 'success';
  if (status === 'UNTESTED' || status === 'DRAFT') return 'warning';
  if (status === 'UNAVAILABLE' || status === 'FAILED') return 'danger';
  return 'info';
}

function providerOf(asset: PlatformAsset): string {
  try {
    const config = JSON.parse(asset.configJson || '{}') as {
      provider?: string;
      modelName?: string;
    };
    return [config.provider, config.modelName].filter(Boolean).join(' / ');
  } catch {
    return '';
  }
}

async function load(): Promise<void> {
  if (!isAdmin.value) return;
  loading.value = true;
  try {
    const page = await listPlatformAssets(
      'model-services',
      authContext(),
      1,
      50,
      search.value.trim() || undefined,
    );
    items.value = page.items;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '模型服务加载失败');
  } finally {
    loading.value = false;
  }
}

function applyAsset(asset: PlatformAsset): void {
  selected.value = asset;
  const next = defaultForm();
  next.name = asset.name;
  try {
    const config = JSON.parse(asset.configJson || '{}') as Record<string, unknown>;
    if (typeof config.category === 'string') next.category = config.category;
    if (typeof config.provider === 'string') next.provider = config.provider;
    if (typeof config.modelName === 'string') next.modelName = config.modelName;
    if (typeof config.apiUrl === 'string') next.apiUrl = config.apiUrl;
    if (typeof config.authType === 'string') next.authType = config.authType;
    if (typeof config.timeoutMs === 'number') next.timeoutMs = config.timeoutMs;
    if (typeof config.retry === 'number') next.retry = config.retry;
    if (config.modelParams && typeof config.modelParams === 'object')
      next.modelParamsText = JSON.stringify(config.modelParams, null, 2);
  } catch {
    ElMessage.warning('模型配置 JSON 不合法，已重置表单');
  }
  form.value = next;
  lastTest.value = null;
}

function selectItem(asset: PlatformAsset): void {
  applyAsset(asset);
}

function createDraft(): void {
  selected.value = null;
  form.value = defaultForm();
  lastTest.value = null;
}

async function save(): Promise<void> {
  if (!form.value.name.trim() || !form.value.modelName.trim() || !form.value.apiUrl.trim()) {
    ElMessage.warning('名称、模型名与 API 地址均为必填');
    return;
  }
  let modelParams: Record<string, unknown>;
  try {
    modelParams = form.value.modelParamsText.trim()
      ? (JSON.parse(form.value.modelParamsText) as Record<string, unknown>)
      : {};
  } catch {
    ElMessage.warning('默认参数 JSON 不合法');
    return;
  }
  saving.value = true;
  try {
    const config: Record<string, unknown> = {
      category: form.value.category,
      provider: form.value.provider,
      modelName: form.value.modelName.trim(),
      apiUrl: form.value.apiUrl.trim(),
      authType: form.value.authType,
      timeoutMs: form.value.timeoutMs,
      retry: form.value.retry,
      modelParams,
    };
    if (form.value.apiKey.trim()) config.apiKey = form.value.apiKey.trim();
    const body = { name: form.value.name.trim(), configJson: JSON.stringify(config) };
    if (selected.value?.id) {
      await updatePlatformAsset('model-services', selected.value.id, body, authContext());
    } else {
      const created = await createPlatformAsset('model-services', body, authContext());
      selected.value = created;
    }
    ElMessage.success('模型服务已保存');
    form.value.apiKey = '';
    await load();
    const current = items.value.find((item) => item.id === selected.value?.id);
    if (current) applyAsset(current);
  } catch (requestError) {
    ElMessage.error(requestError instanceof Error ? requestError.message : '模型服务保存失败');
  } finally {
    saving.value = false;
  }
}

async function runTest(): Promise<void> {
  if (!selected.value?.id) return;
  testing.value = true;
  try {
    const result = await testPlatformModel(selected.value.id, authContext());
    const status = result.status ?? 'UNKNOWN';
    lastTest.value = { status, testedAt: new Date().toISOString().replace('T', ' ').slice(0, 19) };
    if (status === 'AVAILABLE') ElMessage.success('模型连接可用');
    else ElMessage.warning('模型连接不可用（' + status + '）');
    await load();
    const current = items.value.find((item) => item.id === selected.value?.id);
    if (current) selected.value = current;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '模型连接测试失败');
  } finally {
    testing.value = false;
  }
}

async function removeItem(): Promise<void> {
  if (!selected.value?.id) return;
  try {
    await ElMessageBox.confirm(
      '确定删除「' + selected.value.name + '」？绑定该模型的智能体将无法运行。',
      '删除模型服务',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      },
    );
  } catch {
    return;
  }
  try {
    await deletePlatformAsset('model-services', selected.value.id, authContext());
    ElMessage.success('已删除');
    createDraft();
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败');
  }
}

onMounted(() => {
  void load();
});
</script>

<style scoped>
.studio-page {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr);
  gap: 12px;
}
.panel {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
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
  flex-wrap: wrap;
}
.panel-head h3 {
  margin: 3px 0 0;
  font-size: 18px;
}
.list-actions {
  display: flex;
  gap: 6px;
}
.list-actions .el-input {
  width: 110px;
}
.create-btn {
  width: 100%;
}
.item-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.list-item {
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
.list-item.active,
.list-item:focus-visible {
  border-color: var(--ui-accent);
  background: color-mix(in oklab, var(--ui-accent) 8%, transparent);
}
.item-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.item-main strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.item-main small {
  color: var(--ui-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.editor-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.editor-head h3 {
  margin: 3px 0 0;
  font-size: 18px;
}
.editor-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.editor-form {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.form-row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}
.form-row .grow {
  flex: 1;
  min-width: 220px;
}
.fixed-select {
  width: 220px;
}
.fixed-input {
  width: 260px;
  max-width: 100%;
}
.mono :deep(textarea) {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}
@media (max-width: 900px) {
  .studio-page {
    grid-template-columns: minmax(0, 1fr);
    overflow-y: auto;
  }
}
</style>
