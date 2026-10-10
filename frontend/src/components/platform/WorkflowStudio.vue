<template>
  <section class="workflow-studio">
    <!-- 左侧：工作流列表 -->
    <aside class="wf-list panel">
      <div class="panel-head">
        <div>
          <p class="section-label">Workflows</p>
          <h3>工作流</h3>
        </div>
        <div class="list-actions">
          <el-input
            v-model="search"
            size="small"
            clearable
            placeholder="搜索名称"
            @keyup.enter="loadWorkflows"
            @clear="loadWorkflows"
          />
          <el-button size="small" @click="loadWorkflows">查询</el-button>
        </div>
      </div>
      <el-button size="small" type="primary" class="wf-create" @click="createDraft"
        >新建工作流</el-button
      >
      <el-empty
        v-if="workflows.length === 0 && !loading"
        description="暂无工作流"
        :image-size="70"
      />
      <div v-else v-loading="loading" class="wf-items">
        <button
          v-for="item in workflows"
          :key="item.id"
          type="button"
          class="wf-item"
          :class="{ active: selected?.id === item.id }"
          @click="selectWorkflow(item)"
        >
          <span class="wf-item-main">
            <strong>{{ item.name }}</strong>
            <small>{{ item.description || '暂无简介' }}</small>
          </span>
          <el-tag size="small" :type="statusTag(item.status)">{{ item.status || 'DRAFT' }}</el-tag>
        </button>
      </div>
      <div v-if="selected" class="wf-list-ops">
        <el-button size="small" :disabled="!selected.id" @click="copyWorkflow">复制</el-button>
        <el-button
          v-if="selected.status !== 'ONLINE'"
          size="small"
          type="success"
          :disabled="!selected.id"
          @click="setOnline(true)"
          >上线</el-button
        >
        <el-button
          v-else
          size="small"
          type="warning"
          :disabled="!selected.id"
          @click="setOnline(false)"
          >下线</el-button
        >
        <el-button size="small" type="danger" :disabled="!selected.id" @click="removeWorkflow"
          >删除</el-button
        >
      </div>
    </aside>

    <!-- 中部：画布 -->
    <section class="wf-canvas-wrap panel">
      <div class="wf-toolbar">
        <div class="wf-toolbar-group">
          <el-button size="small" @click="zoomBy(-0.1)">缩小</el-button>
          <span class="wf-zoom-value">{{ Math.round(zoom * 100) }}%</span>
          <el-button size="small" @click="zoomBy(0.1)">放大</el-button>
          <el-button size="small" @click="fitView">适应视图</el-button>
          <el-button size="small" @click="autoLayout">优化布局</el-button>
        </div>
        <div class="wf-toolbar-group">
          <el-button size="small" @click="triggerImport">导入 JSON</el-button>
          <el-button size="small" :disabled="!selected?.id" @click="exportWorkflow"
            >导出 JSON</el-button
          >
          <el-button
            size="small"
            type="primary"
            :disabled="!selected?.id"
            @click="testRunVisible = true"
            >试运行</el-button
          >
          <el-button
            size="small"
            type="primary"
            :loading="saving"
            :disabled="!selected"
            @click="saveWorkflow"
            >{{ selected?.id ? '保存' : '创建' }}</el-button
          >
        </div>
      </div>
      <p v-if="pendingConnection" class="wf-hint">正在连线：点击目标节点完成连接，按 Esc 取消。</p>
      <p v-if="dirty" class="wf-hint warn">有未保存的修改，试运行前请先保存。</p>

      <div class="wf-canvas-body">
        <!-- 节点面板 -->
        <aside class="wf-palette" aria-label="节点面板">
          <el-input
            v-model="paletteSearch"
            size="small"
            clearable
            placeholder="搜索节点类型"
            class="palette-search"
          />
          <div v-for="group in paletteGroups" :key="group.label" class="palette-group">
            <p class="palette-group-title">{{ group.label }}</p>
            <div
              v-for="meta in group.items"
              :key="meta.type"
              class="palette-item"
              draggable="true"
              title="拖入画布或点击添加"
              @dragstart="onPaletteDragStart($event, meta.type)"
              @click="addNode(meta.type)"
            >
              <strong>{{ meta.label }}</strong>
              <small>{{ meta.description }}</small>
            </div>
          </div>
        </aside>

        <!-- 画布区域 -->
        <div
          ref="canvasRef"
          class="wf-canvas"
          tabindex="0"
          @dragover.prevent
          @drop="onCanvasDrop"
          @click.self="clearSelection"
          @keydown.delete="deleteSelection"
          @keydown.esc="cancelConnection"
        >
          <div
            class="wf-canvas-inner"
            :style="{
              transform: 'scale(' + zoom + ')',
              width: canvasWidth + 'px',
              height: canvasHeight + 'px',
            }"
          >
            <svg class="wf-edges" :width="canvasWidth" :height="canvasHeight">
              <defs>
                <marker
                  id="wf-arrow"
                  markerWidth="10"
                  markerHeight="8"
                  refX="9"
                  refY="4"
                  orient="auto"
                >
                  <path d="M0,0 L10,4 L0,8 Z" fill="#94a3b8" />
                </marker>
              </defs>
              <path
                v-for="edge in graph.edges"
                :key="edge.id"
                :d="edgePath(edge)"
                class="wf-edge"
                :class="{ selected: selectedEdgeId === edge.id }"
                marker-end="url(#wf-arrow)"
                @click.stop="selectEdge(edge.id)"
              />
              <path v-if="pendingConnection" :d="pendingPath" class="wf-edge pending" />
            </svg>

            <div
              v-for="node in graph.nodes"
              :key="node.id"
              class="wf-node"
              :class="{
                selected: selectedNodeId === node.id,
                entry: node.type === 'START',
                exit: node.type === 'END',
                invalid: !node.name,
              }"
              :style="{ left: node.position.x + 'px', top: node.position.y + 'px' }"
              @pointerdown="onNodePointerDown($event, node)"
              @click.stop="onNodeClick(node.id)"
            >
              <span class="wf-node-type">{{ nodeTypeLabel(node.type) }}</span>
              <strong class="wf-node-name">{{ node.name || '未命名节点' }}</strong>
              <button
                v-if="node.type !== 'END'"
                type="button"
                class="wf-port out"
                title="点击后连接目标节点"
                @pointerdown.stop
                @click.stop="startConnection(node.id)"
              />
            </div>
          </div>
        </div>
      </div>

      <input
        ref="importInputRef"
        type="file"
        accept=".json,application/json"
        class="hidden-input"
        @change="onImportFile"
      />
    </section>

    <!-- 右侧：属性面板 -->
    <aside class="wf-props panel">
      <template v-if="selectedNode">
        <p class="section-label">Node Properties</p>
        <h4>节点属性</h4>
        <el-form label-position="top" size="small" class="prop-form">
          <el-form-item label="节点名称" required>
            <el-input v-model="selectedNode.name" maxlength="64" @input="markDirty" />
          </el-form-item>
          <el-form-item label="节点类型">
            <el-input :model-value="nodeTypeLabel(selectedNode.type)" disabled />
          </el-form-item>
          <el-form-item label="节点 ID">
            <el-input :model-value="selectedNode.id" disabled />
          </el-form-item>
          <el-form-item label="参数（JSON）">
            <el-input
              :model-value="nodeParametersText"
              type="textarea"
              :rows="8"
              spellcheck="false"
              class="mono"
              @update:model-value="onNodeParametersInput"
            />
          </el-form-item>
          <el-form-item label="错误处理策略">
            <el-select
              v-model="selectedNode.nextOnError"
              clearable
              placeholder="默认停止"
              class="full-width"
              @change="markDirty"
            >
              <el-option label="停止执行（STOP）" value="STOP" />
              <el-option
                v-for="node in otherNodes"
                :key="node.id"
                :label="'出错后跳转到 ' + (node.name || node.id)"
                :value="node.id"
              />
            </el-select>
          </el-form-item>
          <el-button size="small" type="danger" @click="deleteSelection">删除节点</el-button>
        </el-form>
      </template>

      <template v-else-if="selectedEdge">
        <p class="section-label">Edge Properties</p>
        <h4>连线属性</h4>
        <el-form label-position="top" size="small" class="prop-form">
          <el-form-item label="连线 ID">
            <el-input :model-value="selectedEdge.id" disabled />
          </el-form-item>
          <el-form-item label="来源 → 目标">
            <el-input :model-value="edgeEndpointText(selectedEdge)" disabled />
          </el-form-item>
          <el-form-item label="条件表达式（可选）">
            <el-input
              v-model="selectedEdge.condition"
              type="textarea"
              :rows="4"
              placeholder="例如：output.score > 0.7"
              @input="markDirty"
            />
          </el-form-item>
          <el-button size="small" type="danger" @click="deleteSelection">删除连线</el-button>
        </el-form>
      </template>

      <template v-else>
        <p class="section-label">Workflow Properties</p>
        <h4>工作流属性</h4>
        <el-form label-position="top" size="small" class="prop-form">
          <el-form-item label="名称" required>
            <el-input v-model="formName" maxlength="128" @input="markDirty" />
          </el-form-item>
          <el-form-item label="简介">
            <el-input
              v-model="formDescription"
              type="textarea"
              :rows="2"
              maxlength="512"
              @input="markDirty"
            />
          </el-form-item>
          <el-form-item label="执行前确认">
            <el-switch v-model="graph.executionConfirm" @change="markDirty" />
          </el-form-item>
          <el-form-item label="输入 Schema（JSON）">
            <el-input
              :model-value="schemaText('inputSchema')"
              type="textarea"
              :rows="6"
              spellcheck="false"
              class="mono"
              @update:model-value="(value: string) => onSchemaInput('inputSchema', value)"
            />
          </el-form-item>
          <el-form-item label="输出 Schema（JSON）">
            <el-input
              :model-value="schemaText('outputSchema')"
              type="textarea"
              :rows="6"
              spellcheck="false"
              class="mono"
              @update:model-value="(value: string) => onSchemaInput('outputSchema', value)"
            />
          </el-form-item>
          <el-form-item label="变量（JSON）">
            <el-input
              :model-value="schemaText('variables')"
              type="textarea"
              :rows="5"
              spellcheck="false"
              class="mono"
              @update:model-value="(value: string) => onSchemaInput('variables', value)"
            />
          </el-form-item>
          <div class="wf-stat">
            <span>节点 {{ graph.nodes.length }} 个</span>
            <span>连线 {{ graph.edges.length }} 条</span>
          </div>
        </el-form>
      </template>
    </aside>

    <!-- 试运行对话框 -->
    <el-dialog v-model="testRunVisible" title="工作流试运行" width="720px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="输入参数（JSON，需符合输入 Schema）" required>
          <el-input
            v-model="testRunInput"
            type="textarea"
            :rows="6"
            spellcheck="false"
            class="mono"
          />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="testRunError"
        type="error"
        show-icon
        :closable="false"
        :title="testRunError"
        class="run-error"
      />
      <template v-if="testRunResult">
        <el-descriptions :column="2" border size="small" class="run-summary">
          <el-descriptions-item label="状态">{{
            testRunResult.status ?? '-'
          }}</el-descriptions-item>
          <el-descriptions-item label="最终输出">
            <pre class="run-output">{{ formatJson(testRunResult.output) }}</pre>
          </el-descriptions-item>
        </el-descriptions>
        <el-table
          v-if="testRunResult.trace?.length"
          :data="testRunResult.trace"
          size="small"
          max-height="260"
          class="run-trace"
        >
          <el-table-column prop="nodeName" label="节点" min-width="140" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="durationMs" label="耗时(ms)" width="90" />
          <el-table-column
            prop="outputSummary"
            label="输出摘要"
            min-width="180"
            show-overflow-tooltip
          />
          <el-table-column prop="error" label="错误" min-width="140" show-overflow-tooltip />
        </el-table>
      </template>
      <template #footer>
        <el-button @click="testRunVisible = false">关闭</el-button>
        <el-button
          type="primary"
          :loading="testRunLoading"
          :disabled="!selected?.id"
          @click="runTest"
          >运行</el-button
        >
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  copyPlatformWorkflow,
  createPlatformAsset,
  deletePlatformAsset,
  exportPlatformWorkflow,
  importPlatformWorkflow,
  listPlatformAssets,
  offlinePlatformWorkflow,
  onlinePlatformWorkflow,
  testRunPlatformWorkflow,
  updatePlatformAsset,
} from '../../api/platform';
import type {
  PlatformAsset,
  WorkflowEdge,
  WorkflowGraphConfig,
  WorkflowNode,
  WorkflowTestRunResult,
} from '../../types/platform';
import { authContext, isAdmin } from '../../composables/useAuthState';

