// 控制台前端自己的界面模型（区别于 types/react.ts 里的后端 DTO）。
// 从 App.vue 单体拆出，字段与原定义逐字一致。
import type { EvalMetricSummary, ReactTraceStep } from './react';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: number;
  citations?: string[];
  evidence?: string[];
  state?: 'pending' | 'streaming' | 'done' | 'error' | 'stopped';
  kind?: 'research'; // 深度研究产生的助手消息（打徽标用）
}

export interface SessionBranch {
  id: string;
  title: string;
  parentBranchId: string | null;
  parentMessageId: string | null;
  updatedAt: number;
  messages: ChatMessage[];
  traceSteps: ReactTraceStep[];
}

export interface SessionRecord {
  id: string;
  title: string;
  updatedAt: number;
  modelProfile: string;
  streaming: boolean;
  pinned: boolean;
  archived: boolean;
  workspaceId: string;
  activeBranchId: string;
  branches: SessionBranch[];
}

export interface BranchTreeItem {
  branch: SessionBranch;
  depth: number;
}

export interface MessageMetric {
  item: ChatMessage;
  index: number;
  offset: number;
  height: number;
}

export type StreamPhase = 'idle' | 'thinking' | 'tool' | 'streaming' | 'done' | 'error' | 'stopped';
export type ConsoleView = 'chat' | 'evaluation' | 'knowledge' | 'admin' | 'usage';

export interface EvalMetricCard {
  key: keyof EvalMetricSummary;
  label: string;
  current: string;
  delta: string;
  deltaClass: string;
}

export type ComposerChipKind = 'uploading' | 'parsing' | 'ready' | 'error';

export interface ComposerUploadChip {
  kind: ComposerChipKind;
  name: string;
  chatId: string;
  text: string;
}
