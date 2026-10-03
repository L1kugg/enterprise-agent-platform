// 展示格式化纯函数：从 App.vue 单体拆出，实现逐字一致。
import type { IngestionJobStatus } from '../types/react';

export function formatTime(value: number): string {
  return new Date(value).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function shortId(id: string): string {
  return id.slice(0, 10);
}

export function isAuthError(error: unknown): boolean {
  return error instanceof Error && error.message.startsWith('HTTP 401');
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
