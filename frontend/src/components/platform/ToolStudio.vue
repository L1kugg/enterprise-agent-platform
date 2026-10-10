<template>
  <section class="tool-studio">
    <!-- 左侧：工具列表 -->
    <aside class="tool-list panel">
      <div class="panel-head">
        <div>
          <p class="section-label">Tools</p>
          <h3>工具</h3>
        </div>
        <div class="list-actions">
          <el-input
            v-model="search"
            size="small"
            clearable
            placeholder="搜索名称"
            @keyup.enter="loadTools"
            @clear="loadTools"
          />
          <el-button size="small" @click="loadTools">查询</el-button>
        </div>
      </div>
      <el-button size="small" type="primary" class="tool-create" @click="createDraft"
        >新建工具</el-button
      >
      <el-empty v-if="tools.length === 0 && !loading" description="暂无工具" :image-size="70" />
      <div v-else v-loading="loading" class="tool-items">
        <button
          v-for="item in tools"
          :key="item.id"
          type="button"
          class="tool-item"
          :class="{ active: selected?.id === item.id }"
          @click="selectTool(item)"
        >
          <span class="tool-item-main">
            <strong>{{ item.name }}</strong>
            <small>{{ toolTypeLabel(item) }}</small>
          </span>
          <el-tag size="small" :type="statusTag(item.status)">{{ item.status || 'DRAFT' }}</el-tag>
        </button>
      </div>
    </aside>

    <!-- 右侧：结构化配置 -->
    <section class="tool-editor panel">
      <div class="tool-head">
        <div>
          <p class="section-label">{{ selected?.id ? 'Edit Tool' : 'New Tool' }}</p>
          <h3>{{ form.name || '未命名工具' }}</h3>
        </div>
        <div class="tool-actions">
          <el-button size="small" :disabled="!selected?.id" @click="testVisible = true"
            >测试</el-button
          >
          <el-button size="small" type="danger" :disabled="!selected?.id" @click="removeTool"
            >删除</el-button
          >
          <el-button size="small" type="primary" :loading="saving" @click="saveTool">{{
            selected?.id ? '保存配置' : '创建工具'
          }}</el-button>
        </div>
      </div>

      <div class="tool-body">
        <nav class="tool-nav" aria-label="工具配置导航">
          <button
            v-for="step in steps"
            :key="step.key"
            type="button"
            :class="{ active: activeStep === step.key }"
            @click="activeStep = step.key"
          >
            <span>{{ step.label }}</span>
            <small>{{ step.description }}</small>
          </button>
        </nav>

        <el-form v-if="activeStep === 'basic'" label-position="top" class="tool-form">
          <el-form-item label="名称" required>
            <el-input v-model="form.name" maxlength="128" show-word-limit />
          </el-form-item>
          <el-form-item label="简介">
            <el-input v-model="form.description" type="textarea" :rows="3" maxlength="512" />
          </el-form-item>
          <el-form-item label="工具类型" required>
            <el-radio-group v-model="form.toolType" @change="onTypeChange">
              <el-radio-button value="FORM_API">表单式 API</el-radio-button>
              <el-radio-button value="CODE_API">代码式 API</el-radio-button>
              <el-radio-button value="SQL">SQL 查询</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="执行前人工确认">
            <el-switch v-model="form.executionConfirm" />
            <span class="form-tip">高风险工具建议开启：智能体调用前需用户确认参数。</span>
          </el-form-item>
        </el-form>

        <!-- 表单式 API：请求定义 -->
        <el-form
          v-else-if="activeStep === 'request' && form.toolType === 'FORM_API'"
          label-position="top"
          class="tool-form"
        >
          <el-form-item label="请求方法" required>
            <el-select v-model="form.method" class="half-width">
              <el-option
                v-for="method in HTTP_METHODS"
                :key="method"
                :label="method"
                :value="method"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="请求 URL" required>
            <el-input
              v-model="form.url"
              placeholder="https://api.example.com/v1/resource/{id}"
              spellcheck="false"
            />
          </el-form-item>
          <el-form-item label="认证方式">
            <el-select v-model="form.authType" class="half-width">
              <el-option label="无认证" value="NONE" />
              <el-option label="Bearer Token" value="BEARER" />
              <el-option label="API Key（Header）" value="API_KEY_HEADER" />
              <el-option label="Basic" value="BASIC" />
            </el-select>
          </el-form-item>
          <el-form-item label="认证凭据（保存后加密存储，不回显）">
            <el-input
              v-model="form.credential"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="留空表示不修改已有凭据"
            />
          </el-form-item>
          <el-form-item label="超时时间（毫秒）">
            <el-input-number v-model="form.timeoutMs" :min="500" :max="60000" :step="500" />
          </el-form-item>
          <el-form-item label="失败重试次数">
            <el-input-number v-model="form.retry" :min="0" :max="5" />
          </el-form-item>
        </el-form>

        <!-- 代码式 API：代码编辑 -->
        <el-form
          v-else-if="activeStep === 'request' && form.toolType === 'CODE_API'"
          label-position="top"
          class="tool-form"
        >
          <el-alert
            type="info"
            show-icon
            :closable="false"
            class="form-alert"
            title="代码在服务端沙箱执行"
            description="限制 CPU、内存、执行时间、网络与文件系统访问；凭据通过受控注入，不允许写入代码明文。前端不执行任何代码。"
          />
          <el-form-item label="运行语言" required>
            <el-select v-model="form.language" class="half-width">
              <el-option label="JavaScript" value="JAVASCRIPT" />
              <el-option label="Python" value="PYTHON" />
            </el-select>
          </el-form-item>
          <el-form-item label="工具代码" required>
            <el-input
              v-model="form.code"
              type="textarea"
              :rows="14"
              spellcheck="false"
              class="mono"
              placeholder="// 入口函数：export default async function handler(input, context)"
            />
          </el-form-item>
          <el-form-item label="执行超时（毫秒）">
            <el-input-number v-model="form.timeoutMs" :min="200" :max="10000" :step="200" />
          </el-form-item>
        </el-form>

        <!-- SQL 工具 -->
        <el-form
          v-else-if="activeStep === 'request' && form.toolType === 'SQL'"
          label-position="top"
          class="tool-form"
        >
          <el-alert
            type="warning"
            show-icon
            :closable="false"
            class="form-alert"
            title="只读 SQL 限制"
            description="仅允许 SELECT 单语句；禁止多语句与危险语法；参数化执行；限制返回行数与超时。数据库密码不会回显。"
          />
          <el-form-item label="目标数据库" required>
            <el-select
              v-model="form.databaseId"
              filterable
              placeholder="选择数据库配置"
              class="half-width"
            >
              <el-option
                v-for="item in databases"
                :key="item.id"
                :label="item.name"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="SQL（支持 :name 占位符）" required>
            <el-input
              v-model="form.sql"
              type="textarea"
              :rows="8"
              spellcheck="false"
              class="mono"
              placeholder="SELECT id, name FROM users WHERE tenant_id = :tenantId LIMIT 100"
            />
          </el-form-item>
          <div class="inline-fields">
            <el-form-item label="最大返回行数">
              <el-input-number v-model="form.maxRows" :min="1" :max="1000" />
            </el-form-item>
            <el-form-item label="超时（毫秒）">
              <el-input-number v-model="form.timeoutMs" :min="500" :max="30000" :step="500" />
            </el-form-item>
          </div>
        </el-form>

        <el-form v-else-if="activeStep === 'request'" label-position="top" class="tool-form">
          <el-empty description="请先选择工具类型" :image-size="80" />
        </el-form>

        <!-- 参数 Schema -->
        <div v-else-if="activeStep === 'params'" class="tool-form">
          <div class="params-head">
            <h4>参数定义</h4>
            <el-button size="small" @click="addParam">添加参数</el-button>
          </div>
          <el-table :data="form.params" size="small" empty-text="暂无参数" class="params-table">
            <el-table-column label="位置" width="110">
              <template #default="{ row }">
                <el-select v-if="form.toolType === 'FORM_API'" v-model="row.location" size="small">
                  <el-option label="Query" value="QUERY" />
                  <el-option label="Path" value="PATH" />
                  <el-option label="Header" value="HEADER" />
                  <el-option label="Body" value="BODY" />
                </el-select>
                <span v-else>SQL</span>
              </template>
            </el-table-column>
            <el-table-column label="名称" min-width="120">
              <template #default="{ row }">
                <el-input v-model="row.name" size="small" placeholder="参数名" />
              </template>
            </el-table-column>
            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <el-select v-model="row.type" size="small">
                  <el-option v-for="item in PARAM_TYPES" :key="item" :label="item" :value="item" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="必填" width="70" align="center">
              <template #default="{ row }">
                <el-switch v-model="row.required" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="默认值" min-width="100">
              <template #default="{ row }">
                <el-input v-model="row.defaultValue" size="small" placeholder="可选" />
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="140">
              <template #default="{ row }">
                <el-input v-model="row.description" size="small" placeholder="参数用途" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="70" align="center">
              <template #default="{ $index }">
                <el-button size="small" link type="danger" @click="form.params.splice($index, 1)"
                  >删除</el-button
                >
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 响应与安全 -->
        <el-form v-else-if="activeStep === 'response'" label-position="top" class="tool-form">
          <template v-if="form.toolType === 'FORM_API'">
            <el-form-item label="响应映射（JSON Path → 输出字段）">
              <el-input
                :model-value="responseMappingText"
                type="textarea"
                :rows="8"
                spellcheck="false"
                class="mono"
                @update:model-value="onResponseMappingInput"
              />
            </el-form-item>
            <el-form-item label="错误映射（JSON）">
              <el-input
                :model-value="errorMappingText"
                type="textarea"
                :rows="6"
                spellcheck="false"
                class="mono"
                @update:model-value="onErrorMappingInput"
              />
            </el-form-item>
          </template>
          <template v-else-if="form.toolType === 'CODE_API'">
            <el-form-item label="输入 Schema（JSON）">
              <el-input
                :model-value="inputSchemaText"
                type="textarea"
                :rows="7"
                spellcheck="false"
                class="mono"
                @update:model-value="(value: string) => onSchemaJsonInput('inputSchema', value)"
              />
            </el-form-item>
            <el-form-item label="输出 Schema（JSON）">
              <el-input
                :model-value="outputSchemaText"
                type="textarea"
                :rows="7"
                spellcheck="false"
                class="mono"
                @update:model-value="(value: string) => onSchemaJsonInput('outputSchema', value)"
              />
            </el-form-item>
          </template>
          <template v-else>
            <el-empty description="SQL 工具按列结构自动生成响应" :image-size="80" />
          </template>
          <el-form-item label="执行确认提示语">
            <el-input
              v-model="form.confirmMessage"
              maxlength="256"
              placeholder="展示给用户的风险说明，例如：即将查询订单数据库"
            />
          </el-form-item>
        </el-form>
      </div>
    </section>

    <!-- 测试抽屉 -->
    <el-drawer v-model="testVisible" title="工具测试" size="480px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item
          v-for="param in form.params.filter((item) => item.name)"
          :key="param.name"
          :label="param.name + (param.required ? '（必填）' : '')"
        >
          <el-switch v-if="param.type === 'BOOLEAN'" v-model="testParams[param.name]" />
          <el-input-number v-else-if="param.type === 'NUMBER'" v-model="testParams[param.name]" />
          <el-input
            v-else-if="param.type === 'OBJECT' || param.type === 'ARRAY'"
            v-model="testParams[param.name]"
            type="textarea"
            :rows="3"
            class="mono"
            placeholder="JSON 值"
          />
          <el-input
            v-else
            v-model="testParams[param.name]"
            :placeholder="param.description || '输入测试值'"
          />
        </el-form-item>
        <el-form-item
          v-if="form.params.filter((item) => item.name).length === 0"
          label="该工具暂无参数"
        >
          <el-text type="info">直接点击运行执行测试。</el-text>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="testError"
        type="error"
        show-icon
        :closable="false"
        :title="testError"
        class="test-error"
      />
      <template v-if="testResult">
        <el-descriptions :column="1" border size="small" class="test-result">
          <el-descriptions-item label="状态">{{ testResult.status ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="耗时"
            >{{ testResult.latencyMs ?? '-' }} ms</el-descriptions-item
          >
          <el-descriptions-item label="响应摘要">
            <pre class="test-summary">{{
              testResult.responseSummary || formatJson(testResult)
            }}</pre>
          </el-descriptions-item>
        </el-descriptions>
      </template>
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
  testPlatformTool,
  updatePlatformAsset,
} from '../../api/platform';
import type { PlatformAsset, ToolParamType, ToolTestResult } from '../../types/platform';
import { authContext, isAdmin } from '../../composables/useAuthState';

type ToolType = 'FORM_API' | 'CODE_API' | 'SQL';

interface ParamRow {
  location: 'QUERY' | 'PATH' | 'HEADER' | 'BODY';
  name: string;
  type: ToolParamType;
  required: boolean;
  defaultValue: string;
  description: string;
}

const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;
const PARAM_TYPES: ToolParamType[] = ['STRING', 'NUMBER', 'BOOLEAN', 'OBJECT', 'ARRAY'];

const steps = [
  { key: 'basic', label: '基本信息', description: '名称、类型与执行确认' },
  { key: 'request', label: formLabelRequest(), description: '请求定义 / 代码 / SQL' },
  { key: 'params', label: '参数 Schema', description: '参数类型、必填与默认值' },
  { key: 'response', label: '响应与安全', description: '响应映射与确认提示' },
] as const;

function formLabelRequest(): string {
  return '请求定义';
}

const tools = ref<PlatformAsset[]>([]);
const databases = ref<PlatformAsset[]>([]);
const loading = ref(false);
const search = ref('');
const selected = ref<PlatformAsset | null>(null);
const saving = ref(false);
const activeStep = ref<(typeof steps)[number]['key']>('basic');

const form = ref(defaultForm());

const testVisible = ref(false);
const testing = ref(false);
const testResult = ref<ToolTestResult | null>(null);
const testError = ref('');
const testParams = ref<Record<string, string | number | boolean>>({});

function defaultForm() {
  return {
    name: '',
    description: '',
    toolType: 'FORM_API' as ToolType,
    executionConfirm: false,
    confirmMessage: '',
    method: 'GET',
    url: '',
    authType: 'NONE',
    credential: '',
    timeoutMs: 5000,
    retry: 0,
    language: 'JAVASCRIPT',
    code: '',
    databaseId: null as number | null,
    sql: '',
    maxRows: 100,
    params: [] as ParamRow[],
    responseMapping: {},
    errorMapping: {},
    inputSchema: {},
    outputSchema: {},
  };
}

const responseMappingText = ref('{}');
const errorMappingText = ref('{}');
const inputSchemaText = ref('{}');
const outputSchemaText = ref('{}');

function statusTag(status?: string | null): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'ENABLED' || status === 'AVAILABLE') return 'success';
  if (status === 'DRAFT' || status === 'PENDING') return 'warning';
  if (status === 'FAILED') return 'danger';
  return 'info';
}

