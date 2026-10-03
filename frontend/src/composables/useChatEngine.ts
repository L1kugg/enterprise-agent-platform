// 聊天引擎：发送（SSE 流式 / JSON 单次）、编辑重发、重新生成、停止、点赞反馈、
// 代码/引用/复制等气泡交互。从 App.vue 单体拆出，函数体逐字一致。
// 依赖：useChatState（状态）、useSessions（分支回写）、useResearch（深度研究分流）、
// useChatViewport（滚底）、useAuthState（凭证）——保持单向，无环。
import { ElMessage } from 'element-plus';

import { reactChat, streamReactChat, submitAnswerFeedback } from '../api/client';
import type { ReactChatResponse, ReactErrorEvent, ReactTokenEvent, ReactTraceStep } from '../types/react';
import type { ChatMessage } from '../types/console';
import { fromBase64, writeClipboardText } from '../utils/dom';
import { createMessage, deriveTitle } from '../utils/models';
import {
  agentEngine,
  answerFeedbackLoading,
  answerFeedbackMap,
  chatId,
  editingMessageDraft,
  editingMessageId,
  isStreamingResponse,
  messages,
  modelProfile,
  prompt,
  researchMode,
  sanitizeMessageStates,
  scheduleStreamReset,
  sending,
  streamPhase,
  streamStatusDetail,
  streaming,
  traceSteps,
  upsertTrace,
  currentAbortController,
} from './useChatState';
import { persistState } from './persistence';
import {
  activeBranch,
  activeSession,
  forkBranch,
  loadSession,
  syncCurrentSessionBranch,
} from './useSessions';
import { runResearchInChat } from './useResearch';
import { scrollToBottom } from './useChatViewport';
import { authContext, canUseRemoteSync } from './useAuthState';

export function startEditMessage(message: ChatMessage): void {
  if (message.role !== 'user') {
    return;
  }

  editingMessageId.value = message.id;
  editingMessageDraft.value = message.content;
}

export function cancelEditMessage(): void {
  editingMessageId.value = null;
  editingMessageDraft.value = '';
}

export async function submitEditAndResend(
  messageIndex: number,
  messageId: string,
): Promise<void> {
  const question = editingMessageDraft.value.trim();
  if (!question) {
    return;
  }

  if (sending.value) {
    ElMessage.warning('请等待当前请求完成');
    return;
  }

  syncCurrentSessionBranch();

  const session = activeSession.value;
  const current = activeBranch.value;
  const baseMessages = messages.value.slice(0, Math.max(0, messageIndex));
  const branch = forkBranch(`${deriveTitle(question)} · edit`, baseMessages, current.id, messageId);

  session.branches.unshift(branch);
  session.activeBranchId = branch.id;
  loadSession(session.id);

  cancelEditMessage();
  persistState();

  await ask(question, true);
}

export async function handleMarkdownClick(event: MouseEvent): Promise<void> {
  const target = event.target as HTMLElement | null;
  const button = target?.closest('.copy-code-btn') as HTMLElement | null;
  if (!button) {
    return;
  }

  const payload = button.getAttribute('data-code');
  if (!payload) {
    return;
  }

  try {
    const raw = fromBase64(payload);
    await writeClipboardText(raw);
    ElMessage.success('代码已复制');
  } catch {
    ElMessage.error('代码复制失败');
  }
}

export async function copyMessage(content: string): Promise<void> {
  try {
    await writeClipboardText(content);
    ElMessage.success('已复制');
  } catch {
    ElMessage.error('复制失败');
  }
}

function parseCitation(citation: string): { source: string; chunk: string } | null {
  const matched = citation.match(/source=([^,]+),\s*chunk=(.+)$/i);
  if (!matched) {
    return null;
  }
  return {
    source: matched[1].trim(),
    chunk: matched[2].trim(),
  };
}

