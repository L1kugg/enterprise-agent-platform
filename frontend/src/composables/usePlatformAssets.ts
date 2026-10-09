// 平台基础功能资源页状态：第 5 章配置对象统一走后端 platform_asset API。
import { computed, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';

import {
  copyPlatformAgent,
  createPlatformAsset,
  deletePlatformAsset,
  listPlatformAssets,
  publishPlatformAgent,
  testPlatformModel,
  updatePlatformAsset,
} from '../api/platform';
import type { PlatformAsset, PlatformAssetType } from '../types/platform';
import { authContext, isAdmin } from './useAuthState';
import { activeView } from './useGlobalUi';

export interface PlatformResourceMeta {
  type: PlatformAssetType;
  label: string;
  description: string;
}

export const platformResources: readonly PlatformResourceMeta[] = [
  { type: 'agents', label: '智能体', description: '提示词、模型、开场引导与资源绑定' },
  { type: 'workflows', label: '工作流', description: '画布节点、连线和执行确认策略' },
  { type: 'tools', label: '工具', description: '表单式 API、代码式 API 与 SQL 工具' },
  { type: 'knowledge-bases', label: '知识库', description: '知识库基础信息与对接 API' },
  { type: 'knowledge-files', label: '知识文件', description: '文件切分策略与解析状态' },
  { type: 'safety-guards', label: '安全防护', description: '过滤主题与阻止消息' },
  { type: 'model-services', label: '模型服务', description: '模型提供商、地址与参数' },
  { type: 'databases', label: '数据库', description: '第三方数据库连接配置' },
];

export type PlatformSection =
  | 'overview'
  | 'agents'
  | 'workflows'
  | 'tools'
  | 'knowledge'
  | 'safety-guards'
  | 'model-services'
  | 'databases';

export const platformSection = ref<PlatformSection>('overview');
export const platformOverview = ref<Record<string, number>>({});
export const platformOverviewLoading = ref(false);

const sectionAssetTypes: Partial<Record<PlatformSection, PlatformAssetType>> = {
  agents: 'agents',
  workflows: 'workflows',
  tools: 'tools',
  'safety-guards': 'safety-guards',
  'model-services': 'model-services',
  databases: 'databases',
};
export const activePlatformType = ref<PlatformAssetType>('agents');
export const platformAssets = ref<PlatformAsset[]>([]);
export const platformLoading = ref(false);
export const platformTotal = ref(0);
export const platformPage = ref(1);
export const platformPageSize = ref(20);
export const platformSearch = ref('');
export const platformNeedsAuth = ref(false);

export const dialogVisible = ref(false);
export const dialogSaving = ref(false);
export const editingAsset = ref<PlatformAsset | null>(null);
export const assetName = ref('');
export const assetDescription = ref('');
export const assetStatus = ref('');
export const assetConfigJson = ref('{}');
export const assetParentId = ref<number | null>(null);
export const knowledgeBaseOptions = ref<PlatformAsset[]>([]);

export const activeResource = computed(
  () => platformResources.find(item => item.type === activePlatformType.value) ?? platformResources[0],
);

function configTemplate(type: PlatformAssetType): string {
  const templates: Record<PlatformAssetType, unknown> = {
    agents: {
      prompt: '你是一个企业知识助手',
      modelId: null,
      modelParams: { temperature: 0.7 },
      openingMessage: '你好，我可以帮你处理企业知识任务。',
      openingQuestions: [],
      workflowIds: [],
      tools: [],
      knowledgeBaseIds: [],
      safetyGuardIds: [],
      memoryTurns: 10,
    },
    workflows: { graph: { nodes: [], edges: [] }, executionConfirm: true },
    tools: { toolType: 'FORM_API', schema: {}, executionConfirm: true },
    'knowledge-bases': { listApiUrl: '', queryApiUrl: '', recallApiUrl: '' },
    'knowledge-files': { chunkStrategy: 'SMART', delimiter: '\\n\\n', chunkSize: 800, chunkOverlap: 80, chunkPreview: [] },
    'safety-guards': { blockedTopics: [], blockMessage: '该主题不允许讨论。' },
    'model-services': { category: 'LLM', provider: '', apiUrl: '', apiKey: '', modelParams: {} },
    databases: { databaseType: 'MYSQL', jdbcUrl: '', username: '', password: '' },
  };
  return `${JSON.stringify(templates[type], null, 2)}\n`;
}

function parseConfigOrThrow(): unknown {
  try {
    return JSON.parse(assetConfigJson.value || '{}');
  } catch (error) {
    throw new Error(`配置 JSON 不合法：${error instanceof Error ? error.message : String(error)}`);
  }
}

export async function loadPlatformAssets(silent = false): Promise<void> {
  if (!isAdmin.value) return;
  platformNeedsAuth.value = false;
  if (!silent) platformLoading.value = true;
  try {
    const result = await listPlatformAssets(
      activePlatformType.value,
      authContext(),
      platformPage.value,
      platformPageSize.value,
      platformSearch.value.trim() || undefined,
    );
    platformAssets.value = result.items;
    platformTotal.value = result.total;
    if (activePlatformType.value === 'knowledge-bases') knowledgeBaseOptions.value = result.items;
  } catch (error) {
    const message = error instanceof Error ? error.message : '资源列表加载失败';
    if (message.includes('401') || message.includes('未认证')) {
      platformNeedsAuth.value = true;
      platformAssets.value = [];
    } else if (!silent) ElMessage.error(message);
  } finally {
    if (!silent) platformLoading.value = false;
  }
}

async function loadKnowledgeBaseOptions(): Promise<void> {
  if (knowledgeBaseOptions.value.length > 0 || activePlatformType.value !== 'knowledge-files') return;
  try {
    const result = await listPlatformAssets('knowledge-bases', authContext(), 1, 100);
    knowledgeBaseOptions.value = result.items;
  } catch {
    knowledgeBaseOptions.value = [];
  }
}

export function switchPlatformType(type: PlatformAssetType): void {
  if (activePlatformType.value === type) return;
  activePlatformType.value = type;
  platformPage.value = 1;
  platformSearch.value = '';
  void loadPlatformAssets();
}

export function handlePlatformSearch(): void {
  platformPage.value = 1;
  void loadPlatformAssets();
}

export function handlePlatformPageChange(page: number): void {
  platformPage.value = page;
  void loadPlatformAssets();
}

export function openCreateDialog(): void {
  editingAsset.value = null;
  assetName.value = activeResource.value.type === 'agents' ? '新建智能体' : `新建${activeResource.value.label}`;
  assetDescription.value = '';
  assetStatus.value = '';
  assetConfigJson.value = configTemplate(activeResource.value.type);
  assetParentId.value = null;
  dialogVisible.value = true;
  void loadKnowledgeBaseOptions();
}

export function openEditDialog(row: PlatformAsset): void {
  editingAsset.value = row;
  assetName.value = row.name;
  assetDescription.value = row.description ?? '';
  assetStatus.value = row.status ?? '';
  assetConfigJson.value = row.configJson ?? '{}';
  assetParentId.value = row.parentId ?? null;
  dialogVisible.value = true;
  void loadKnowledgeBaseOptions();
}

export async function savePlatformAsset(): Promise<void> {
  if (!assetName.value.trim()) {
    ElMessage.warning('名称不能为空');
    return;
  }
  if (activePlatformType.value === 'knowledge-files' && !assetParentId.value) {
    ElMessage.warning('请选择所属知识库');
    return;
  }
  try {
    parseConfigOrThrow();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '配置 JSON 不合法');
    return;
  }
  dialogSaving.value = true;
  try {
    const body = {
      name: assetName.value.trim(),
      description: assetDescription.value.trim() || undefined,
      configJson: JSON.stringify(parseConfigOrThrow()),
      parentId: assetParentId.value,
      status: assetStatus.value.trim() || undefined,
    };
    if (editingAsset.value) {
      await updatePlatformAsset(activePlatformType.value, editingAsset.value.id, body, authContext());
      ElMessage.success('已保存');
    } else {
      await createPlatformAsset(activePlatformType.value, body, authContext());
      ElMessage.success('已创建');
    }
    dialogVisible.value = false;
    await loadPlatformAssets(true);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    dialogSaving.value = false;
  }
}

