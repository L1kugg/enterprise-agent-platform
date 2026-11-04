// 展示格式化纯函数：从 App.vue 单体拆出，实现逐字一致。
import type { IngestionJobStatus } from '../types/react';

export function formatTime(value: number): string {
  return new Date(value).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * 相对时间（会话列表等扫读场景）：刚刚 / N 分钟前 / 当天只看时分 /
 * 昨天 HH:mm / N 天前 / 超过一周落到"M月D日"。时钟轻微漂移（未来时间）按"刚刚"处理。
 */
export function formatRelativeTime(value: number): string {
  const now = Date.now();
  const diff = now - value;
  if (diff < 60_000) {
    return '刚刚';
  }
  const shown = new Date(value);
  const hhmm = `${String(shown.getHours()).padStart(2, '0')}:${String(shown.getMinutes()).padStart(2, '0')}`;
  if (diff < 3_600_000) {
    return `${Math.floor(diff / 60_000)} 分钟前`;
  }
  if (shown.toDateString() === new Date(now).toDateString()) {
    return hhmm;
  }
  if (shown.toDateString() === new Date(now - 86_400_000).toDateString()) {
    return `昨天 ${hhmm}`;
  }
  if (diff < 7 * 86_400_000) {
    return `${Math.floor(diff / 86_400_000)} 天前`;
  }
  return `${shown.getMonth() + 1}月${shown.getDate()}日`;
}

export function shortId(id: string): string {
  return id.slice(0, 10);
}

export function isAuthError(error: unknown): boolean {
  return error instanceof Error && error.message.includes('错误码 401');
}

export function statusTagType(
  status: IngestionJobStatus,
): 'success' | 'danger' | 'warning' | 'info' | 'primary' {
  switch (status) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'RETRY':
      return 'warning';
    case 'RUNNING':
      return 'primary';
    default:
      return 'info';
  }
}

export function formatJobTime(value: string | undefined): string {
  if (!value) {
    return '-';
  }
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function formatFileSize(size: number | null | undefined): string {
  if (size === null || size === undefined) {
    return '-';
  }
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}