interface NodeTypeMeta {
  type: string;
  label: string;
  description: string;
  group: string;
}

const NODE_TYPES: NodeTypeMeta[] = [
  { type: 'START', label: '开始', description: '流程入口，接收输入参数', group: '基础' },
  { type: 'END', label: '结束', description: '流程出口，输出最终结果', group: '基础' },
  { type: 'LLM', label: 'LLM 调用', description: '调用模型服务生成内容', group: '模型' },
  {
    type: 'KNOWLEDGE_RETRIEVAL',
    label: '知识召回',
    description: '在绑定知识库内检索',
    group: '模型',
  },
  { type: 'CONDITION', label: '条件分支', description: '按条件表达式路由', group: '逻辑' },
  { type: 'TOOL_CALL', label: '工具调用', description: '执行已绑定的平台工具', group: '执行' },
  { type: 'WORKFLOW_CALL', label: '子工作流', description: '调用另一个工作流', group: '执行' },
  { type: 'HTTP_REQUEST', label: 'HTTP 请求', description: '按配置发起外部请求', group: '执行' },
  { type: 'CODE', label: '代码执行', description: '服务端沙箱内运行代码', group: '执行' },
];

const NODE_WIDTH = 176;
const NODE_HEIGHT = 58;
const CANVAS_WIDTH = 2600;
const CANVAS_HEIGHT = 1800;

