// 会话/消息/分支模型工厂与归一化：从 App.vue 单体拆出，实现逐字一致。
import type { ChatMessage, SessionBranch, SessionRecord } from '../types/console';
import type { ReactTraceStep } from '../types/react';
import { DEFAULT_SYSTEM_MESSAGE, DEFAULT_WORKSPACE } from './constants';

export function createChatId(): string {
  const suffix = Math.random().toString(36).slice(2, 8);
  return `react-${Date.now()}-${suffix}`;
}

export function createBranchId(): string {
  const suffix = Math.random().toString(36).slice(2, 8);
  return `branch-${Date.now()}-${suffix}`;
}

export function createMessage(role: ChatMessage['role'], content: string): ChatMessage {
  return {
    id: `${role}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content,
    createdAt: Date.now(),
    state: 'done',
  };
}

export function normalizeMessage(raw: unknown): ChatMessage {
  const candidate = (raw ?? {}) as Partial<ChatMessage>;
  const role = candidate.role === 'assistant' ? 'assistant' : 'user';
  return {
    id: candidate.id || `${role}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content: typeof candidate.content === 'string' ? candidate.content : '',
    createdAt: typeof candidate.createdAt === 'number' ? candidate.createdAt : Date.now(),
    citations: Array.isArray(candidate.citations)
      ? candidate.citations.map((item) => String(item).trim()).filter(Boolean)
      : [],
    evidence: Array.isArray(candidate.evidence)
      ? candidate.evidence.map((item) => String(item).trim()).filter(Boolean)
      : [],
    state: candidate.state || 'done',
    kind: candidate.kind === 'research' ? 'research' : undefined,
  };
}

export function deriveTitle(text: string): string {
  const clean = text.trim().replace(/\s+/g, ' ');
  if (!clean) {
    return '新会话';
  }
  return clean.length > 28 ? `${clean.slice(0, 28)}...` : clean;
}

export function createRootBranch(): SessionBranch {
  return {
    id: createBranchId(),
    title: '主分支',
    parentBranchId: null,
    parentMessageId: null,
    updatedAt: Date.now(),
    messages: [createMessage('assistant', DEFAULT_SYSTEM_MESSAGE)],
    traceSteps: [],
  };
}

export function normalizeBranch(raw: unknown): SessionBranch {
  const candidate = (raw ?? {}) as Partial<SessionBranch>;
  const messages = Array.isArray(candidate.messages)
    ? candidate.messages.map(normalizeMessage)
    : [createMessage('assistant', DEFAULT_SYSTEM_MESSAGE)];

  return {
    id: candidate.id || createBranchId(),
    title: candidate.title || '分支',
    parentBranchId: candidate.parentBranchId ?? null,
    parentMessageId: candidate.parentMessageId ?? null,
    updatedAt: typeof candidate.updatedAt === 'number' ? candidate.updatedAt : Date.now(),
    messages,
    traceSteps: Array.isArray(candidate.traceSteps) ? candidate.traceSteps : [],
  };
}

export function normalizeSession(raw: unknown): SessionRecord {
  const candidate = (raw ?? {}) as Record<string, unknown>;
  let branches: SessionBranch[] = [];

  if (Array.isArray(candidate.branches) && candidate.branches.length > 0) {
    branches = candidate.branches.map((item) => normalizeBranch(item));
  } else {
    const fallbackMessages = Array.isArray(candidate.messages)
      ? candidate.messages.map(normalizeMessage)
      : [createMessage('assistant', DEFAULT_SYSTEM_MESSAGE)];

    branches = [
      {
        id: createBranchId(),
        title: '主分支',
        parentBranchId: null,
        parentMessageId: null,
        updatedAt: typeof candidate.updatedAt === 'number' ? candidate.updatedAt : Date.now(),
        messages: fallbackMessages,
        traceSteps: Array.isArray(candidate.traceSteps)
          ? (candidate.traceSteps as ReactTraceStep[])
          : [],
      },
    ];
  }

  const activeBranchId =
    typeof candidate.activeBranchId === 'string' ? candidate.activeBranchId : branches[0].id;

  return {
    id: typeof candidate.id === 'string' ? candidate.id : createChatId(),
    title: typeof candidate.title === 'string' ? candidate.title : '新会话',
    updatedAt: typeof candidate.updatedAt === 'number' ? candidate.updatedAt : Date.now(),
    modelProfile: typeof candidate.modelProfile === 'string' ? candidate.modelProfile : 'balanced',
    streaming: Boolean(candidate.streaming ?? true),
    pinned: Boolean(candidate.pinned),
    archived: Boolean(candidate.archived),
    workspaceId:
      typeof candidate.workspaceId === 'string' ? candidate.workspaceId : DEFAULT_WORKSPACE,
    activeBranchId,
    branches,
    handoffSummary: typeof candidate.handoffSummary === 'string' ? candidate.handoffSummary : undefined,
    handoffGeneratedAt: typeof candidate.handoffGeneratedAt === 'number' ? candidate.handoffGeneratedAt : undefined,
  };
}

export function createSession(): SessionRecord {
  const id = createChatId();
  const rootBranch = createRootBranch();

  return {
    id,
    title: '新会话',
    updatedAt: Date.now(),
    modelProfile: 'balanced',
    streaming: true,
    pinned: false,
    archived: false,
    workspaceId: DEFAULT_WORKSPACE,
    activeBranchId: rootBranch.id,
    branches: [rootBranch],
  };
}
