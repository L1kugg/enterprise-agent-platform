<template>
  <section class="mcp-panel panel">
    <div class="panel-head mcp-head">
      <div>
        <h3>MCP 工具</h3>
        <p class="mcp-sub">外部标准 MCP server 的注册与发现：server 增删改即时生效，无需改配置重启</p>
      </div>
      <div class="mcp-actions">
        <el-button size="small" :loading="refreshing" @click="refreshRuntime">重新发现</el-button>
        <el-button size="small" type="primary" @click="openCreate">注册 MCP 服务</el-button>
      </div>
    </div>

    <div v-loading="loading" class="mcp-body">
      <el-empty v-if="servers.length === 0 && !loading" description="暂无 MCP 服务（可在下方注册或改配置文件）" :image-size="80" />
      <div v-for="server in servers" :key="server.name" class="mcp-server-card">
        <div class="mcp-server-head">
          <div class="mcp-server-title">
            <strong>{{ server.name }}</strong>
            <el-tag size="small" :type="server.source === 'asset' ? 'success' : 'info'">
              {{ server.source === 'asset' ? '平台资产' : '配置文件' }}
            </el-tag>
            <el-tag size="small" type="success">{{ server.discoveredTools.length }} 个工具</el-tag>
          </div>
          <div class="mcp-server-actions">
            <el-button
              v-if="server.source === 'asset'"
              size="small"
              @click="openEdit(server)"
              >编辑</el-button
            >
            <el-button
              size="small"
              :loading="probingName === server.name"
              @click="probeServer(server)"
              >测连通</el-button
            >
            <el-button
              v-if="server.source === 'asset'"
              size="small"
              type="danger"
              plain
              @click="removeServer(server)"
              >删除</el-button
            >
          </div>
        </div>
        <p class="mcp-server-url">{{ server.baseUrl }}{{ server.endpoint }}</p>
        <div v-if="server.discoveredTools.length > 0" class="mcp-tools">
          <div v-for="tool in server.discoveredTools" :key="tool.name" class="mcp-tool-row">
            <code class="mcp-tool-name">{{ server.name }}/{{ tool.name }}</code>
            <span class="mcp-tool-desc">{{ tool.description || '（无描述）' }}</span>
            <span v-if="server.toolTimeouts?.[tool.name]" class="mcp-tool-timeout"
              >{{ server.toolTimeouts[tool.name] }}ms</span
            >
          </div>
        </div>
        <p v-else class="mcp-no-tools">工具发现为空（server 未上线或白名单未放行）</p>
        <p v-if="probeError === server.name" class="mcp-probe-error">连通失败：{{ probeMessage }}</p>
        <p v-if="probeOk === server.name" class="mcp-probe-ok">
          连通正常（{{ probeLatency }}ms，发现 {{ probeTools }} 个工具）
        </p>
      </div>

      <!-- 注册/编辑对话框 -->
      <el-dialog v-model="dialogVisible" :title="editingId ? '编辑 MCP 服务' : '注册 MCP 服务'" width="min(560px, 94vw)">
        <el-form label-position="top" size="small">
          <el-form-item label="名称（全局唯一，同名覆盖配置文件项）">
            <el-input v-model="form.name" placeholder="如 my-mcp-server" />
          </el-form-item>
          <el-form-item label="Base URL">
            <el-input v-model="form.baseUrl" placeholder="https://mcp.example.com" />
          </el-form-item>
          <el-form-item label="Endpoint（默认 /mcp）">
            <el-input v-model="form.endpoint" placeholder="/mcp" />
          </el-form-item>
          <el-form-item label="工具白名单与超时（毫秒）">
            <div class="tool-rows">
              <div v-for="(row, index) in form.tools" :key="row.name" class="tool-row">
                <el-input v-model="row.name" placeholder="工具名" class="tool-name" />
                <el-input-number v-model="row.timeoutMs" :min="1000" :step="1000" class="tool-timeout" />
                <el-button size="small" text type="danger" @click="form.tools.splice(index, 1)">删除</el-button>
              </div>
              <el-button size="small" @click="form.tools.push({ name: '', timeoutMs: 15000 })">添加工具</el-button>
            </div>
          </el-form-item>
          <el-form-item label="探测（保存前可先验证连通）">
            <el-button
              size="small"
              :disabled="!form.baseUrl"
              :loading="probingName === '__draft__'"
              @click="probeDraft"
              >探测 {{ form.baseUrl || 'Base URL' }}</el-button
            >
            <span v-if="draftProbeText" class="mcp-probe-inline" :class="{ ok: draftProbeOk }">{{ draftProbeText }}</span>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button size="small" @click="dialogVisible = false">取消</el-button>
          <el-button size="small" type="primary" :loading="saving" @click="saveServer">保存并生效</el-button>
        </template>
      </el-dialog>
    </div>
  </section>