export async function removePlatformAsset(row: PlatformAsset): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除「${row.name}」？该操作不可恢复。`, '删除资源', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
  } catch {
    return;
  }
  try {
    await deletePlatformAsset(activePlatformType.value, row.id, authContext());
    ElMessage.success('已删除');
    if (platformAssets.value.length === 1 && platformPage.value > 1) platformPage.value -= 1;
    await loadPlatformAssets(true);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败');
  }
}

export async function copyAgent(row: PlatformAsset): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('请输入新智能体名称', '复制智能体', {
      inputValue: `${row.name} - 副本`,
      confirmButtonText: '复制',
      cancelButtonText: '取消',
      inputValidator: input => Boolean(input?.trim()) || '名称不能为空',
    });
    await copyPlatformAgent(row.id, value.trim(), authContext());
    ElMessage.success('复制完成');
    await loadPlatformAssets(true);
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '复制失败');
    }
  }
}

export async function publishAgent(row: PlatformAsset): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      '发布渠道，逗号分隔（platform / rest-api）',
      '发布智能体',
      { inputValue: 'platform,rest-api', confirmButtonText: '发布', cancelButtonText: '取消' },
    );
    const channels = value.split(/[,，]/).map(item => item.trim()).filter(Boolean);
    await publishPlatformAgent(row.id, channels, authContext());
    ElMessage.success('发布完成');
    await loadPlatformAssets(true);
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '发布失败');
    }
  }
}

export async function testModel(row: PlatformAsset): Promise<void> {
  try {
    const result = await testPlatformModel(row.id, authContext());
    const status = result.status ?? 'UNKNOWN';
    if (status === 'AVAILABLE') ElMessage.success('模型连接可用');
    else ElMessage.warning(`模型连接不可用（${status}）`);
    await loadPlatformAssets(true);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '模型连接测试失败');
  }
}

watch(activePlatformType, () => {
  if (activePlatformType.value === 'knowledge-files') void loadKnowledgeBaseOptions();
});

export function knowledgeBaseName(parentId?: number | null): string {
  if (!parentId) return '-';
  return knowledgeBaseOptions.value.find(item => item.id === parentId)?.name ?? `#${parentId}`;
}

