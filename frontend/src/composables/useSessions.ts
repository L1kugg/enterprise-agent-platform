// 会话与分支：会话列表、当前会话/分支、云端同步、增删改/置顶/归档/重命名、
// 分支分叉/切换/对比/合并。从 App.vue 单体拆出，字段与原定义逐字一致。
// 依赖：useAuthState（凭证）、useGlobalUi（侧栏过滤）、useChatState（聊天状态回写）、
// useChatViewport（行高/滚底）、useUsage（refreshCostSummary）——保持单向，无环。
// watch 与生命周期不放这里，registerSessionEffects() 只允许被调用一次。
import { computed, ref, watch } from 'vue';

import { ElMessage, ElMessageBox } from 'element-plus';

import {
  compareSessionBranches,
  generateSessionHandoff,
  listSessionStates,
  mergeSessionBranches,
  saveSessionState,
} from '../api/client';
import type { SessionState } from '../types/react';
import type { BranchTreeItem, ChatMessage, SessionBranch, SessionRecord } from '../types/console';
import { DEFAULT_WORKSPACE } from '../utils/constants';
import {
  createBranchId,
  createRootBranch,
  createSession,
  deriveTitle,
  normalizeSession,
} from '../utils/models';
import {
  agentEngine,
  chatId,
  editingMessageDraft,
  editingMessageId,
  messages,
  modelProfile,
  prompt,
  streaming,
  traceSteps,
} from './useChatState';
import { messageHeights, scrollToBottom } from './useChatViewport';
import { persistState, readBootstrap, registerPersistSlice } from './persistence';
import {
  sessionColCollapsed,
  sessionSearch,
  showArchivedSessions,
  workspaceFilter,
} from './useGlobalUi';
import { authContext, canUseRemoteSync } from './useAuthState';
import { refreshCostSummary } from './useUsage';

const bootstrap = readBootstrap();

export const sessions = ref<SessionRecord[]>(
  Array.isArray(bootstrap.sessions) && bootstrap.sessions.length > 0
    ? (bootstrap.sessions as unknown[]).map((item) => normalizeSession(item))
    : [createSession()],
);

export const activeSessionId = ref(
  (bootstrap.activeSessionId as string | undefined) ?? sessions.value[0].id,
);

// 未在同步中的会话保存请求（true = 云端拉取/保存进行中）
export const cloudSyncing = ref(false);

registerPersistSlice(() => ({
  activeSessionId: activeSessionId.value,
  sessions: sessions.value,
}));

export const activeSession = computed(() => {
  const found = sessions.value.find((item) => item.id === activeSessionId.value);
  return found ?? sessions.value[0];
});

export const activeBranch = computed(() => {
  const session = activeSession.value;
  return (
    session.branches.find((branch) => branch.id === session.activeBranchId) ?? session.branches[0]
  );
});

/** 把当前会话分支灌进聊天状态（原 App.vue setup 里的初始化赋值，逐字保留）。 */
export function initChatFromActiveSession(): void {
  chatId.value = activeSession.value.id;
  modelProfile.value = activeSession.value.modelProfile;
  streaming.value = activeSession.value.streaming;
  messages.value = [...activeBranch.value.messages];
  traceSteps.value = [...activeBranch.value.traceSteps];
}

export const sessionCount = computed(() => sessions.value.length);

export const workspaceOptions = computed(() => {
  const options = new Set<string>([DEFAULT_WORKSPACE]);
  sessions.value.forEach((session) => {
    options.add(session.workspaceId || DEFAULT_WORKSPACE);
  });
  return [...options].sort((a, b) => a.localeCompare(b, 'zh-CN'));
});

export const activeWorkspaceId = computed({
  get: () => activeSession.value.workspaceId,
  set: (value: string) => {
    activeSession.value.workspaceId = value || DEFAULT_WORKSPACE;
    persistState();
  },
});

export const orderedSessions = computed(() =>
  [...sessions.value].sort((a, b) => {
    if (a.pinned !== b.pinned) {
      return Number(b.pinned) - Number(a.pinned);
    }
    return b.updatedAt - a.updatedAt;
  }),
);

export const filteredSessions = computed(() => {
  const keyword = sessionSearch.value.trim().toLowerCase();
  return orderedSessions.value.filter((session) => {
    if (!showArchivedSessions.value && session.archived) {
      return false;
    }

    if (workspaceFilter.value !== 'all' && session.workspaceId !== workspaceFilter.value) {
      return false;
    }

    if (!keyword) {
      return true;
    }

    return (
      session.title.toLowerCase().includes(keyword) || session.id.toLowerCase().includes(keyword)
    );
  });
});

