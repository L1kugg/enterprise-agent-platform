// 聊天状态（叶模块）：消息、流式状态、输入区、引擎与深度研究开关。
// 从 App.vue 单体拆出，字段与原定义逐字一致。与原文件的两处差别：
// 1. chatId/modelProfile/streaming/messages/traceSteps 原地初始化自当前会话
//    分支，这里改为空默认值——真正的初始化由 useSessions.initChatFromActiveSession()
//    在 App setup 第一条语句执行（onMounted 的 loadSession 反正会重读，行为等价）。
// 2. scheduleStreamReset/sanitizeMessageStates/upsertTrace 收拢进本模块（叶），
//    让 useChatEngine/useResearch 单向依赖这里，避免 chatEngine↔research 成环。
import { computed, ref } from 'vue';

import type { AgentEngine } from '../types/react';
import type { ChatMessage, StreamPhase } from '../types/console';
import { readBootstrap, registerPersistSlice } from './persistence';

const bootstrap = readBootstrap();

export const chatId = ref('');
export const modelProfile = ref('balanced');
export const streaming = ref(true);
export const messages = ref<ChatMessage[]>([]);
export const traceSteps = ref<ChatMessageTraceSteps>([]);

// ReactTraceStep 的类型别名仅为可读性；与 types/react.ts 保持同源
type ChatMessageTraceSteps = import('../types/react').ReactTraceStep[];

export const traceDurationMs = computed(() => {
  if (!traceSteps.value.length) return 0;
  // 若无真实耗时数据，按每步约 2 秒粗略估算
  return traceSteps.value.length * 2000;
});

export const sending = ref(false);
export const isStreamingResponse = ref(false);
export const prompt = ref('');
// 首屏示例问题：点击即填入输入框并聚焦，降低新用户上手成本
export const composerInputRef = ref<{ focus: () => void } | null>(null);

export function applySuggestion(question: string): void {
  prompt.value = question;
  composerInputRef.value?.focus();
}

export const currentAbortController = ref<AbortController | null>(null);
export const editingMessageId = ref<string | null>(null);
export const editingMessageDraft = ref('');
export const streamPhase = ref<StreamPhase>('idle');
export const streamStatusDetail = ref('');
export const answerFeedbackMap = ref<Record<string, number>>({});
export const answerFeedbackLoading = ref<Record<string, boolean>>({});

// Agent 引擎：standard = 主聊天标准 ReAct；workflow = 工作流版（步骤全留痕可回放，不读写会话记忆）
export const agentEngine = ref<AgentEngine>(
  bootstrap.agentEngine === 'workflow' ? 'workflow' : 'standard',
);

registerPersistSlice(() => ({
  agentEngine: agentEngine.value,
}));

// ---------- 深度研究（聊天输入框模式，开关不持久化） ----------
export const researchMode = ref(false); // 开着时，下一次「发送」改走深度研究
export const researchRunning = ref(false); // 当前 in-flight 的发送是否为深度研究

let streamResetTimer: number | null = null;

export const isEmptyConversation = computed(() => {
  const nonSystem = messages.value.filter((item) => item.role === 'user');
  return nonSystem.length === 0;
});

export const streamStatusLabel = computed(() => {
  switch (streamPhase.value) {
    case 'thinking':
      return '思考中';
    case 'tool':
      return '工具调用中';
    case 'streaming':
      return '输出中';
    case 'done':
      return '已完成';
    case 'error':
      return '失败';
    case 'stopped':
      return '已停止';
    default:
      return '空闲';
  }
});

export const streamStatusTagType = computed(() => {
  switch (streamPhase.value) {
    case 'thinking':
      return 'warning';
    case 'tool':
      return 'success';
    case 'streaming':
      return 'primary';
    case 'done':
      return 'success';
    case 'error':
      return 'danger';
    case 'stopped':
      return 'info';
    default:
      return 'info';
  }
});

export function scheduleStreamReset(): void {
  if (streamResetTimer) {
    window.clearTimeout(streamResetTimer);
  }
  streamResetTimer = window.setTimeout(() => {
    streamPhase.value = 'idle';
    streamStatusDetail.value = '';
    streamResetTimer = null;
  }, 1800);
}

/** 卸载清理用（onBeforeUnmount 调）；计时器变量是模块私有 let，外部不能直接赋 null。 */
export function clearStreamResetTimer(): void {
  if (streamResetTimer) {
    window.clearTimeout(streamResetTimer);
    streamResetTimer = null;
  }
}

export function upsertTrace(step: import('../types/react').ReactTraceStep): void {
  const index = traceSteps.value.findIndex((item) => item.step === step.step);
  if (index >= 0) {
    traceSteps.value[index] = step;
  } else {
    traceSteps.value.push(step);
    traceSteps.value.sort((a, b) => a.step - b.step);
  }
}

export function sanitizeMessageStates(): void {
  messages.value = messages.value.map((message) => ({
    ...message,
    state:
      message.state === 'pending' || message.state === 'streaming'
        ? 'done'
        : message.state || 'done',
  }));
}
