// 聊天输入框临时附件（随手甩文件问答，不入知识库）：文件选中即解析为纯文本短存 30 分钟，
// attachmentId 随 /ai/react 请求注入规划与成稿上下文。chip 常驻（支持追问），手动 × 移除。
import { computed, ref } from 'vue';

import { ElMessage } from 'element-plus';

import { uploadChatAttachment } from '../api/client';
import type { ComposerUploadChip } from '../types/console';
import { authContext, canUseRemoteSync } from './useAuthState';

export const composerFileInput = ref<HTMLInputElement | null>(null);
export const composerUploading = ref(false);
export const composerUploadChip = ref<ComposerUploadChip | null>(null);

/** 当前就绪的附件 id：chip 为 ready 态时随消息携带。 */
export const composerAttachmentId = computed(() =>
  composerUploadChip.value && composerUploadChip.value.kind === 'ready' && composerUploadChip.value.attachmentId
    ? composerUploadChip.value.attachmentId
    : undefined,
);

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
    ElMessage.warning('请先登录后再上传附件');
    return;
  }
  if (!/\.(pdf|docx?|md|txt)$/i.test(file.name)) {
    ElMessage.warning('支持 PDF / Word（doc、docx）/ Markdown / 文本文件');
    return;
  }
  if (composerUploading.value) {
    ElMessage.warning('已有文件在解析中，稍等片刻再传');
    return;
  }
  await submitComposerUpload(file);
}

async function submitComposerUpload(file: File): Promise<void> {
  composerUploading.value = true;
  composerUploadChip.value = { kind: 'parsing', name: file.name, text: '解析中…' };
  try {
    const parsed = await uploadChatAttachment(file, authContext());
    composerUploadChip.value = {
      kind: 'ready',
      name: parsed.filename,
      attachmentId: parsed.attachmentId,
      text: `已就绪 · ${parsed.chars} 字${parsed.truncated ? '（超长已截断）' : ''}，随下条消息起生效`,
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : '附件解析失败';
    ElMessage.error(message);
    composerUploadChip.value = null;
  } finally {
    composerUploading.value = false;
  }
}

export function dismissComposerUploadChip(): void {
  composerUploadChip.value = null;
}
