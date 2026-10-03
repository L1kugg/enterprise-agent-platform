// 深度研究（聊天输入框模式）：任务受理、taskId 轮询、气泡收尾、报告格式化。
// 从 App.vue 单体拆出，字段与原定义逐字一致。依赖 useChatState（状态复位）
// 与 useSessions（syncCurrentSessionBranch 回写会话）——保持单向，无环。
import { ElMessage } from 'element-plus';

import { createResearchTask, getResearchReport, getResearchTask } from '../api/client';
import type { ChatMessage } from '../types/console';
import { createMessage } from '../utils/models';
import { isAuthError } from '../utils/format';
import {
  currentAbortController,
  isStreamingResponse,
  messages,
  modelProfile,
  prompt,
  researchRunning,
  sanitizeMessageStates,
  scheduleStreamReset,
  sending,
  streamPhase,
  streamStatusDetail,
  traceSteps,
} from './useChatState';
import { persistState } from './persistence';
import { syncCurrentSessionBranch } from './useSessions';
import { authContext, canUseRemoteSync } from './useAuthState';
import { scrollToBottom } from './useChatViewport';

// 工作流任务状态 → 中文进度文案
export function researchStatusLabel(status: string | undefined): string {
  switch (status) {
    case 'CREATED':
      return '已创建';
    case 'PLANNING':
      return '正在规划';
    case 'SEARCHING':
      return '正在检索';
    case 'RETRIEVING':
      return '正在召回';
    case 'WRITING':
      return '正在撰写';
    case 'DONE':
      return '已完成';
    case 'FAILED':
      return '失败';
    default:
      return status || '-';
  }
}

// 受理成功后按 taskId 精确盯任务（每 3 秒查单任务，不再扫任务列表，避免同租户他人任务串台）
let researchPollTimer: number | null = null;

export function stopResearchPolling(): void {
  if (researchPollTimer !== null) {
    window.clearInterval(researchPollTimer);
    researchPollTimer = null;
  }
}

// 研究终态收尾：填气泡、复位发送状态、持久化。
// 守卫：迟到的轮询/停止回调不再覆盖已定稿的气泡（重复完成保护）。
function finalizeResearchBubble(
  assistantMsg: ChatMessage,
  phase: 'done' | 'error' | 'stopped',
  content: string,
  statusDetail: string,
): void {
  if (assistantMsg.state !== 'pending') {
    return;
  }
  assistantMsg.content = content;
  assistantMsg.state = phase;
  streamPhase.value = phase;
  streamStatusDetail.value = statusDetail;
  sending.value = false;
  researchRunning.value = false;
  isStreamingResponse.value = false;
  currentAbortController.value = null;

  void (async () => {
    syncCurrentSessionBranch();
    persistState();
    await scrollToBottom(true);
    scheduleStreamReset();
  })();
}

function watchResearchTask(taskId: string, assistantMsg: ChatMessage): void {
  stopResearchPolling();
  let ticks = 0;
  researchPollTimer = window.setInterval(() => {
    void (async () => {
      ticks += 1;
      if (ticks > 200) {
        // 3s × 200 ≈ 10 分钟兜底：别让气泡永远转下去
        stopResearchPolling();
        finalizeResearchBubble(
          assistantMsg,
          'error',
          '深度研究超时：10 分钟未完成，已停止等待（后端任务仍会继续）。',
          '深度研究超时',
        );
        ElMessage.error('深度研究超时');
        return;
      }
      try {
        const task = await getResearchTask(taskId, authContext());
        const label = researchStatusLabel(task.status);
        streamStatusDetail.value = `深度研究：${label}`;
        if (assistantMsg.state === 'pending') {
          assistantMsg.content = `**深度研究进行中：${label}**\n\n> 规划 → 检索 → 召回 → 撰写，全程约 1-3 分钟。`;
        }
        if (task.status === 'DONE') {
          stopResearchPolling();
          try {
            const report = await getResearchReport(taskId, authContext());
            finalizeResearchBubble(
              assistantMsg,
              'done',
              formatResearchReport(report.report ?? ''),
              '深度研究完成',
            );
          } catch {
            finalizeResearchBubble(
              assistantMsg,
              'error',
              '深度研究已完成，但报告获取失败，可稍后重试或到任务记录中查看。',
              '报告获取失败',
            );
          }
        } else if (task.status === 'FAILED') {
          stopResearchPolling();
          // finalOutput 里是后端的失败原因，比泛化文案更有用
          finalizeResearchBubble(
            assistantMsg,
            'error',
            `深度研究失败：${task.finalOutput || '后端任务异常'}`,
            '深度研究失败',
          );
          ElMessage.error('深度研究任务失败');
        }
      } catch (error) {
        if (isAuthError(error)) {
          stopResearchPolling();
          finalizeResearchBubble(
            assistantMsg,
            'error',
            '登录已过期，研究结果可稍后到任务记录中查看。',
            '登录已过期',
          );
        }
        // 其他轮询失败不打断，等下一轮
      }
    })();
  }, 3000);
}

