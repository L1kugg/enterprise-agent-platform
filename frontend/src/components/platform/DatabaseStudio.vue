<template>
  <section class="studio-page">
    <aside class="studio-list panel">
      <div class="panel-head">
        <div>
          <p class="section-label">Databases</p>
          <h3>数据库管理</h3>
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
        >新增数据库</el-button
      >
      <el-empty
        v-if="items.length === 0 && !loading"
        description="暂无数据库配置"
        :image-size="70"
      />
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
            <small>{{ typeOf(item) }}</small>
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
          <p class="section-label">{{ selected?.id ? 'Edit Database' : 'New Database' }}</p>
          <h3>{{ form.name || '未命名数据库' }}</h3>
        </div>
        <div class="editor-actions">
          <el-button size="small" :loading="testing" :disabled="!selected?.id" @click="runTest"
            >测试连接</el-button
          >
          <el-button size="small" type="danger" :disabled="!selected?.id" @click="removeItem"
            >删除</el-button
          >
          <el-button size="small" type="primary" :loading="saving" @click="save">{{
            selected?.id ? '保存配置' : '创建数据库'
          }}</el-button>
        </div>
      </div>

      <el-form label-position="top" class="editor-form">
        <div class="form-row">
          <el-form-item label="名称" required class="grow">
            <el-input v-model="form.name" maxlength="128" show-word-limit />
          </el-form-item>
          <el-form-item label="数据库类型" required>
            <el-select v-model="form.databaseType" class="fixed-select">
              <el-option label="MySQL" value="MYSQL" />
              <el-option label="PostgreSQL" value="POSTGRESQL" />
              <el-option label="SQL Server" value="SQLSERVER" />
              <el-option label="Oracle" value="ORACLE" />
              <el-option label="ClickHouse" value="CLICKHOUSE" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="主机地址" required>
            <el-input
              v-model="form.host"
              placeholder="db.example.com"
              spellcheck="false"
              class="fixed-input"
            />
          </el-form-item>
          <el-form-item label="端口" required>
            <el-input-number v-model="form.port" :min="1" :max="65535" />
          </el-form-item>
          <el-form-item label="数据库名" required>
            <el-input v-model="form.databaseName" class="fixed-input" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="用户名" required>
            <el-input v-model="form.username" autocomplete="off" class="fixed-input" />
          </el-form-item>
          <el-form-item label="密码（加密存储，不回显）">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="留空表示不修改已有密码"
              class="fixed-input"
            />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="只读连接">
            <el-switch v-model="form.readOnly" />
            <span class="form-tip">SQL 工具只能绑定只读已验证可用的数据库</span>
          </el-form-item>
        </div>
        <el-form-item label="连接池配置（JSON）">
          <el-input
            v-model="form.poolText"
            type="textarea"
            :rows="5"
            spellcheck="false"
            class="mono"
            placeholder='{"maxPoolSize": 10, "minIdle": 2}'
          />
        </el-form-item>
        <el-alert
          v-if="lastTest"
          :type="lastTest.status === 'AVAILABLE' ? 'success' : 'error'"
          show-icon
          :closable="false"
          :title="
            '最近测试：' +
            (lastTest.status === 'AVAILABLE' ? '连接成功' : '连接失败') +
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
  testPlatformDatabase,
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
  databaseType: 'MYSQL',
  host: '',
  port: 3306,
  databaseName: '',
  username: '',
  password: '',
  readOnly: true,
  poolText: '{"maxPoolSize": 10, "minIdle": 2}',
});
const form = ref(defaultForm());

function statusTag(status?: string | null): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'AVAILABLE' || status === 'ENABLED') return 'success';
  if (status === 'UNTESTED' || status === 'DRAFT') return 'warning';
  if (status === 'UNAVAILABLE' || status === 'FAILED') return 'danger';
  return 'info';
}

function typeOf(asset: PlatformAsset): string {
  try {
    const config = JSON.parse(asset.configJson || '{}') as { databaseType?: string };
    return config.databaseType ?? '-';
  } catch {
    return '-';
  }
}

