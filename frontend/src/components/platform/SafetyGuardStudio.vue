<template>
  <section class="studio-page">
    <aside class="studio-list panel">
      <div class="panel-head">
        <div>
          <p class="section-label">Safety Guards</p>
          <h3>安全防护</h3>
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
        >新建策略</el-button
      >
      <el-empty v-if="items.length === 0 && !loading" description="暂无安全策略" :image-size="70" />
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
            <small>{{ item.description || '暂无描述' }}</small>
          </span>
          <el-tag size="small" :type="item.status === 'ENABLED' ? 'success' : 'info'">{{
            item.status === 'ENABLED' ? '已启用' : '已停用'
          }}</el-tag>
        </button>
      </div>
    </aside>

    <section class="studio-editor panel">
      <div class="editor-head">
        <div>
          <p class="section-label">{{ selected?.id ? 'Edit Policy' : 'New Policy' }}</p>
          <h3>{{ form.name || '未命名安全策略' }}</h3>
        </div>
        <div class="editor-actions">
          <el-button size="small" :disabled="!selected?.id" @click="testVisible = true"
            >策略测试</el-button
          >
          <el-button size="small" :disabled="!selected?.id" @click="toggleStatus">{{
            form.enabled ? '停用' : '启用'
          }}</el-button>
          <el-button size="small" type="danger" :disabled="!selected?.id" @click="removeItem"
            >删除</el-button
          >
          <el-button size="small" type="primary" :loading="saving" @click="save">{{
            selected?.id ? '保存配置' : '创建策略'
          }}</el-button>
        </div>
      </div>

      <el-form label-position="top" class="editor-form">
        <div class="form-row">
          <el-form-item label="名称" required class="grow">
            <el-input v-model="form.name" maxlength="128" show-word-limit />
          </el-form-item>
          <el-form-item label="命中等级">
            <el-select v-model="form.hitLevel" class="fixed-select">
              <el-option label="低" value="LOW" />
              <el-option label="中" value="MEDIUM" />
              <el-option label="高（建议直接阻止）" value="HIGH" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="512" />
        </el-form-item>
        <el-form-item label="匹配方式" required>
          <el-radio-group v-model="form.matchType">
            <el-radio-button value="KEYWORD">关键词</el-radio-button>
            <el-radio-button value="REGEX">正则表达式</el-radio-button>
            <el-radio-button value="CLASSIFICATION">分类模型</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="过滤主题标签（回车添加）">
          <el-select
            v-model="form.blockedTopics"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="例如：暴力、违法、隐私"
            class="full-width"
          >
            <el-option
              v-for="topic in form.blockedTopics"
              :key="topic"
              :label="topic"
              :value="topic"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          :label="form.matchType === 'REGEX' ? '匹配正则（每行一个）' : '匹配关键词（每行一个）'"
          required
        >
          <el-input
            v-model="form.patternsText"
            type="textarea"
            :rows="6"
            spellcheck="false"
            class="mono"
            :placeholder="
              form.matchType === 'REGEX' ? '(?:银行卡|密码)\\s*[:：]' : '每行一个关键词'
            "
          />
        </el-form-item>
        <el-form-item label="阻止消息" required>
          <el-input
            v-model="form.blockMessage"
            maxlength="256"
            show-word-limit
            placeholder="命中策略后返回给用户的提示语"
          />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="记录命中日志">
            <el-switch v-model="form.recordHits" />
          </el-form-item>
          <el-form-item label="启用状态">
            <el-switch v-model="form.enabled" />
          </el-form-item>
        </div>
      </el-form>
    </section>

    <el-drawer v-model="testVisible" title="安全策略测试" size="460px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="测试文本" required>
          <el-input
            v-model="testText"
            type="textarea"
            :rows="6"
            placeholder="输入待检测的消息内容"
          />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="testError"
        type="error"
        show-icon
        :closable="false"
        :title="testError"
        class="gap-block"
      />
      <el-descriptions v-if="testResult" :column="1" border size="small" class="gap-block">
        <el-descriptions-item label="拦截结果">
          <el-tag :type="testResult.blocked ? 'danger' : 'success'">{{
            testResult.blocked ? '已拦截' : '放行'
          }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="命中等级">{{
          testResult.hitLevel ?? '-'
        }}</el-descriptions-item>
        <el-descriptions-item label="命中片段">
          <span v-if="testResult.matchedFragments?.length">{{
            testResult.matchedFragments.join('、')
          }}</span>
          <span v-else>无</span>
        </el-descriptions-item>
        <el-descriptions-item label="阻止消息">{{
          testResult.blockMessage ?? '-'
        }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="testVisible = false">关闭</el-button>
        <el-button type="primary" :loading="testing" :disabled="!selected?.id" @click="runTest"
          >运行测试</el-button
        >
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  createPlatformAsset,
  deletePlatformAsset,
  listPlatformAssets,
  testPlatformSafetyGuard,
  updatePlatformAsset,
} from '../../api/platform';
import type { PlatformAsset, SafetyGuardTestResult } from '../../types/platform';
import { authContext, isAdmin } from '../../composables/useAuthState';

const items = ref<PlatformAsset[]>([]);
const loading = ref(false);
const search = ref('');
const selected = ref<PlatformAsset | null>(null);
const saving = ref(false);

const defaultForm = () => ({
  name: '',
  description: '',
  matchType: 'KEYWORD',
  blockedTopics: [] as string[],
  patternsText: '',
  hitLevel: 'HIGH',
  blockMessage: '该话题不在允许讨论范围内。',
  recordHits: true,
  enabled: true,
});
const form = ref(defaultForm());

const testVisible = ref(false);
const testText = ref('');
const testing = ref(false);
const testResult = ref<SafetyGuardTestResult | null>(null);
const testError = ref('');

async function load(): Promise<void> {
  if (!isAdmin.value) return;
  loading.value = true;
  try {
    const page = await listPlatformAssets(
      'safety-guards',
      authContext(),
      1,
      50,
      search.value.trim() || undefined,
    );
    items.value = page.items;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '安全策略加载失败');
  } finally {
    loading.value = false;
  }
}