const emptyGraph = (): WorkflowGraphConfig => ({
  version: '1.0',
  nodes: [{ id: 'start', type: 'START', name: '开始', position: { x: 80, y: 100 } }],
  edges: [],
  variables: {},
  inputSchema: { type: 'object', properties: {} },
  outputSchema: { type: 'object', properties: {} },
  executionConfirm: false,
});

const workflows = ref<PlatformAsset[]>([]);
const loading = ref(false);
const search = ref('');
const selected = ref<PlatformAsset | null>(null);
const graph = ref<WorkflowGraphConfig>(emptyGraph());
const formName = ref('');
const formDescription = ref('');
const dirty = ref(false);
const saving = ref(false);

const selectedNodeId = ref<string | null>(null);
const selectedEdgeId = ref<string | null>(null);
const pendingConnection = ref<string | null>(null);
const pendingPointer = ref({ x: 0, y: 0 });
const zoom = ref(1);
const paletteSearch = ref('');
const canvasRef = ref<HTMLElement | null>(null);
const importInputRef = ref<HTMLInputElement | null>(null);
const canvasWidth = CANVAS_WIDTH;
const canvasHeight = CANVAS_HEIGHT;

const testRunVisible = ref(false);
const testRunInput = ref('{}');
const testRunLoading = ref(false);
const testRunResult = ref<WorkflowTestRunResult | null>(null);
const testRunError = ref('');