function toolTypeLabel(asset: PlatformAsset): string {
  try {
    const config = JSON.parse(asset.configJson || '{}') as { toolType?: string };
    const labels: Record<string, string> = {
      FORM_API: '表单式 API',
      CODE_API: '代码式 API',
      SQL: 'SQL 查询',
    };
    return labels[config.toolType ?? ''] ?? '未分类';
  } catch {
    return '未分类';
  }
}

function formatJson(value: unknown): string {
  try {
    return JSON.stringify(value ?? {}, null, 2);
  } catch {
    return String(value);
  }
}

function applyAsset(asset: PlatformAsset): void {
  selected.value = asset;
  const next = defaultForm();
  next.name = asset.name;
  next.description = asset.description ?? '';
  try {
    const config = JSON.parse(asset.configJson || '{}') as Record<string, unknown>;
    if (typeof config.toolType === 'string') next.toolType = config.toolType as ToolType;
    next.executionConfirm = Boolean(config.executionConfirm);
    if (typeof config.confirmMessage === 'string') next.confirmMessage = config.confirmMessage;
    if (typeof config.method === 'string') next.method = config.method;
    if (typeof config.url === 'string') next.url = config.url;
    if (typeof config.authType === 'string') next.authType = config.authType;
    if (typeof config.timeoutMs === 'number') next.timeoutMs = config.timeoutMs;
    if (typeof config.retry === 'number') next.retry = config.retry;
    if (typeof config.language === 'string') next.language = config.language;
    if (typeof config.code === 'string') next.code = config.code;
    if (typeof config.databaseId === 'number') next.databaseId = config.databaseId;
    if (typeof config.sql === 'string') next.sql = config.sql;
    if (typeof config.maxRows === 'number') next.maxRows = config.maxRows;
    if (Array.isArray(config.params)) {
      next.params = config.params.map((raw) => {
        const item = raw as Record<string, unknown>;
        return {
          location: (['QUERY', 'PATH', 'HEADER', 'BODY'] as const).includes(
            item.location as 'QUERY',
          )
            ? (item.location as ParamRow['location'])
            : 'QUERY',
          name: typeof item.name === 'string' ? item.name : '',
          type: (PARAM_TYPES as string[]).includes(item.type as string)
            ? (item.type as ToolParamType)
            : 'STRING',
          required: Boolean(item.required),
          defaultValue: typeof item.defaultValue === 'string' ? item.defaultValue : '',
          description: typeof item.description === 'string' ? item.description : '',
        };
      });
    }
    if (config.responseMapping && typeof config.responseMapping === 'object')
      next.responseMapping = config.responseMapping as Record<string, unknown>;
    if (config.errorMapping && typeof config.errorMapping === 'object')
      next.errorMapping = config.errorMapping as Record<string, unknown>;
    if (config.inputSchema && typeof config.inputSchema === 'object')
      next.inputSchema = config.inputSchema as Record<string, unknown>;
    if (config.outputSchema && typeof config.outputSchema === 'object')
      next.outputSchema = config.outputSchema as Record<string, unknown>;
  } catch {
    ElMessage.warning('工具配置 JSON 不合法，已重置表单');
  }
  responseMappingText.value = JSON.stringify(next.responseMapping ?? {}, null, 2);
  errorMappingText.value = JSON.stringify(next.errorMapping ?? {}, null, 2);
  inputSchemaText.value = JSON.stringify(next.inputSchema ?? {}, null, 2);
  outputSchemaText.value = JSON.stringify(next.outputSchema ?? {}, null, 2);
  form.value = next;
  testResult.value = null;
  testError.value = '';
  testParams.value = {};
}