async function load(): Promise<void> {
  if (!isAdmin.value) return;
  loading.value = true;
  try {
    const page = await listPlatformAssets(
      'databases',
      authContext(),
      1,
      50,
      search.value.trim() || undefined,
    );
    items.value = page.items;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '数据库列表加载失败');
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
    if (typeof config.databaseType === 'string') next.databaseType = config.databaseType;
    if (typeof config.host === 'string') next.host = config.host;
    if (typeof config.port === 'number') next.port = config.port;
    if (typeof config.databaseName === 'string') next.databaseName = config.databaseName;
    if (typeof config.username === 'string') next.username = config.username;
    next.readOnly = config.readOnly !== false;
    if (config.pool && typeof config.pool === 'object')
      next.poolText = JSON.stringify(config.pool, null, 2);
    else if (typeof config.jdbcUrl === 'string' && !next.host) next.host = config.jdbcUrl;
  } catch {
    ElMessage.warning('数据库配置 JSON 不合法，已重置表单');
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
  if (
    !form.value.name.trim() ||
    !form.value.host.trim() ||
    !form.value.databaseName.trim() ||
    !form.value.username.trim()
  ) {
    ElMessage.warning('名称、主机、数据库名与用户名均为必填');
    return;
  }
  let pool: Record<string, unknown>;
  try {
    pool = form.value.poolText.trim()
      ? (JSON.parse(form.value.poolText) as Record<string, unknown>)
      : {};
  } catch {
    ElMessage.warning('连接池配置 JSON 不合法');
    return;
  }
  saving.value = true;
  try {
    const config: Record<string, unknown> = {
      databaseType: form.value.databaseType,
      host: form.value.host.trim(),
      port: form.value.port,
      databaseName: form.value.databaseName.trim(),
      username: form.value.username.trim(),
      readOnly: form.value.readOnly,
      pool,
    };
    if (form.value.password.trim()) config.password = form.value.password.trim();
    const body = { name: form.value.name.trim(), configJson: JSON.stringify(config) };
    if (selected.value?.id) {
      await updatePlatformAsset('databases', selected.value.id, body, authContext());
    } else {
      const created = await createPlatformAsset('databases', body, authContext());
      selected.value = created;
    }
    ElMessage.success('数据库配置已保存');
    form.value.password = '';
    await load();
    const current = items.value.find((item) => item.id === selected.value?.id);
    if (current) applyAsset(current);
  } catch (requestError) {
    ElMessage.error(requestError instanceof Error ? requestError.message : '数据库保存失败');
  } finally {
    saving.value = false;
  }
}

async function runTest(): Promise<void> {
  if (!selected.value?.id) return;
  testing.value = true;
  try {
    const result = await testPlatformDatabase(selected.value.id, authContext());
    const status = result.status ?? 'UNKNOWN';
    lastTest.value = {
      status,
      latencyMs: result.latencyMs,
      testedAt: new Date().toISOString().replace('T', ' ').slice(0, 19),
    };
    if (status === 'AVAILABLE') ElMessage.success('数据库连接成功');
    else ElMessage.error('数据库连接失败');
    await load();
    const current = items.value.find((item) => item.id === selected.value?.id);
    if (current) selected.value = current;
  } catch (error) {
    lastTest.value = {
      status: 'UNAVAILABLE',
      testedAt: new Date().toISOString().replace('T', ' ').slice(0, 19),
    };
    ElMessage.error(error instanceof Error ? error.message : '数据库连接测试失败');
  } finally {
    testing.value = false;
  }
}

async function removeItem(): Promise<void> {
  if (!selected.value?.id) return;
  try {
    await ElMessageBox.confirm(
      '确定删除「' + selected.value.name + '」？绑定该库的 SQL 工具将不可用。',
      '删除数据库',
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
    await deletePlatformAsset('databases', selected.value.id, authContext());
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
  align-items: flex-start;
}
.form-row .grow {
  flex: 1;
  min-width: 220px;
}
.fixed-select {
  width: 180px;
}
.fixed-input {
  width: 240px;
  max-width: 100%;
}
.form-tip {
  margin-left: 10px;
  color: var(--ui-muted);
  font-size: 12px;
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