let nodeDragState: {
  nodeId: string;
  startX: number;
  startY: number;
  originX: number;
  originY: number;
} | null = null;

const paletteGroups = computed(() => {
  const keyword = paletteSearch.value.trim().toLowerCase();
  const groups = new Map<string, NodeTypeMeta[]>();
  NODE_TYPES.filter(
    (meta) =>
      !keyword ||
      meta.label.toLowerCase().includes(keyword) ||
      meta.type.toLowerCase().includes(keyword),
  ).forEach((meta) => {
    const list = groups.get(meta.group) ?? [];
    list.push(meta);
    groups.set(meta.group, list);
  });
  return [...groups.entries()].map(([label, items]) => ({ label, items }));
});

const selectedNode = computed(
  () => graph.value.nodes.find((node) => node.id === selectedNodeId.value) ?? null,
);
const selectedEdge = computed(
  () => graph.value.edges.find((edge) => edge.id === selectedEdgeId.value) ?? null,
);
const otherNodes = computed(() =>
  graph.value.nodes.filter((node) => node.id !== selectedNodeId.value),
);

const nodeParametersText = computed(() => formatJson(selectedNode.value?.parameters ?? {}));
const pendingPath = computed(() => {
  const source = graph.value.nodes.find((node) => node.id === pendingConnection.value);
  if (!source) return '';
  return (
    'M ' +
    (source.position.x + NODE_WIDTH) +
    ' ' +
    (source.position.y + NODE_HEIGHT / 2) +
    ' L ' +
    pendingPointer.value.x +
    ' ' +
    pendingPointer.value.y
  );
});

function nodeTypeLabel(type: string): string {
  return NODE_TYPES.find((meta) => meta.type === type)?.label ?? type;
}

function statusTag(status?: string | null): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'ONLINE' || status === 'PUBLISHED' || status === 'ENABLED') return 'success';
  if (status === 'DRAFT' || status === 'OFFLINE' || status === 'PENDING') return 'warning';
  if (status === 'FAILED') return 'danger';
  return 'info';
}

function formatJson(value: unknown): string {
  try {
    return JSON.stringify(value ?? {}, null, 2);
  } catch {
    return String(value);
  }
}

function markDirty(): void {
  dirty.value = true;
}

function parseGraphConfig(asset: PlatformAsset): WorkflowGraphConfig {
  try {
    const parsed = JSON.parse(asset.configJson || '{}') as Record<string, unknown>;
    const inner = (parsed.graph ?? parsed) as Partial<WorkflowGraphConfig>;
    return {
      version: typeof inner.version === 'string' ? inner.version : '1.0',
      nodes: Array.isArray(inner.nodes) ? inner.nodes : emptyGraph().nodes,
      edges: Array.isArray(inner.edges) ? inner.edges : [],
      variables: (inner.variables as Record<string, unknown>) ?? {},
      inputSchema: (inner.inputSchema as Record<string, unknown>) ?? {},
      outputSchema: (inner.outputSchema as Record<string, unknown>) ?? {},
      executionConfirm: Boolean(inner.executionConfirm),
      layout: (inner.layout as Record<string, unknown>) ?? {},
    };
  } catch {
    ElMessage.warning('工作流配置 JSON 不合法，已重置为空画布');
    return emptyGraph();
  }
}

async function loadWorkflows(): Promise<void> {
  if (!isAdmin.value) return;
  loading.value = true;
  try {
    const page = await listPlatformAssets(
      'workflows',
      authContext(),
      1,
      50,
      search.value.trim() || undefined,
    );
    workflows.value = page.items;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工作流列表加载失败');
  } finally {
    loading.value = false;
  }
}

function selectWorkflow(asset: PlatformAsset): void {
  selected.value = asset;
  graph.value = parseGraphConfig(asset);
  formName.value = asset.name;
  formDescription.value = asset.description ?? '';
  dirty.value = false;
  clearSelection();
  void nextTick(fitView);
}

function createDraft(): void {
  selected.value = null;
  graph.value = emptyGraph();
  formName.value = '未命名工作流';
  formDescription.value = '';
  dirty.value = true;
  clearSelection();
}