export function statusTag(status?: string | null): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'PUBLISHED' || status === 'ENABLED' || status === 'ACTIVE' || status === 'AVAILABLE') return 'success';
  if (status === 'UNTESTED' || status === 'PENDING' || status === 'DRAFT') return 'warning';
  if (status === 'UNAVAILABLE' || status === 'FAILED') return 'danger';
  return 'info';
}

export function formatConfigPlaceholder(config?: string | null): string {
  if (!config) return '{}';
  try {
    return JSON.stringify(JSON.parse(config), null, 2);
  } catch {
    return config;
  }
}

export async function loadPlatformOverview(): Promise<void> {
  platformOverviewLoading.value = true;
  try {
    const types: PlatformAssetType[] = [
      'agents',
      'workflows',
      'tools',
      'knowledge-bases',
      'safety-guards',
      'model-services',
      'databases',
    ];
    const pages = await Promise.all(
      types.map(type => listPlatformAssets(type, authContext(), 1, 1)),
    );
    const result: Record<string, number> = {};
    types.forEach((type, index) => {
      result[type] = pages[index]?.total ?? 0;
    });
    platformOverview.value = result;
  } catch {
    platformOverview.value = {};
  } finally {
    platformOverviewLoading.value = false;
  }
}

export function setPlatformSection(section: PlatformSection): void {
  platformSection.value = section;
  if (section === 'overview') {
    void loadPlatformOverview();
    return;
  }
  if (section === 'knowledge') {
    activeView.value = 'knowledge';
    return;
  }
  const assetType = sectionAssetTypes[section];
  if (assetType) {
    activePlatformType.value = assetType;
    platformPage.value = 1;
    platformSearch.value = '';
    void loadPlatformAssets();
  }
}