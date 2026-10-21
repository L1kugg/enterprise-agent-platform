// 知识库（文档入库 + 文档清单 + 试搜）：从 App.vue 单体拆出，字段与原定义逐字一致。
// 含入库轮询与「上传批次的通用上传函数」——聊天输入框上传（useComposerUpload）
// 复用 submitIngestionUpload / mintIngestionChatId，依赖方向 composerUpload → knowledge。
import { ref } from 'vue';
import type { Ref } from 'vue';

import { ElMessage, ElMessageBox } from 'element-plus';
import type { UploadUserFile } from 'element-plus';

import {
  deleteIngestionDocument,
  downloadIngestionOriginalFile,
  getIngestionDocumentContent,
  listIngestionDocuments,
  listKnowledgeBaseCatalog,
  listRecentIngestionJobs,
  lookupIngestionDocument,
  searchIngestionPreview,
  uploadIngestionDocument,
} from '../api/client';
import type {
  DocumentContent,
  IngestionDocumentSummary,
  IngestionJob,
  KnowledgeBaseCatalogItem,
  RetrievalPreviewResult,
} from '../types/react';
import { isAuthError, shortId } from '../utils/format';
import { activeView } from './useGlobalUi';
import { apiKeyInput, authContext, token } from './useAuthState';

export const knowledgeJobs = ref<IngestionJob[]>([]);
export const knowledgeLoading = ref(false);
export const knowledgeUploading = ref(false);
export const knowledgeUploadFiles = ref<UploadUserFile[]>([]);
// 未登录 / 登录过期时不弹报错，改在页面里给一句提示
export const knowledgeNeedsAuth = ref(false);
// 知识库文档清单（本租户按文档分组，= 每个 chatId 的最新入库任务）+ 分页/搜索
export const knowledgeTab = ref<'documents' | 'jobs' | 'search'>('documents');
export const knowledgeDocuments = ref<IngestionDocumentSummary[]>([]);
export const knowledgeDocsTotal = ref(0);
export const knowledgeDocsPage = ref(1);
export const knowledgeDocsPageSize = ref(20);
export const knowledgeDocsSearch = ref('');
// 知识库目录（FR-R5-01）：上传归属选择 + 文档清单/试搜按库过滤；0 = 不限库
export const knowledgeCatalog = ref<KnowledgeBaseCatalogItem[]>([]);
export const knowledgeCatalogLoading = ref(false);
export const uploadKnowledgeBaseId = ref(0);
export const docsKnowledgeBaseId = ref(0);
export const previewKnowledgeBaseId = ref(0);
// 试搜（检索预览）：只召回不出答案，验证内容能否被检索到
export const previewQuery = ref('');
export const previewLoading = ref(false);
export const previewResult = ref<RetrievalPreviewResult | null>(null);
// 文档内容预览抽屉：点文件名打开，临时 UI 状态不持久化
export const docViewerVisible = ref(false);
export const docViewerLoading = ref(false);
export const docViewerDoc = ref<DocumentContent | null>(null);
/** 引用跳转定位的内容块序号（null = 不定位）；抽屉打开后滚动并高亮该块 */
export const docViewerFocusChunk = ref<number | null>(null);
// 分段治理抽屉（R8）：文档清单「分段」按钮打开
export const segmentManagerVisible = ref(false);
export const segmentManagerChatId = ref('');
export const segmentManagerSourceName = ref('');

export function openSegmentManager(chatId: string, sourceName: string): void {
  segmentManagerChatId.value = chatId;
  segmentManagerSourceName.value = sourceName;
  segmentManagerVisible.value = true;
}

export async function loadKnowledgeJobs(silent = false): Promise<void> {
  if (!token.value && !apiKeyInput.value) {
    // 压根没登录过，别去打接口，页面里提示即可
    knowledgeNeedsAuth.value = true;
    knowledgeJobs.value = [];
    return;
  }
  if (!silent) {
    knowledgeLoading.value = true;
  }
  try {
    knowledgeJobs.value = await listRecentIngestionJobs(authContext());
    knowledgeNeedsAuth.value = false;
  } catch (error) {
    if (isAuthError(error)) {
      // 登录过期（JWT 两小时失效），页面里提示，不弹报错
      knowledgeNeedsAuth.value = true;
      knowledgeJobs.value = [];
    } else if (!silent) {
      const message = error instanceof Error ? error.message : '入库任务加载失败';
      ElMessage.error(message);
    }
  } finally {
    if (!silent) {
      knowledgeLoading.value = false;
    }
  }
}