function clearSelection(): void {
  selectedNodeId.value = null;
  selectedEdgeId.value = null;
}

function selectNode(id: string): void {
  selectedNodeId.value = id;
  selectedEdgeId.value = null;
}

function onNodeClick(id: string): void {
  if (pendingConnection.value) {
    completeConnection(id);
    return;
  }
  selectNode(id);
}

function selectEdge(id: string): void {
  selectedEdgeId.value = id;
  selectedNodeId.value = null;
}

function addNode(type: string, position?: { x: number; y: number }): void {
  const id = 'node_' + Date.now().toString(36) + '_' + Math.floor(Math.random() * 1000);
  graph.value.nodes.push({
    id,
    type,
    name: nodeTypeLabel(type),
    position: position ?? { x: 160 + Math.random() * 300, y: 120 + Math.random() * 200 },
    parameters: {},
    nextOnError: null,
  });
  selectNode(id);
  markDirty();
}

function onPaletteDragStart(event: DragEvent, type: string): void {
  event.dataTransfer?.setData('application/x-workflow-node', type);
}

function canvasDropPosition(
  clientX: number,
  clientY: number,
): { x: number; y: number } | undefined {
  const canvas = canvasRef.value;
  if (!canvas) return undefined;
  const rect = canvas.getBoundingClientRect();
  const x = (clientX - rect.left + canvas.scrollLeft) / zoom.value;
  const y = (clientY - rect.top + canvas.scrollTop) / zoom.value;
  if (x < 0 || y < 0 || x > CANVAS_WIDTH || y > CANVAS_HEIGHT) return undefined;
  return { x: Math.round(x), y: Math.round(y) };
}

function onCanvasDrop(event: DragEvent): void {
  const type = event.dataTransfer?.getData('application/x-workflow-node');
  if (!type) return;
  const position = canvasDropPosition(event.clientX, event.clientY);
  if (position) addNode(type, position);
}

function onNodePointerDown(event: PointerEvent, node: WorkflowNode): void {
  if ((event.target as HTMLElement).closest('.wf-port')) return;
  nodeDragState = {
    nodeId: node.id,
    startX: event.clientX,
    startY: event.clientY,
    originX: node.position.x,
    originY: node.position.y,
  };
  window.addEventListener('pointermove', onPointerMove);
  window.addEventListener('pointerup', onPointerUp);
}

function onPointerMove(event: PointerEvent): void {
  if (pendingConnection.value) {
    const position = canvasDropPosition(event.clientX, event.clientY);
    if (position) pendingPointer.value = position;
  }
  if (!nodeDragState) return;
  const node = graph.value.nodes.find((item) => item.id === nodeDragState?.nodeId);
  if (!node) return;
  node.position.x = Math.max(
    0,
    Math.round(nodeDragState.originX + (event.clientX - nodeDragState.startX) / zoom.value),
  );
  node.position.y = Math.max(
    0,
    Math.round(nodeDragState.originY + (event.clientY - nodeDragState.startY) / zoom.value),
  );
}

function onPointerUp(): void {
  nodeDragState = null;
  window.removeEventListener('pointermove', onPointerMove);
  window.removeEventListener('pointerup', onPointerUp);
}

function startConnection(sourceId: string): void {
  pendingConnection.value = sourceId;
  const source = graph.value.nodes.find((node) => node.id === sourceId);
  if (source)
    pendingPointer.value = {
      x: source.position.x + NODE_WIDTH,
      y: source.position.y + NODE_HEIGHT / 2,
    };
}

function cancelConnection(): void {
  pendingConnection.value = null;
}

function completeConnection(targetId: string): void {
  const sourceId = pendingConnection.value;
  if (!sourceId || sourceId === targetId) return;
  const target = graph.value.nodes.find((node) => node.id === targetId);
  if (!target || target.type === 'START') {
    ElMessage.warning('不允许连接到开始节点');
    return;
  }
  const source = graph.value.nodes.find((node) => node.id === sourceId);
  if (source?.type === 'END') {
    ElMessage.warning('结束节点不能发起连线');
    return;
  }
  if (graph.value.edges.some((edge) => edge.source === sourceId && edge.target === targetId)) {
    ElMessage.warning('两个节点之间已存在连线');
    return;
  }
  graph.value.edges.push({
    id: 'edge_' + Date.now().toString(36),
    source: sourceId,
    target: targetId,
    condition: null,
  });
  markDirty();
  cancelConnection();
}

function deleteSelection(): void {
  if (selectedNodeId.value) {
    graph.value.nodes = graph.value.nodes.filter((node) => node.id !== selectedNodeId.value);
    graph.value.edges = graph.value.edges.filter(
      (edge) => edge.source !== selectedNodeId.value && edge.target !== selectedNodeId.value,
    );
    selectedNodeId.value = null;
    markDirty();
    return;
  }
  if (selectedEdgeId.value) {
    graph.value.edges = graph.value.edges.filter((edge) => edge.id !== selectedEdgeId.value);
    selectedEdgeId.value = null;
    markDirty();
  }
}