async function loadTools(): Promise<void> {
  if (!isAdmin.value) return;
  loading.value = true;
  try {
    const [toolPage, dbPage] = await Promise.all([
      listPlatformAssets('tools', authContext(), 1, 50, search.value.trim() || undefined),
      listPlatformAssets('databases', authContext(), 1, 100),
    ]);
    tools.value = toolPage.items;
    databases.value = dbPage.items;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工具列表加载失败');
  } finally {
    loading.value = false;
  }
}

function selectTool(asset: PlatformAsset): void {
  applyAsset(asset);
  activeStep.value = 'basic';
}

function createDraft(): void {
  selected.value = null;
  form.value = defaultForm();
  activeStep.value = 'basic';
}

function onTypeChange(): void {
  form.value.params = form.value.params.map((param) => ({
    ...param,
    location: form.value.toolType === 'SQL' ? 'QUERY' : param.location,
  }));
}

function addParam(): void {
  form.value.params.push({
    location: 'QUERY',
    name: '',
    type: 'STRING',
    required: false,
    defaultValue: '',
    description: '',
  });
}

function parseJsonOrThrow(value: string, label: string): Record<string, unknown> {
  try {
    return value.trim() ? (JSON.parse(value) as Record<string, unknown>) : {};
  } catch (error) {
    throw new Error(
      label + ' JSON 不合法：' + (error instanceof Error ? error.message : String(error)),
    );
  }
}