export function openCitation(citation: string): void {
  const base = (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api';
  const target = parseCitation(citation);
  const url = `${base}/ai/pdf/file/${encodeURIComponent(chatId.value)}${
    target
      ? `?source=${encodeURIComponent(target.source)}&chunk=${encodeURIComponent(target.chunk)}`
      : ''
  }`;
  window.open(url, '_blank', 'noopener,noreferrer');
}

function findPreviousUserQuestion(index: number): string {
  for (let i = index - 1; i >= 0; i -= 1) {
    const candidate = messages.value[i];
    if (candidate.role === 'user' && candidate.content.trim()) {
      return candidate.content.trim();
    }
  }
  return '';
}

export async function rateAnswer(index: number, message: ChatMessage, rating: number): Promise<void> {
  if (message.role !== 'assistant') {
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再提交反馈');
    return;
  }
  if (answerFeedbackMap.value[message.id]) {
    ElMessage.info('该回答已评分');
    return;
  }
  answerFeedbackLoading.value = {
    ...answerFeedbackLoading.value,
    [message.id]: true,
  };
  try {
    await submitAnswerFeedback(
      {
        chatId: chatId.value,
        sessionId: activeSession.value.id,
        branchId: activeBranch.value.id,
        messageId: message.id,
        rating,
        question: findPreviousUserQuestion(index),
        answer: message.content,
        comment: rating >= 4 ? '回答有帮助' : '回答需要改进',
      },
      authContext(),
    );
    answerFeedbackMap.value = {
      ...answerFeedbackMap.value,
      [message.id]: rating,
    };
    ElMessage.success('反馈已提交并回灌评测集');
  } catch (error) {
    const tip = error instanceof Error ? error.message : '反馈提交失败';
    ElMessage.error(tip);
  } finally {
    answerFeedbackLoading.value = {
      ...answerFeedbackLoading.value,
      [message.id]: false,
    };
  }
}

export function stopGenerating(): void {
  currentAbortController.value?.abort();
}

export function clearConversation(): void {
  messages.value = [createMessage('assistant', '会话已重置。你可以继续发问。')];
  traceSteps.value = [];
  answerFeedbackMap.value = {};
  answerFeedbackLoading.value = {};
  prompt.value = '';
  streamPhase.value = 'idle';
  streamStatusDetail.value = '';
  syncCurrentSessionBranch();
  persistState();
}

async function ask(question: string, appendUser: boolean): Promise<void> {
  if (!question.trim() || sending.value) {
    return;
  }

  sanitizeMessageStates();

  const assistantMsg: ChatMessage = {
    ...createMessage('assistant', ''),
    citations: [],
    evidence: [],
    state: 'pending',
  };

  if (appendUser) {
    messages.value.push(createMessage('user', question));
  }
  messages.value.push(assistantMsg);
  const assistantIndex = messages.value.length - 1;

  traceSteps.value = [];
  sending.value = true;
  isStreamingResponse.value = streaming.value;
  prompt.value = '';
  streamPhase.value = 'thinking';
  streamStatusDetail.value = '模型正在准备响应';

  syncCurrentSessionBranch();
  persistState();
  await scrollToBottom(true);

  const controller = new AbortController();
  currentAbortController.value = controller;

  try {
    if (streaming.value) {
      let streamError = '';
      await streamReactChat(
        {
          prompt: question,
          chatId: chatId.value,
          modelProfile: modelProfile.value,
        },
        authContext(),
        (event, payload) => {
          if (event === 'trace') {
            const step = payload as ReactTraceStep;
            upsertTrace(step);
            streamPhase.value = 'tool';
            streamStatusDetail.value = `调用工具: ${step.action}`;
            return;
          }

          if (event === 'token') {
            const tokenEvent = payload as ReactTokenEvent;
            messages.value[assistantIndex].state = 'streaming';
            messages.value[assistantIndex].content += tokenEvent.token ?? '';
            streamPhase.value = 'streaming';
            streamStatusDetail.value = '正在生成文本';
            void scrollToBottom();
            return;
          }

          if (event === 'done') {
            const done = payload as ReactChatResponse;
            if (done.trace?.length) {
              traceSteps.value = done.trace;
            }
            if (done.answer?.trim()) {
              messages.value[assistantIndex].content = done.answer;
            }
            messages.value[assistantIndex].citations = Array.isArray(done.citations)
              ? done.citations.map((item) => String(item).trim()).filter(Boolean)
              : [];
            messages.value[assistantIndex].evidence = Array.isArray(done.evidence)
              ? done.evidence.map((item) => String(item).trim()).filter(Boolean)
              : [];
            messages.value[assistantIndex].state = 'done';
            streamPhase.value = 'done';
            streamStatusDetail.value = '响应已完成';
            return;
          }

          if (event === 'error') {
            const err = payload as ReactErrorEvent;
            streamError = err.message || 'stream error';
          }
        },
        controller.signal,
        agentEngine.value,
      );

      if (streamError) {
        throw new Error(streamError);
      }
    } else {
      const result = await reactChat(
        {
          prompt: question,
          chatId: chatId.value,
          modelProfile: modelProfile.value,
        },
        authContext(),
        controller.signal,
        agentEngine.value,
      );
      traceSteps.value = result.trace ?? [];
      messages.value[assistantIndex].content = result.answer || '模型没有返回内容';
      messages.value[assistantIndex].citations = Array.isArray(result.citations)
        ? result.citations.map((item) => String(item).trim()).filter(Boolean)
        : [];
      messages.value[assistantIndex].evidence = Array.isArray(result.evidence)
        ? result.evidence.map((item) => String(item).trim()).filter(Boolean)
        : [];
      messages.value[assistantIndex].state = 'done';
      streamPhase.value = 'done';
      streamStatusDetail.value = '响应已完成';
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      ElMessage.info('已停止输出');
      if (!messages.value[assistantIndex].content.trim()) {
        messages.value[assistantIndex].content = '输出已手动停止。';
      }
      messages.value[assistantIndex].state = 'stopped';
      streamPhase.value = 'stopped';
      streamStatusDetail.value = '你手动停止了本次输出';
    } else {
      const message = error instanceof Error ? error.message : 'request failed';
      messages.value[assistantIndex].content = `请求失败：${message}`;
      messages.value[assistantIndex].state = 'error';
      streamPhase.value = 'error';
      streamStatusDetail.value = message;
      ElMessage.error(message);
    }
  } finally {
    sending.value = false;
    isStreamingResponse.value = false;
    currentAbortController.value = null;

    syncCurrentSessionBranch();
    persistState();
    await scrollToBottom(true);

    if (
      streamPhase.value === 'done' ||
      streamPhase.value === 'error' ||
      streamPhase.value === 'stopped'
    ) {
      scheduleStreamReset();
    }
  }
}

export async function send(): Promise<void> {
  const question = prompt.value.trim();
  // 深度研究开关开着时，这一条发送改走研究报告流程（ask 仍服务普通聊天和重新生成）
  if (researchMode.value) {
    await runResearchInChat(question);
    return;
  }
  await ask(question, true);
}

export async function regenerateFrom(assistantIndex: number): Promise<void> {
  for (let i = assistantIndex - 1; i >= 0; i -= 1) {
    const candidate = messages.value[i];
    if (candidate.role === 'user' && candidate.content.trim()) {
      syncCurrentSessionBranch();
      const session = activeSession.value;
      const current = activeBranch.value;
      const baseMessages = messages.value.slice(0, i);
      const branch = forkBranch(
        `${deriveTitle(candidate.content)} · retry`,
        baseMessages,
        current.id,
        candidate.id,
      );
      session.branches.unshift(branch);
      session.activeBranchId = branch.id;
      loadSession(session.id);
      persistState();
      await ask(candidate.content, true);
      return;
    }
  }

  ElMessage.warning('没有找到可重试的用户问题');
}