function edgePath(edge: WorkflowEdge): string {
  const source = graph.value.nodes.find((node) => node.id === edge.source);
  const target = graph.value.nodes.find((node) => node.id === edge.target);
  if (!source || !target) return '';
  const x1 = source.position.x + NODE_WIDTH;
  const y1 = source.position.y + NODE_HEIGHT / 2;
  const x2 = target.position.x;
  const y2 = target.position.y + NODE_HEIGHT / 2;
  const dx = Math.max(40, Math.abs(x2 - x1) / 2);
  return (
    'M ' +
    x1 +
    ' ' +
    y1 +
    ' C ' +
    (x1 + dx) +
    ' ' +
    y1 +
    ', ' +
    (x2 - dx) +
    ' ' +
    y2 +
    ', ' +
    x2 +
    ' ' +
    y2
  );
}

function edgeEndpointText(edge: WorkflowEdge): string {
  const source = graph.value.nodes.find((node) => node.id === edge.source);
  const target = graph.value.nodes.find((node) => node.id === edge.target);
  return (source?.name ?? edge.source) + ' → ' + (target?.name ?? edge.target);
}

function onNodeParametersInput(value: string): void {
  const node = selectedNode.value;
  if (!node) return;
  try {
    node.parameters = value.trim() ? (JSON.parse(value) as Record<string, unknown>) : {};
    markDirty();
  } catch {
    node.parameters = { __raw: value };
  }
}

function schemaText(key: 'inputSchema' | 'outputSchema' | 'variables'): string {
  return formatJson(graph.value[key]);
}

function onSchemaInput(key: 'inputSchema' | 'outputSchema' | 'variables', value: string): void {
  try {
    graph.value[key] = value.trim() ? (JSON.parse(value) as Record<string, unknown>) : {};
    markDirty();
  } catch {
    // 输入过程中允许暂时不合法，保存时统一校验
  }
}

function validateGraph(): string | null {
  if (!formName.value.trim()) return '工作流名称不能为空';
  const ids = new Set<string>();
  for (const node of graph.value.nodes) {
    if (!node.id) return '存在缺少 ID 的节点';
    if (ids.has(node.id)) return '节点 ID 重复：' + node.id;
    ids.add(node.id);
    if (!node.name.trim()) return '节点「' + node.id + '」名称不能为空';
    if (node.parameters && typeof node.parameters.__raw === 'string')
      return '节点「' + node.name + '」参数 JSON 不合法';
  }
  if (!graph.value.nodes.some((node) => node.type === 'START')) return '缺少开始（START）节点';
  if (!graph.value.nodes.some((node) => node.type === 'END')) return '缺少结束（END）节点';
  for (const edge of graph.value.edges) {
    if (!ids.has(edge.source) || !ids.has(edge.target))
      return '连线「' + edge.id + '」引用了不存在的节点';
  }
  return null;
}

async function saveWorkflow(): Promise<void> {
  const error = validateGraph();
  if (error) {
    ElMessage.warning(error);
    return;
  }
  saving.value = true;
  try {
    const body = {
      name: formName.value.trim(),
      description: formDescription.value.trim() || undefined,
      configJson: JSON.stringify(graph.value),
    };
    if (selected.value?.id) {
      await updatePlatformAsset('workflows', selected.value.id, body, authContext());
    } else {
      const created = await createPlatformAsset('workflows', body, authContext());
      selected.value = created;
    }
    ElMessage.success('工作流已保存');
    dirty.value = false;
    await loadWorkflows();
    const current = workflows.value.find((item) => item.id === selected.value?.id);
    if (current && selected.value) selected.value = current;
  } catch (requestError) {
    ElMessage.error(requestError instanceof Error ? requestError.message : '工作流保存失败');
  } finally {
    saving.value = false;
  }
}

function zoomBy(delta: number): void {
  zoom.value = Math.min(1.6, Math.max(0.4, Math.round((zoom.value + delta) * 10) / 10));
}

function fitView(): void {
  const canvas = canvasRef.value;
  if (!canvas || graph.value.nodes.length === 0) return;
  const xs = graph.value.nodes.map((node) => node.position.x);
  const ys = graph.value.nodes.map((node) => node.position.y);
  const minX = Math.min(...xs);
  const minY = Math.min(...ys);
  const maxX = Math.max(...xs.map((x) => x + NODE_WIDTH));
  const maxY = Math.max(...ys.map((y) => y + NODE_HEIGHT));
  const scale = Math.min(
    1,
    Math.min(canvas.clientWidth / (maxX - minX + 120), canvas.clientHeight / (maxY - minY + 120)),
  );
  zoom.value = Math.max(0.4, Math.round(scale * 10) / 10);
  canvas.scrollTo({
    left: Math.max(0, minX - 80),
    top: Math.max(0, minY - 60),
    behavior: 'smooth',
  });
}