function onResponseMappingInput(value: string): void {
  responseMappingText.value = value;
}

function onErrorMappingInput(value: string): void {
  errorMappingText.value = value;
}

function onSchemaJsonInput(key: 'inputSchema' | 'outputSchema', value: string): void {
  if (key === 'inputSchema') inputSchemaText.value = value;
  else outputSchemaText.value = value;
}

function buildConfig(): Record<string, unknown> {
  const responseMapping = parseJsonOrThrow(responseMappingText.value, '响应映射');
  const errorMapping = parseJsonOrThrow(errorMappingText.value, '错误映射');
  const inputSchema = parseJsonOrThrow(inputSchemaText.value, '输入 Schema');
  const outputSchema = parseJsonOrThrow(outputSchemaText.value, '输出 Schema');

  const config: Record<string, unknown> = {
    toolType: form.value.toolType,
    executionConfirm: form.value.executionConfirm,
    confirmMessage: form.value.confirmMessage || undefined,
    params: form.value.params.filter((param) => param.name.trim()),
  };
  if (form.value.toolType === 'FORM_API') {
    config.method = form.value.method;
    config.url = form.value.url;
    config.authType = form.value.authType;
    config.timeoutMs = form.value.timeoutMs;
    config.retry = form.value.retry;
    config.responseMapping = responseMapping;
    config.errorMapping = errorMapping;
    if (form.value.credential.trim()) config.credential = form.value.credential.trim();
  } else if (form.value.toolType === 'CODE_API') {
    config.language = form.value.language;
    config.code = form.value.code;
    config.timeoutMs = form.value.timeoutMs;
    config.inputSchema = inputSchema;
    config.outputSchema = outputSchema;
  } else {
    config.databaseId = form.value.databaseId;
    config.sql = form.value.sql;
    config.maxRows = form.value.maxRows;
    config.timeoutMs = form.value.timeoutMs;
  }
  return config;
}