export async function loadKnowledgeDocuments(silent = false): Promise<void> {
  if (!token.value && !apiKeyInput.value) {
    knowledgeNeedsAuth.value = true;
    knowledgeDocuments.value = [];
    return;
  }
  if (!silent) {
    knowledgeLoading.value = true;
  }
  try {
    const page = await listIngestionDocuments(authContext(), {
      page: knowledgeDocsPage.value,
      pageSize: knowledgeDocsPageSize.value,
      search: knowledgeDocsSearch.value.trim() || undefined,
      knowledgeBaseId: docsKnowledgeBaseId.value || undefined,
    });
    knowledgeDocuments.value = page.items;
    knowledgeDocsTotal.value = page.total;
    knowledgeNeedsAuth.value = false;
  } catch (error) {
    if (isAuthError(error)) {
      knowledgeNeedsAuth.value = true;
      knowledgeDocuments.value = [];
    } else if (!silent) {
      const message = error instanceof Error ? error.message : '文档清单加载失败';
      ElMessage.error(message);
    }
  } finally {
    if (!silent) {
      knowledgeLoading.value = false;
    }
  }
}

/** 加载用户侧知识库目录（ACTIVE 库 + 真实文档统计），上传/过滤/试搜选择器共用。 */
export async function loadKnowledgeCatalog(silent = false): Promise<void> {
  if (!token.value && !apiKeyInput.value) {
    knowledgeCatalog.value = [];
    return;
  }
  if (!silent) {
    knowledgeCatalogLoading.value = true;
  }
  try {
    knowledgeCatalog.value = await listKnowledgeBaseCatalog(authContext());
  } catch (error) {
    if (!isAuthError(error) && !silent) {
      const message = error instanceof Error ? error.message : '知识库目录加载失败';
      ElMessage.error(message);
    }
  } finally {
    if (!silent) {
      knowledgeCatalogLoading.value = false;
    }
  }
}

/** 搜索条件变了要回到第一页，避免停在过滤后的空页上。 */
export function searchKnowledgeDocuments(): void {
  knowledgeDocsPage.value = 1;
  void loadKnowledgeDocuments();
}

/** 按知识库过滤文档清单：过滤条件变了回到第一页。 */
export function filterKnowledgeDocumentsByKb(): void {
  knowledgeDocsPage.value = 1;
  void loadKnowledgeDocuments();
}

export async function removeKnowledgeDocument(row: IngestionDocumentSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.sourceName}」？将同时清掉它的向量切片和原文件，不可恢复。`,
      '删除文档',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return; // 用户点了取消
  }
  try {
    const msg = await deleteIngestionDocument(row.chatId, authContext());
    ElMessage.success(msg || '已删除');
    // 删到本页只剩一条时回退一页，避免停在空页上
    if (knowledgeDocuments.value.length === 1 && knowledgeDocsPage.value > 1) {
      knowledgeDocsPage.value -= 1;
    }
    await Promise.all([loadKnowledgeDocuments(true), loadKnowledgeJobs(true)]);
  } catch (error) {
    const message = error instanceof Error ? error.message : '删除失败';
    ElMessage.error(message);
  }
}

/**
 * 聊天引用点击跳转（R5 剩余）：按引用的文档名定位 chatId，加载内容并在抽屉里
 * 定位到被引用的内容块（高亮 + 滚动）。同文档名多份时取最新一份。
 */
export async function openDocumentByCitation(sourceName: string, chunkId: string): Promise<void> {
  if (!token.value && !apiKeyInput.value) {
    knowledgeNeedsAuth.value = true;
    return;
  }
  docViewerLoading.value = true;
  docViewerDoc.value = null;
  const chunkIndex = Number.parseInt(chunkId.replace('chunk-', ''), 10);
  docViewerFocusChunk.value = Number.isFinite(chunkIndex) ? chunkIndex : null;
  docViewerVisible.value = true;
  try {
    const located = await lookupIngestionDocument(sourceName, authContext());
    docViewerDoc.value = await getIngestionDocumentContent(located.chatId, authContext());
  } catch (error) {
    docViewerVisible.value = false;
    const message = error instanceof Error ? error.message : '引用原文定位失败';
    ElMessage.error(message);
  } finally {
    docViewerLoading.value = false;
  }
}

/** 点文件名打开内容预览：每次打开都重置旧内容；报错弹 toast 并收起抽屉（401 走页面内未登录提示）。 */
export async function openKnowledgeDocument(row: IngestionDocumentSummary): Promise<void> {
  if (!token.value && !apiKeyInput.value) {
    knowledgeNeedsAuth.value = true;
    return;
  }
  docViewerVisible.value = true;
  docViewerLoading.value = true;
  docViewerDoc.value = null;
  try {
    docViewerDoc.value = await getIngestionDocumentContent(row.chatId, authContext());
  } catch (error) {
    docViewerVisible.value = false;
    if (isAuthError(error)) {
      knowledgeNeedsAuth.value = true;
    } else {
      const message = error instanceof Error ? error.message : '文档内容加载失败';
      ElMessage.error(message);
    }
  } finally {
    docViewerLoading.value = false;
  }
}

/** 下载当前预览文档的原文件：blob + objectURL 触发浏览器下载，文件名用清单里的原始文件名。 */
export async function downloadKnowledgeOriginal(): Promise<void> {
  const doc = docViewerDoc.value;
  if (!doc) {
    return;
  }
  try {
    const blob = await downloadIngestionOriginalFile(doc.chatId, authContext());
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = doc.sourceName || 'document';
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    const message = error instanceof Error ? error.message : '原文件下载失败';
    ElMessage.error(message);
  }
}

export async function runPreviewSearch(): Promise<void> {
  const query = previewQuery.value.trim();
  if (!query) {
    ElMessage.warning('先输入要试搜的内容');
    return;
  }
  previewLoading.value = true;
  try {
    previewResult.value = await searchIngestionPreview(
      query,
      authContext(),
      6,
      previewKnowledgeBaseId.value ? [previewKnowledgeBaseId.value] : undefined,
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : '试搜失败';
    ElMessage.error(message);
  } finally {
    previewLoading.value = false;
  }
}

/** 检索通道名翻译成用户能看懂的词。 */
export function previewSourceLabel(source: string): string {
  if (source === 'vector') {
    return '语义检索';
  }
  if (source === 'keyword') {
    return '关键词';
  }
  if (source === 'graph') {
    return '知识图谱';
  }
  return source;
}

/** 文档清单里的知识库列展示名：目录未加载/已删除时回退为 ID，未归属明示。 */
export function knowledgeBaseLabel(id?: number | null): string {
  if (!id) {
    return '未归属';
  }
  const kb = knowledgeCatalog.value.find((item) => item.id === id);
  return kb ? kb.name : `知识库#${id}`;
}