function applyAsset(asset: PlatformAsset): void {
  selected.value = asset;
  const next = defaultForm();
  next.name = asset.name;
  next.description = asset.description ?? '';
  next.enabled = asset.status === 'ENABLED';
  try {
    const config = JSON.parse(asset.configJson || '{}') as Record<string, unknown>;
    if (typeof config.matchType === 'string') next.matchType = config.matchType;
    if (Array.isArray(config.blockedTopics)) next.blockedTopics = config.blockedTopics.map(String);
    if (Array.isArray(config.patterns)) next.patternsText = config.patterns.map(String).join('\n');
    else if (typeof config.patternsText === 'string') next.patternsText = config.patternsText;
    if (typeof config.hitLevel === 'string') next.hitLevel = config.hitLevel;
    if (typeof config.blockMessage === 'string') next.blockMessage = config.blockMessage;
    next.recordHits = config.recordHits !== false;
    next.enabled = config.status !== 'DISABLED' && asset.status !== 'DISABLED';
  } catch {
    ElMessage.warning('策略配置 JSON 不合法，已重置表单');
  }
  form.value = next;
  testResult.value = null;
  testError.value = '';
  testText.value = '';
}

function selectItem(asset: PlatformAsset): void {
  applyAsset(asset);
}

function createDraft(): void {
  selected.value = null;
  form.value = defaultForm();
}

function parsePatterns(): { patterns: string[]; error: string | null } {
  const patterns = form.value.patternsText
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  if (patterns.length === 0) return { patterns, error: '请至少配置一个匹配规则' };
  if (form.value.matchType === 'REGEX') {
    for (const pattern of patterns) {
      try {
        new RegExp(pattern);
      } catch {
        return { patterns, error: '正则不合法：' + pattern };
      }
    }
  }
  return { patterns, error: null };
}

async function save(): Promise<void> {
  if (!form.value.name.trim()) {
    ElMessage.warning('策略名称不能为空');
    return;
  }
  if (!form.value.blockMessage.trim()) {
    ElMessage.warning('阻止消息不能为空');
    return;
  }
  const { patterns, error } = parsePatterns();
  if (error) {
    ElMessage.warning(error);
    return;
  }
  saving.value = true;
  try {
    const body = {
      name: form.value.name.trim(),
      description: form.value.description.trim() || undefined,
      status: form.value.enabled ? 'ENABLED' : 'DISABLED',
      configJson: JSON.stringify({
        matchType: form.value.matchType,
        blockedTopics: form.value.blockedTopics,
        patterns,
        hitLevel: form.value.hitLevel,
        blockMessage: form.value.blockMessage.trim(),
        recordHits: form.value.recordHits,
        status: form.value.enabled ? 'ENABLED' : 'DISABLED',
      }),
    };
    if (selected.value?.id) {
      await updatePlatformAsset('safety-guards', selected.value.id, body, authContext());
    } else {
      const created = await createPlatformAsset('safety-guards', body, authContext());
      selected.value = created;
    }
    ElMessage.success('安全策略已保存');
    await load();
    const current = items.value.find((item) => item.id === selected.value?.id);
    if (current) applyAsset(current);
  } catch (requestError) {
    ElMessage.error(requestError instanceof Error ? requestError.message : '安全策略保存失败');
  } finally {
    saving.value = false;
  }
}

async function toggleStatus(): Promise<void> {
  if (!selected.value?.id) return;
  const action = form.value.enabled ? '停用' : '启用';
  try {
    await ElMessageBox.confirm(
      '确定' + action + '策略「' + selected.value.name + '」？',
      action + '安全策略',
      {
        type: 'warning',
        confirmButtonText: action,
        cancelButtonText: '取消',
      },
    );
  } catch {
    return;
  }
  form.value.enabled = !form.value.enabled;
  await save();
}

async function removeItem(): Promise<void> {
  if (!selected.value?.id) return;
  try {
    await ElMessageBox.confirm(
      '确定删除「' + selected.value.name + '」？删除后相关智能体将失去该防护。',
      '删除安全策略',
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
    await deletePlatformAsset('safety-guards', selected.value.id, authContext());
    ElMessage.success('已删除');
    createDraft();
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败');
  }
}

async function runTest(): Promise<void> {
  if (!selected.value?.id) return;
  if (!testText.value.trim()) {
    ElMessage.warning('请输入测试文本');
    return;
  }
  testing.value = true;
  testError.value = '';
  testResult.value = null;
  try {
    testResult.value = await testPlatformSafetyGuard(
      selected.value.id,
      testText.value.trim(),
      authContext(),
    );
  } catch (error) {
    testError.value = error instanceof Error ? error.message : '策略测试失败';
  } finally {
    testing.value = false;
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
.full-width {
  width: 100%;
}
.gap-block {
  margin-bottom: 10px;
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