function validate(): string | null {
  if (!form.value.name.trim()) return '工具名称不能为空';
  if (form.value.toolType === 'FORM_API' && !form.value.url.trim()) return '请求 URL 不能为空';
  if (form.value.toolType === 'CODE_API' && !form.value.code.trim()) return '工具代码不能为空';
  if (form.value.toolType === 'SQL') {
    if (!form.value.databaseId) return '请选择目标数据库';
    if (!form.value.sql.trim()) return 'SQL 不能为空';
    if (!/^\s*select\b/i.test(form.value.sql)) return 'SQL 工具仅允许 SELECT 查询';
    if (form.value.sql.includes(';')) return 'SQL 不允许包含多语句';
  }
  const names = form.value.params.map((param) => param.name.trim()).filter(Boolean);
  if (new Set(names).size !== names.length) return '存在重复的参数名';
  return null;
}

async function saveTool(): Promise<void> {
  const error = validate();
  if (error) {
    ElMessage.warning(error);
    return;
  }
  saving.value = true;
  try {
    const body = {
      name: form.value.name.trim(),
      description: form.value.description.trim() || undefined,
      configJson: JSON.stringify(buildConfig()),
    };
    if (selected.value?.id) {
      await updatePlatformAsset('tools', selected.value.id, body, authContext());
    } else {
      const created = await createPlatformAsset('tools', body, authContext());
      selected.value = created;
    }
    ElMessage.success('工具配置已保存');
    await loadTools();
    const current = tools.value.find((item) => item.id === selected.value?.id);
    if (current) applyAsset(current);
  } catch (requestError) {
    ElMessage.error(requestError instanceof Error ? requestError.message : '工具保存失败');
  } finally {
    saving.value = false;
  }
}