</template>

<script setup lang="ts">
// MCP 工具面板：注册表合并视图（yml 配置文件项 + 平台资产项）展示、
// 资产增删改（保存即刷新运行时：注册表重建 + 客户端失效 + 工具重发现）、
// 草稿连通探测（保存前验证 baseUrl 可达且能发现工具）。
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  createPlatformAsset,
  deletePlatformAsset,
  fetchDiscoveredMcpServers,
  probeMcpServer,
  refreshMcpRuntime,
  updatePlatformAsset,
  type McpProbeResult,
  type McpServerView,
} from '../../api/platform';
import { authContext, isAdmin } from '../../composables/useAuthState';

const servers = ref<McpServerView[]>([]);
const loading = ref(false);
const refreshing = ref(false);
const probingName = ref('');
const probeError = ref('');
const probeOk = ref('');
const probeMessage = ref('');
const probeLatency = ref(0);
const probeTools = ref(0);
const saving = ref(false);

const dialogVisible = ref(false);
const editingId = ref<number | null>(null);
const form = ref({
  name: '',
  baseUrl: '',
  endpoint: '/mcp',
  tools: [] as { name: string; timeoutMs: number }[],
});

function loadServers(): void {
  if (!isAdmin.value) return;
  loading.value = true;
  fetchDiscoveredMcpServers(authContext())
    .then((items) => {
      servers.value = items;
    })
    .catch((error) => {
      ElMessage.error(error instanceof Error ? error.message : 'MCP 工具列表加载失败');
    })
    .finally(() => {
      loading.value = false;
    });
}

async function refreshRuntime(): Promise<void> {
  refreshing.value = true;
  try {
    await refreshMcpRuntime(authContext());
    const items = await fetchDiscoveredMcpServers(authContext());
    servers.value = items;
    ElMessage.success(`已重新发现 ${items.length} 个 MCP 服务`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '重新发现失败');
  } finally {
    refreshing.value = false;
  }
}

function openCreate(): void {
  editingId.value = null;
  form.value = { name: '', baseUrl: '', endpoint: '/mcp', tools: [] };
  draftProbeText.value = '';
  dialogVisible.value = true;
}

function openEdit(server: McpServerView): void {
  editingId.value = server.assetId ?? null;
  form.value = {
    name: server.name,
    baseUrl: server.baseUrl,
    endpoint: server.endpoint,
    tools: Object.entries(server.toolTimeouts ?? {}).map(([name, timeoutMs]) => ({ name, timeoutMs })),
  };
  draftProbeText.value = '';
  dialogVisible.value = true;
}

function buildConfigJson(): string {
  const tools: Record<string, { timeoutMs: number }> = {};
  form.value.tools
    .filter(row => row.name.trim())
    .forEach(row => {
      tools[row.name.trim()] = { timeoutMs: row.timeoutMs };
    });
  return JSON.stringify({
    baseUrl: form.value.baseUrl.trim(),
    endpoint: form.value.endpoint.trim() || '/mcp',
    enabled: true,
    tools,
  });
}