function autoLayout(): void {
  const columns = Math.ceil(Math.sqrt(graph.value.nodes.length)) || 1;
  graph.value.nodes.forEach((node, index) => {
    node.position.x = 100 + (index % columns) * 260;
    node.position.y = 100 + Math.floor(index / columns) * 140;
  });
  markDirty();
  void nextTick(fitView);
}

function triggerImport(): void {
  importInputRef.value?.click();
}

async function onImportFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  try {
    const text = await file.text();
    const parsed = JSON.parse(text) as Record<string, unknown>;
    if (typeof parsed.version !== 'string')
      throw new Error('缺少 version 字段，不是合法的工作流 JSON');
    if (!Array.isArray(parsed.nodes) || !Array.isArray(parsed.edges))
      throw new Error('缺少 nodes / edges 字段');
    const summary =
      '节点 ' +
      parsed.nodes.length +
      ' 个，连线 ' +
      parsed.edges.length +
      ' 条，版本 ' +
      parsed.version;
    try {
      await ElMessageBox.confirm('导入摘要：' + summary + '。将创建为新工作流。', '导入工作流', {
        confirmButtonText: '导入',
        cancelButtonText: '取消',
      });
    } catch {
      return;
    }
    const name = file.name.replace(/\.json$/i, '') || '导入的工作流';
    try {
      await importPlatformWorkflow(
        { name, description: '导入自 ' + file.name, configJson: text },
        authContext(),
      );
      ElMessage.success('工作流导入成功');
    } catch (importError) {
      // 后端导入接口未就绪时退回通用创建接口，配置内容仍然真实保存
      if (!(importError instanceof Error) || !importError.message.includes('404'))
        throw importError;
      await createPlatformAsset(
        'workflows',
        { name, description: '导入自 ' + file.name, configJson: text },
        authContext(),
      );
      ElMessage.success('工作流已通过通用接口导入');
    }
    await loadWorkflows();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工作流导入失败');
  }
}