async function removeTool(): Promise<void> {
  if (!selected.value?.id) return;
  try {
    await ElMessageBox.confirm(
      '确定删除「' + selected.value.name + '」？该操作不可恢复。',
      '删除工具',
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
    await deletePlatformAsset('tools', selected.value.id, authContext());
    ElMessage.success('已删除');
    createDraft();
    await loadTools();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败');
  }
}

async function runTest(): Promise<void> {
  if (!selected.value?.id) return;
  const params: Record<string, unknown> = {};
  for (const param of form.value.params) {
    if (!param.name) continue;
    const value = testParams.value[param.name];
    if (value === undefined || value === '') continue;
    if (param.type === 'OBJECT' || param.type === 'ARRAY') {
      try {
        params[param.name] = JSON.parse(String(value));
      } catch {
        ElMessage.warning('参数「' + param.name + '」需要合法 JSON');
        return;
      }
    } else if (param.type === 'NUMBER') {
      params[param.name] = Number(value);
    } else {
      params[param.name] = value;
    }
  }
  testing.value = true;
  testError.value = '';
  testResult.value = null;
  try {
    testResult.value = await testPlatformTool(selected.value.id, params, authContext());
  } catch (error) {
    testError.value = error instanceof Error ? error.message : '工具测试失败';
  } finally {
    testing.value = false;
  }
}

onMounted(() => {
  void loadTools();
});
</script>

<style scoped>
.tool-studio {
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
.tool-create {
  width: 100%;
}

.tool-items {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.tool-item {
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
.tool-item.active,
.tool-item:focus-visible {
  border-color: var(--ui-accent);
  background: color-mix(in oklab, var(--ui-accent) 8%, transparent);
}
.tool-item-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.tool-item-main strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tool-item-main small {
  color: var(--ui-muted);
}

.tool-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.tool-head h3 {
  margin: 3px 0 0;
  font-size: 18px;
}
.tool-actions {
  display: flex;
  gap: 8px;
}

.tool-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 196px minmax(0, 1fr);
  gap: 12px;
}
.tool-nav {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding-right: 8px;
  border-right: 1px solid var(--ui-border);
}
.tool-nav button {
  border: 0;
  background: transparent;
  text-align: left;
  padding: 9px;
  border-radius: 7px;
  cursor: pointer;
  color: var(--ui-text);
}
.tool-nav button.active,
.tool-nav button:focus-visible {
  background: color-mix(in oklab, var(--ui-accent) 10%, transparent);
}
.tool-nav span {
  display: block;
  font-size: 13px;
  font-weight: 600;
}
.tool-nav small {
  display: block;
  margin-top: 3px;
  color: var(--ui-muted);
  font-size: 11px;
}

.tool-form {
  min-width: 0;
  overflow-y: auto;
}
.form-tip {
  margin-left: 10px;
  color: var(--ui-muted);
  font-size: 12px;
}
.form-alert {
  margin-bottom: 12px;
}
.half-width {
  width: 260px;
  max-width: 100%;
}
.inline-fields {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}
.params-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.params-head h4 {
  margin: 0;
  font-size: 14px;
}
.params-table {
  width: 100%;
}

.mono :deep(textarea) {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}
.test-error {
  margin-bottom: 10px;
}
.test-summary {
  margin: 0;
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
}

@media (max-width: 900px) {
  .tool-studio {
    grid-template-columns: minmax(0, 1fr);
    overflow-y: auto;
  }
  .tool-body {
    grid-template-columns: minmax(0, 1fr);
  }
  .tool-nav {
    flex-direction: row;
    overflow-x: auto;
    border-right: 0;
    border-bottom: 1px solid var(--ui-border);
  }
}
</style>