async function saveServer(): Promise<void> {
  if (!form.value.name.trim() || !form.value.baseUrl.trim()) {
    ElMessage.warning('名称与 Base URL 必填');
    return;
  }
  saving.value = true;
  try {
    const body = {
      name: form.value.name.trim(),
      description: 'MCP server（工具管理页注册）',
      configJson: buildConfigJson(),
      status: 'ENABLED',
    };
    if (editingId.value) {
      await updatePlatformAsset('mcp-servers', editingId.value, body, authContext());
      ElMessage.success('已保存并生效');
    } else {
      await createPlatformAsset('mcp-servers', body, authContext());
      ElMessage.success('已注册并生效');
    }
    dialogVisible.value = false;
    loadServers();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function removeServer(server: McpServerView): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除 MCP 服务「${server.name}」？其工具将从智能体可用列表移除。`, '删除 MCP 服务', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
  } catch {
    return;
  }
  try {
    if (server.assetId) {
      await deletePlatformAsset('mcp-servers', server.assetId, authContext());
    }
    ElMessage.success('已删除');
    loadServers();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败');
  }
}

const draftProbeText = ref('');
const draftProbeOk = ref(false);

async function probeDraft(): Promise<void> {
  probingName.value = '__draft__';
  draftProbeText.value = '探测中…';
  try {
    const result: McpProbeResult = await probeMcpServer(form.value.baseUrl.trim(), form.value.endpoint, authContext());
    if (result.ok === 1) {
      draftProbeOk.value = true;
      draftProbeText.value = `连通正常（${result.latencyMs}ms，发现 ${result.tools?.length ?? 0} 个工具）`;
    } else {
      draftProbeOk.value = false;
      draftProbeText.value = `失败：${result.error ?? '未知原因'}`;
    }
  } catch (error) {
    draftProbeOk.value = false;
    draftProbeText.value = error instanceof Error ? error.message : '探测失败';
  } finally {
    probingName.value = '';
  }
}

async function probeServer(server: McpServerView): Promise<void> {
  probingName.value = server.name;
  probeError.value = '';
  probeOk.value = '';
  try {
    const result: McpProbeResult = await probeMcpServer(server.baseUrl, server.endpoint, authContext());
    if (result.ok === 1) {
      probeOk.value = server.name;
      probeLatency.value = result.latencyMs ?? 0;
      probeTools.value = result.tools?.length ?? 0;
    } else {
      probeError.value = server.name;
      probeMessage.value = result.error ?? '未知原因';
    }
  } catch (error) {
    probeError.value = server.name;
    probeMessage.value = error instanceof Error ? error.message : '探测失败';
  } finally {
    probingName.value = '';
  }
}

onMounted(loadServers);
</script>

<style scoped>
.mcp-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  overflow: auto;
}

.mcp-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.mcp-head h3 {
  margin: 0;
}

.mcp-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ui-muted);
}

.mcp-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.mcp-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 120px;
}

.mcp-server-card {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 12px 14px;
  background: color-mix(in oklab, var(--ui-panel) 80%, transparent);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.mcp-server-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.mcp-server-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mcp-server-actions {
  display: flex;
  gap: 6px;
}

.mcp-server-url {
  margin: 0;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  color: var(--ui-muted);
  word-break: break-all;
}

.mcp-tools {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.mcp-tool-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
  padding: 4px 8px;
  border-radius: 6px;
  background: color-mix(in oklab, var(--ui-panel) 60%, transparent);
}

.mcp-tool-name {
  font-size: 12px;
  color: var(--ui-accent-strong);
  flex-shrink: 0;
}

.mcp-tool-desc {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ui-muted);
}

.mcp-tool-timeout {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--ui-muted);
  font-variant-numeric: tabular-nums;
}

.mcp-no-tools {
  margin: 0;
  font-size: 12px;
  color: var(--ui-muted);
}

.mcp-probe-error {
  margin: 0;
  font-size: 12px;
  color: #dc2626;
}

.mcp-probe-ok {
  margin: 0;
  font-size: 12px;
  color: var(--ui-accent-strong);
}

.mcp-probe-inline {
  margin-left: 10px;
  font-size: 12px;
  color: #dc2626;
}

.mcp-probe-inline.ok {
  color: var(--ui-accent-strong);
}

.tool-rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.tool-row {
  display: grid;
  grid-template-columns: 1fr 140px auto;
  gap: 8px;
  align-items: center;
}

.tool-timeout {
  width: 140px;
}
</style>