async function exportWorkflow(): Promise<void> {
  if (!selected.value?.id) return;
  try {
    let config: WorkflowGraphConfig;
    try {
      config = await exportPlatformWorkflow(selected.value.id, authContext());
    } catch (exportError) {
      if (!(exportError instanceof Error) || !exportError.message.includes('404'))
        throw exportError;
      config = graph.value;
    }
    const blob = new Blob([JSON.stringify(config, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = (selected.value.name || 'workflow') + '.json';
    link.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工作流导出失败');
  }
}

async function runTest(): Promise<void> {
  if (!selected.value?.id) return;
  let input: Record<string, unknown>;
  try {
    input = testRunInput.value.trim()
      ? (JSON.parse(testRunInput.value) as Record<string, unknown>)
      : {};
  } catch {
    ElMessage.warning('输入参数 JSON 不合法');
    return;
  }
  testRunLoading.value = true;
  testRunError.value = '';
  testRunResult.value = null;
  try {
    testRunResult.value = await testRunPlatformWorkflow(selected.value.id, input, authContext());
  } catch (error) {
    testRunError.value = error instanceof Error ? error.message : '试运行失败';
  } finally {
    testRunLoading.value = false;
  }
}

async function copyWorkflow(): Promise<void> {
  if (!selected.value?.id) return;
  try {
    const { value } = await ElMessageBox.prompt('请输入新工作流名称', '复制工作流', {
      inputValue: selected.value.name + ' - 副本',
      confirmButtonText: '复制',
      cancelButtonText: '取消',
      inputValidator: (input) => Boolean(input?.trim()) || '名称不能为空',
    });
    await copyPlatformWorkflow(selected.value.id, value.trim(), authContext());
    ElMessage.success('复制完成');
    await loadWorkflows();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '复制失败');
    }
  }
}

async function setOnline(online: boolean): Promise<void> {
  if (!selected.value?.id) return;
  const action = online ? '上线' : '下线';
  try {
    await ElMessageBox.confirm(
      online
        ? '上线前请确认画布完整并已试运行通过，智能体将只能调用已上线工作流。'
        : '下线后智能体不能再发起新调用，确定继续？',
      action + '工作流',
      { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    if (online) await onlinePlatformWorkflow(selected.value.id, authContext());
    else await offlinePlatformWorkflow(selected.value.id, authContext());
    ElMessage.success('已' + action);
    await loadWorkflows();
    const current = workflows.value.find((item) => item.id === selected.value?.id);
    if (current) selected.value = current;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : action + '失败');
  }
}

async function removeWorkflow(): Promise<void> {
  if (!selected.value?.id) return;
  try {
    await ElMessageBox.confirm(
      '确定删除「' + selected.value.name + '」？该操作不可恢复。',
      '删除工作流',
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
    await deletePlatformAsset('workflows', selected.value.id, authContext());
    ElMessage.success('已删除');
    selected.value = null;
    createDraft();
    await loadWorkflows();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败');
  }
}

onMounted(() => {
  void loadWorkflows();
});

onBeforeUnmount(() => {
  onPointerUp();
});
</script>

<style scoped>
.workflow-studio {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr) 300px;
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
.wf-create {
  width: 100%;
}

.wf-items {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.wf-item {
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
.wf-item.active,
.wf-item:focus-visible {
  border-color: var(--ui-accent);
  background: color-mix(in oklab, var(--ui-accent) 8%, transparent);
}
.wf-item-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.wf-item-main strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wf-item small {
  color: var(--ui-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wf-list-ops {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px;
  border-top: 1px solid var(--ui-border);
  padding-top: 10px;
}

.wf-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}
.wf-toolbar-group {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.wf-zoom-value {
  min-width: 44px;
  text-align: center;
  font-size: 12px;
  color: var(--ui-muted);
}
.wf-hint {
  margin: 0;
  font-size: 12px;
  color: var(--ui-muted);
}
.wf-hint.warn {
  color: var(--el-color-warning);
}

.wf-canvas-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 172px minmax(0, 1fr);
  gap: 10px;
}

.wf-palette {
  min-height: 0;
  overflow-y: auto;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  padding: 8px;
  background: color-mix(in oklab, var(--ui-panel) 70%, transparent);
}
.palette-search {
  margin-bottom: 8px;
}
.palette-group-title {
  margin: 8px 4px 4px;
  color: var(--ui-muted);
  font-size: 11px;
  letter-spacing: 0.06em;
}
.palette-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  margin-bottom: 6px;
  padding: 7px 8px;
  border: 1px dashed var(--ui-border);
  border-radius: 7px;
  background: transparent;
  cursor: grab;
  text-align: left;
}
.palette-item:hover {
  border-color: var(--ui-accent);
}
.palette-item strong {
  font-size: 12px;
  color: var(--ui-text);
}
.palette-item small {
  font-size: 11px;
  color: var(--ui-muted);
  line-height: 1.4;
}

.wf-canvas {
  position: relative;
  min-height: 0;
  overflow: auto;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  background:
    linear-gradient(90deg, rgba(148, 163, 184, 0.12) 1px, transparent 1px),
    linear-gradient(rgba(148, 163, 184, 0.12) 1px, transparent 1px);
  background-size: 24px 24px;
  outline: none;
}
.wf-canvas:focus-visible {
  border-color: var(--ui-accent);
}
.wf-canvas-inner {
  position: relative;
  transform-origin: 0 0;
}

.wf-edges {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.wf-edge {
  fill: none;
  stroke: #94a3b8;
  stroke-width: 2;
  pointer-events: stroke;
  cursor: pointer;
}
.wf-edge:hover,
.wf-edge.selected {
  stroke: var(--ui-accent);
  stroke-width: 3;
}
.wf-edge.pending {
  stroke-dasharray: 6 4;
}

.wf-node {
  position: absolute;
  width: 176px;
  height: 58px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 2px;
  padding: 6px 12px;
  border: 1.5px solid var(--ui-border);
  border-radius: 9px;
  background: var(--ui-card);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.08);
  cursor: grab;
  user-select: none;
}
.wf-node.entry {
  border-color: var(--el-color-success);
}
.wf-node.exit {
  border-color: var(--el-color-danger);
}
.wf-node.selected,
.wf-node:focus-visible {
  border-color: var(--ui-accent);
  box-shadow: 0 0 0 3px color-mix(in oklab, var(--ui-accent) 18%, transparent);
}
.wf-node.invalid {
  border-color: var(--el-color-danger);
}
.wf-node-type {
  font-size: 10px;
  color: var(--ui-muted);
  letter-spacing: 0.04em;
}
.wf-node-name {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wf-port.out {
  position: absolute;
  right: -7px;
  top: 50%;
  transform: translateY(-50%);
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid var(--ui-accent);
  background: var(--ui-card);
  cursor: crosshair;
  padding: 0;
}

.wf-props h4 {
  margin: 2px 0 10px;
  font-size: 15px;
}
.prop-form {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.wf-stat {
  display: flex;
  gap: 12px;
  color: var(--ui-muted);
  font-size: 12px;
}

.hidden-input {
  display: none;
}
.full-width {
  width: 100%;
}
.mono :deep(textarea) {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}
.run-error {
  margin-bottom: 10px;
}
.run-output {
  margin: 0;
  max-height: 180px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
}
.run-trace {
  margin-top: 10px;
}

@media (max-width: 1250px) {
  .workflow-studio {
    grid-template-columns: 220px minmax(0, 1fr);
  }
  .wf-props {
    display: none;
  }
}

@media (max-width: 900px) {
  .workflow-studio {
    grid-template-columns: minmax(0, 1fr);
    overflow-y: auto;
  }
  .wf-canvas-body {
    grid-template-columns: minmax(0, 1fr);
  }
  .wf-palette {
    display: flex;
    gap: 6px;
    overflow-x: auto;
  }
  .palette-group {
    flex-shrink: 0;
    min-width: 130px;
  }
}
</style>
