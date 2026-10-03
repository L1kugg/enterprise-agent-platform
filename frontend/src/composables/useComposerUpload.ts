// 聊天输入框上传文档（进知识库，非文档限定问答）：从 App.vue 单体拆出，
// 字段与原定义逐字一致。复用 useKnowledge 的 mintIngestionChatId。
import { ref } from 'vue';

import { ElMessage } from 'element-plus';

import { listRecentIngestionJobs, uploadIngestionDocument } from '../api/client';
import type { ComposerUploadChip } from '../types/console';
import { isAuthError } from '../utils/format';
import { mintIngestionChatId } from './useKnowledge';
import { authContext, canUseRemoteSync } from './useAuthState';

export const composerFileInput = ref<HTMLInputElement | null>(null);
export const composerUploading = ref(false);
export const composerUploadChip = ref<ComposerUploadChip | null>(null);
let composerUploadPollTimer: number | null = null;
let composerChipDismissTimer: number | null = null;

export function triggerComposerUpload(): void {
  composerFileInput.value?.click();
}

export async function onComposerFileChosen(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = ''; // 允许下次重选同一个文件
  if (!file) {
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先登录后再上传文档到知识库');
    return;
  }
  if (!/\.(pdf|docx?|md)$/i.test(file.name)) {
    ElMessage.warning('支持 PDF / Word（doc、docx）/ Markdown 文件');
    return;
  }
  if (
    composerUploadChip.value &&
    (composerUploadChip.value.kind === 'uploading' || composerUploadChip.value.kind === 'parsing')
  ) {
    ElMessage.warning('已有文件在解析中，稍等片刻再传');
    return;
  }
  await submitComposerUpload(file);
}

async function submitComposerUpload(file: File): Promise<void> {
  const chatId = mintIngestionChatId();
  composerUploadChip.value = { kind: 'uploading', name: file.name, chatId, text: '上传中…' };
  composerUploading.value = true;
  try {
    await uploadIngestionDocument(chatId, file, authContext());
    composerUploadChip.value = { kind: 'parsing', name: file.name, chatId, text: '解析中…' };
    startComposerUploadPolling();
  } catch (error) {
    const message = error instanceof Error ? error.message : '上传失败';
    composerUploadChip.value = { kind: 'error', name: file.name, chatId, text: message };
    scheduleComposerChipDismiss(8000);
  } finally {
    composerUploading.value = false;
  }
}

// 每 3 秒盯一次入库任务，把 解析中→已可提问/失败 刷到胶囊上
function startComposerUploadPolling(): void {
  stopComposerUploadPolling();
  let ticks = 0;
  composerUploadPollTimer = window.setInterval(() => {
    void (async () => {
      ticks += 1;
      const chip = composerUploadChip.value;
      if (!chip || chip.kind !== 'parsing') {
        stopComposerUploadPolling();
        return;
      }
      if (ticks > 100) {
        // 约 5 分钟兜底：别让胶囊永远转下去
        composerUploadChip.value = {
          ...chip,
          kind: 'error',
          text: '解析超时，可到知识库页查看状态',
        };
        stopComposerUploadPolling();
        scheduleComposerChipDismiss(8000);
        return;
      }
      try {
        const jobs = await listRecentIngestionJobs(authContext(), 20);
        const job = jobs.find((item) => item.chatId === chip.chatId);
        if (!job) {
          return; // 任务还没出现在列表里，等下一轮
        }
        if (job.status === 'SUCCEEDED') {
          composerUploadChip.value = { ...chip, kind: 'ready', text: '已入库，照常提问即可检索到' };
          stopComposerUploadPolling();
          scheduleComposerChipDismiss(5000);
        } else if (job.status === 'FAILED') {
          composerUploadChip.value = {
            ...chip,
            kind: 'error',
            text: job.errorMessage || '解析失败',
          };
          stopComposerUploadPolling();
          scheduleComposerChipDismiss(8000);
        }
      } catch (error) {
        if (isAuthError(error)) {
          composerUploadChip.value = {
            ...chip,
            kind: 'error',
            text: '登录已过期，重新登录后可见结果',
          };
          stopComposerUploadPolling();
          scheduleComposerChipDismiss(8000);
        }
        // 其他轮询失败不打断，等下一轮
      }
    })();
  }, 3000);
}

export function stopComposerUploadPolling(): void {
  if (composerUploadPollTimer !== null) {
    window.clearInterval(composerUploadPollTimer);
    composerUploadPollTimer = null;
  }
}

function scheduleComposerChipDismiss(delay = 5000): void {
  if (composerChipDismissTimer !== null) {
    window.clearTimeout(composerChipDismissTimer);
  }
  composerChipDismissTimer = window.setTimeout(dismissComposerUploadChip, delay);
}

export function dismissComposerUploadChip(): void {
  stopComposerUploadPolling();
  if (composerChipDismissTimer !== null) {
    window.clearTimeout(composerChipDismissTimer);
    composerChipDismissTimer = null;
  }
  composerUploadChip.value = null;
}