export const branchTreeItems = computed<BranchTreeItem[]>(() => {
  const session = activeSession.value;
  const children = new Map<string | null, SessionBranch[]>();

  session.branches.forEach((branch) => {
    const key = branch.parentBranchId ?? null;
    if (!children.has(key)) {
      children.set(key, []);
    }
    children.get(key)?.push(branch);
  });

  children.forEach((list) => {
    list.sort((a, b) => b.updatedAt - a.updatedAt);
  });

  const result: BranchTreeItem[] = [];

  function dfs(parentId: string | null, depth: number): void {
    const list = children.get(parentId) ?? [];
    list.forEach((branch) => {
      result.push({ branch, depth });
      dfs(branch.id, depth + 1);
    });
  }

  dfs(null, 0);
  return result;
});

function normalizeRemoteSession(raw: unknown): SessionRecord {
  return normalizeSession(raw);
}

export async function loadSessionsFromCloud(): Promise<void> {
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再同步');
    return;
  }
  cloudSyncing.value = true;
  try {
    const page = await listSessionStates(authContext(), {
      page: 1,
      pageSize: 200,
      includeArchived: true,
    });
    if (Array.isArray(page.items) && page.items.length > 0) {
      sessions.value = page.items.map((item) => normalizeRemoteSession(item));
      const current =
        sessions.value.find((item) => item.id === activeSessionId.value) ?? sessions.value[0];
      loadSession(current.id);
      persistState();
    }
    await refreshCostSummary();
    ElMessage.success('已从云端加载会话');
  } catch (error) {
    const message = error instanceof Error ? error.message : '云端拉取失败';
    ElMessage.error(message);
  } finally {
    cloudSyncing.value = false;
  }
}

export async function syncActiveSessionToCloud(): Promise<void> {
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再同步');
    return;
  }
  syncCurrentSessionBranch();
  cloudSyncing.value = true;
  try {
    const saved = await saveSessionState(
      activeSession.value as unknown as SessionState,
      authContext(),
    );
    const normalized = normalizeRemoteSession(saved);
    const index = sessions.value.findIndex((item) => item.id === normalized.id);
    if (index >= 0) {
      sessions.value[index] = normalized;
    } else {
      sessions.value.unshift(normalized);
    }
    loadSession(normalized.id);
    persistState();
    await refreshCostSummary();
    ElMessage.success('当前会话已保存到云端');
  } catch (error) {
    const message = error instanceof Error ? error.message : '云端保存失败';
    ElMessage.error(message);
  } finally {
    cloudSyncing.value = false;
  }
}

export function getSession(sessionId: string): SessionRecord | undefined {
  return sessions.value.find((item) => item.id === sessionId);
}

function getBranch(session: SessionRecord, branchId: string): SessionBranch | undefined {
  return session.branches.find((branch) => branch.id === branchId);
}

/** 把内存里的聊天状态回写到当前会话的当前分支（标题/时间戳一并刷新）。 */
export function syncCurrentSessionBranch(): void {
  const session = getSession(activeSessionId.value);
  if (!session) {
    return;
  }

  const branch = getBranch(session, session.activeBranchId);
  if (!branch) {
    return;
  }

  session.modelProfile = modelProfile.value;
  session.streaming = streaming.value;

  branch.messages = [...messages.value];
  branch.traceSteps = [...traceSteps.value];
  branch.updatedAt = Date.now();

  const firstUser = branch.messages.find((item) => item.role === 'user');
  if (firstUser?.content?.trim()) {
    branch.title = deriveTitle(firstUser.content);
    // 只在还是默认标题时自动起名：用户手动重命名过就不覆盖
    if (session.title === '新会话') {
      session.title = deriveTitle(firstUser.content);
    }
  }

  session.updatedAt = Date.now();
}

export function loadSession(sessionId: string): void {
  const session = getSession(sessionId);
  if (!session) {
    return;
  }

  activeSessionId.value = session.id;
  chatId.value = session.id;
  modelProfile.value = session.modelProfile;
  streaming.value = session.streaming;

  const branch = getBranch(session, session.activeBranchId) ?? session.branches[0];
  if (!branch) {
    const rootBranch = createRootBranch();
    session.branches = [rootBranch];
    session.activeBranchId = rootBranch.id;
    messages.value = [...rootBranch.messages];
    traceSteps.value = [...rootBranch.traceSteps];
  } else {
    messages.value = [...branch.messages];
    traceSteps.value = [...branch.traceSteps];
  }

  messageHeights.value = {};
  prompt.value = '';
  editingMessageId.value = null;
  editingMessageDraft.value = '';
  void scrollToBottom(true);
}