// 上传批次的 chatId：doc-年月日-时分秒，知识库页和输入框上传共用
export function mintIngestionChatId(): string {
  const stamp = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `doc-${stamp.getFullYear()}${pad(stamp.getMonth() + 1)}${pad(stamp.getDate())}-${pad(stamp.getHours())}${pad(stamp.getMinutes())}${pad(stamp.getSeconds())}`;
}

export async function submitIngestionUpload(
  files: Ref<UploadUserFile[]>,
  loading: Ref<boolean>,
  opts?: { afterUpload?: (chatId: string) => void; knowledgeBaseId?: number },
): Promise<void> {
  const file = files.value[0]?.raw;
  if (!(file instanceof File)) {
    ElMessage.warning('请先选择一个文档文件（支持 PDF / Word / Markdown）');
    return;
  }
  loading.value = true;
  try {
    // 每次上传独立批次：chatId 用时间戳生成，入库任务按批次隔离
    const chatId = mintIngestionChatId();
    const result = await uploadIngestionDocument(chatId, file, authContext(), opts?.knowledgeBaseId);
    ElMessage.success(`已提交入库任务 ${shortId(result.job?.jobId ?? '')}，切分入库需要一点时间`);
    files.value = [];
    opts?.afterUpload?.(chatId);
  } catch (error) {
    const message = error instanceof Error ? error.message : '上传失败';
    ElMessage.error(message);
  } finally {
    loading.value = false;
  }
}

export function submitKnowledgeUpload(): Promise<void> {
  return submitIngestionUpload(knowledgeUploadFiles, knowledgeUploading, {
    afterUpload: () => {
      void loadKnowledgeJobs(true);
      void loadKnowledgeDocuments(true);
      startKnowledgePolling();
    },
    knowledgeBaseId: uploadKnowledgeBaseId.value || undefined,
  });
}

export async function removeKnowledgeJob(row: IngestionJob): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.sourceName}」？将同时清掉它的向量切片和原文件，不可恢复。`,
      '删除文档',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return; // 用户点了取消
  }
  try {
    const msg = await deleteIngestionDocument(row.chatId, authContext());
    ElMessage.success(msg || '已删除');
    await loadKnowledgeJobs(true);
  } catch (error) {
    const message = error instanceof Error ? error.message : '删除失败';
    ElMessage.error(message);
  }
}

// 有进行中的任务时每 3 秒刷新列表，全部到终态自动停表
let knowledgePollTimer: number | null = null;

export function startKnowledgePolling(): void {
  if (knowledgePollTimer !== null) {
    return;
  }
  knowledgePollTimer = window.setInterval(() => {
    const hasActive = knowledgeJobs.value.some(
      (job) => job.status === 'PENDING' || job.status === 'RUNNING' || job.status === 'RETRY',
    );
    if (!hasActive) {
      stopKnowledgePolling();
      return;
    }
    if (activeView.value === 'knowledge') {
      void loadKnowledgeJobs(true);
      void loadKnowledgeDocuments(true);
    }
  }, 3000);
}

export function stopKnowledgePolling(): void {
  if (knowledgePollTimer !== null) {
    window.clearInterval(knowledgePollTimer);
    knowledgePollTimer = null;
  }
}