// 输入框发起的深度研究：受理（202 + taskId，毫秒级）→ 按 taskId 轮询 → 完成后拉报告落气泡
export async function runResearchInChat(question: string): Promise<void> {
  if (!question || sending.value) {
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先登录后再发起深度研究');
    return;
  }

  sanitizeMessageStates();

  const assistantMsg: ChatMessage = {
    ...createMessage('assistant', ''),
    citations: [],
    evidence: [],
    state: 'pending',
    kind: 'research',
  };
  messages.value.push(createMessage('user', question));
  messages.value.push(assistantMsg);

  // 研究不是流式接口，清掉旧 trace 时间线，避免上一条的回答过程挂在研究气泡下面
  traceSteps.value = [];
  sending.value = true;
  researchRunning.value = true;
  isStreamingResponse.value = true;
  prompt.value = '';
  streamPhase.value = 'thinking';
  streamStatusDetail.value = '深度研究：任务提交中';

  syncCurrentSessionBranch();
  persistState();
  await scrollToBottom(true);

  const controller = new AbortController();
  currentAbortController.value = controller;
  // 受理之后「停止」按钮的 abort 变成"停止观察"：停轮询、气泡置 stopped，后台任务照跑（跑完仍计费）
  controller.signal.addEventListener('abort', () => {
    stopResearchPolling();
    finalizeResearchBubble(
      assistantMsg,
      'stopped',
      '深度研究已停止等待（后端任务仍会跑完并计费）。',
      '你手动停止了本次研究',
    );
  });

  try {
    const accepted = await createResearchTask(
      question,
      modelProfile.value,
      authContext(),
      controller.signal,
    );
    streamStatusDetail.value = `深度研究：${researchStatusLabel(accepted.status)}`;
    watchResearchTask(accepted.taskId, assistantMsg);
  } catch (error) {
    stopResearchPolling();
    if (error instanceof DOMException && error.name === 'AbortError') {
      finalizeResearchBubble(
        assistantMsg,
        'stopped',
        '深度研究已停止等待（后端任务仍会跑完并计费）。',
        '你手动停止了本次研究',
      );
    } else {
      const message = error instanceof Error ? error.message : 'research failed';
      finalizeResearchBubble(assistantMsg, 'error', `深度研究失败：${message}`, message);
      ElMessage.error(message);
    }
  }
}

// 模型偶尔会直接吐 JSON 字符串当报告（economy 档实测如此），也可能是正经 Markdown；
// 检测到 JSON 就转成分节可读文本，其余原样交给渲染器
export function formatResearchReport(raw: string): string {
  const text = (raw ?? '').trim();
  if (!text.startsWith('{') && !text.startsWith('[')) {
    return text;
  }
  try {
    return researchJsonToMarkdown(JSON.parse(text) as unknown);
  } catch {
    return text;
  }
}

function researchJsonToMarkdown(value: unknown): string {
  if (typeof value === 'string') {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map((item) => `- ${researchInlineValue(item)}`).join('\n');
  }
  if (value && typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([key, val]) => {
        if (Array.isArray(val)) {
          return `### ${key}\n${val.map((item) => `- ${researchInlineValue(item)}`).join('\n')}`;
        }
        return `### ${key}\n${researchInlineValue(val)}`;
      })
      .join('\n\n');
  }
  return String(value ?? '');
}

function researchInlineValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '-';
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}