export function switchSession(sessionId: string): void {
  if (sessionId === activeSessionId.value) {
    return;
  }

  syncCurrentSessionBranch();
  loadSession(sessionId);
  persistState();
}

export function createAndSwitchSession(): void {
  syncCurrentSessionBranch();
  const session = createSession();
  sessions.value.unshift(session);
  loadSession(session.id);
  persistState();
}

export function removeSession(sessionId: string): void {
  if (sessions.value.length <= 1) {
    ElMessage.warning('至少保留一个会话');
    return;
  }

  syncCurrentSessionBranch();
  const filtered = sessions.value.filter((item) => item.id !== sessionId);
  sessions.value = filtered;

  if (activeSessionId.value === sessionId) {
    const next = filtered.find((item) => !item.archived) ?? filtered[0];
    loadSession(next.id);
  }

  persistState();
}

export async function toggleSessionPin(sessionId: string): Promise<void> {
  const session = getSession(sessionId);
  if (!session) {
    return;
  }
  session.pinned = !session.pinned;
  session.updatedAt = Date.now();
  persistState();
  if (canUseRemoteSync.value) {
    try {
      // 走整会话保存而非 /pin 端点：本地新建、尚未上云的会话在云端没有档案，
      // 专用 pin 端点是纯 UPDATE、会 400（session not found）；upsert 对缺失
      // 会话直接建档并连同置顶标志一起持久化（与重命名同路）。
      await saveSessionState(session as unknown as SessionState, authContext());
    } catch (error) {
      const message = error instanceof Error ? error.message : '会话置顶同步失败';
      ElMessage.error(message);
    }
  }
}

/** 重命名会话：先改本地，再尽力同步云端（整会话保存）。 */
export async function renameSession(sessionId: string): Promise<void> {
  const session = getSession(sessionId);
  if (!session) {
    return;
  }
  let newName: string;
  try {
    const result = await ElMessageBox.prompt('给这个会话起个好认的名字', '重命名会话', {
      inputValue: session.title,
      inputPattern: /\S/,
      inputErrorMessage: '名称不能为空',
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    });
    newName = result.value.trim();
  } catch {
    return; // 用户点了取消
  }
  if (!newName || newName === session.title) {
    return;
  }
      session.title = newName;
  session.updatedAt = Date.now();
  persistState();
  if (canUseRemoteSync.value) {
    try {
      await saveSessionState(session as unknown as SessionState, authContext());
    } catch (error) {
      const message = error instanceof Error ? error.message : '会话重命名同步失败';
      ElMessage.error(message);
    }
  }
}

export async function generateHandoffSummary(sessionId: string): Promise<void> {
  const session = getSession(sessionId);
  if (!session) {
    return;
  }
  let summary = '';
  try {
    const normalized = normalizeRemoteSession(
      await generateSessionHandoff(sessionId, authContext()),
    );
    summary = normalized.handoffSummary ?? '';
    const index = sessions.value.findIndex((item) => item.id === sessionId);
    if (index >= 0) {
      sessions.value[index] = normalized;
    }
    if (activeSessionId.value === sessionId) {
      loadSession(sessionId);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '交接摘要生成失败');
    return;
  }
  if (!summary) {
    ElMessage.warning('交接摘要为空');
    return;
  }
  try {
    await navigator.clipboard.writeText(summary);
    await ElMessageBox.alert(summary, '交接摘要已复制', {
      confirmButtonText: '完成',
      customStyle: { maxWidth: 'min(920px, 92vw)', whiteSpace: 'pre-wrap' },
    });
  } catch {
    await ElMessageBox.alert(summary, '交接摘要', {
      confirmButtonText: '完成',
      customStyle: { maxWidth: 'min(920px, 92vw)', whiteSpace: 'pre-wrap' },
    });
  }
}

export async function toggleSessionArchive(sessionId: string): Promise<void> {
  const session = getSession(sessionId);
  if (!session) {
    return;
  }

  session.archived = !session.archived;
  session.updatedAt = Date.now();

  if (session.archived && !showArchivedSessions.value && activeSessionId.value === sessionId) {
    const next =
      sessions.value.find((item) => item.id !== sessionId && !item.archived) ??
      sessions.value.find((item) => item.id !== sessionId) ??
      createSession();

    if (!sessions.value.find((item) => item.id === next.id)) {
      sessions.value.unshift(next);
    }

    loadSession(next.id);
  }

  persistState();
  if (canUseRemoteSync.value) {
    try {
      // 同置顶：整会话保存兜底本地未上云会话（纯 UPDATE 的 /archive 端点会 400）
      await saveSessionState(session as unknown as SessionState, authContext());
    } catch (error) {
      const message = error instanceof Error ? error.message : '会话归档同步失败';
      ElMessage.error(message);
    }
  }
}

/**
 * 工作区下拉变更：选已有项直接切；allow-create 输入的新名字在这里落地——
 * 规范化（去空白/转小写）后把当前会话挪过去，并把会话栏过滤器同步到该工作区。
 */
export function handleWorkspaceChange(value: string): void {
  const normalized = (value ?? '').trim().toLowerCase();
  if (!normalized) {
    return;
  }
  if (normalized !== value) {
    // 输入的新名字可能带空格/大写：纠正回规范化值再落库
    activeWorkspaceId.value = normalized;
  }
  workspaceFilter.value = normalized;
  persistState();
}

export function switchBranch(branchId: string): void {
  const session = activeSession.value;
  if (session.activeBranchId === branchId) {
    return;
  }

  syncCurrentSessionBranch();
  session.activeBranchId = branchId;
  loadSession(session.id);
  persistState();
}

export function forkBranch(
  title: string,
  baseMessages: ChatMessage[],
  parentBranchId: string | null,
  parentMessageId: string | null,
): SessionBranch {
  return {
    id: createBranchId(),
    title,
    parentBranchId,
    parentMessageId,
    updatedAt: Date.now(),
    messages: [...baseMessages],
    traceSteps: [],
  };
}

export function forkFromCurrent(): void {
  const session = activeSession.value;
  const current = activeBranch.value;

  const branch = forkBranch(`${current.title} · fork`, [...messages.value], current.id, null);

  session.branches.unshift(branch);
  session.activeBranchId = branch.id;
  loadSession(session.id);
  persistState();
  ElMessage.success('已创建分支');
}

export async function compareWithParent(): Promise<void> {
  const current = activeBranch.value;
  if (!current?.parentBranchId) {
    ElMessage.warning('当前分支没有父分支可对比');
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再执行云端分支对比');
    return;
  }
  syncCurrentSessionBranch();
  try {
    await syncActiveSessionToCloud();
    const result = await compareSessionBranches(
      activeSession.value.id,
      {
        sourceBranchId: current.id,
        targetBranchId: current.parentBranchId,
      },
      authContext(),
    );
    ElMessage.success(
      `对比完成：公共 ${result.commonMessageCount}，当前独有 ${result.sourceOnlyCount}，父分支独有 ${result.targetOnlyCount}`,
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : '分支对比失败';
    ElMessage.error(message);
  }
}

export async function mergeIntoParent(): Promise<void> {
  const current = activeBranch.value;
  if (!current?.parentBranchId) {
    ElMessage.warning('当前分支没有父分支可合并');
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再执行云端分支合并');
    return;
  }
  syncCurrentSessionBranch();
  try {
    await syncActiveSessionToCloud();
    const result = await mergeSessionBranches(
      activeSession.value.id,
      {
        sourceBranchId: current.id,
        targetBranchId: current.parentBranchId,
        title: `${current.title} -> ${current.parentBranchId} merge`,
      },
      authContext(),
    );
    const normalized = normalizeRemoteSession(result.session);
    const index = sessions.value.findIndex((item) => item.id === normalized.id);
    if (index >= 0) {
      sessions.value[index] = normalized;
    } else {
      sessions.value.unshift(normalized);
    }
    loadSession(normalized.id);
    persistState();
    ElMessage.success(`合并完成，分支消息数 ${result.mergedMessageCount}`);
  } catch (error) {
    const message = error instanceof Error ? error.message : '分支合并失败';
    ElMessage.error(message);
  }
}

/** 会话副作用（偏好/引擎变更回写当前分支 + 持久化；消息流深度监听同），只允许被调用一次。 */
export function registerSessionEffects(): void {
  watch(
    [
      modelProfile,
      streaming,
      workspaceFilter,
      showArchivedSessions,
      sessionSearch,
      agentEngine,
      sessionColCollapsed,
    ],
    () => {
      syncCurrentSessionBranch();
      persistState();
    },
  );

  watch(
    [messages, traceSteps],
    () => {
      syncCurrentSessionBranch();
      persistState();
    },
    { deep: true },
  );
}
