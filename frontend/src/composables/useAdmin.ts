// 管理员文档总览（跨租户）：从 App.vue 单体拆出，字段与原定义逐字一致。
// 仅 ADMIN 角色可用；未登录/过期时页面内提示，不弹报错。
import { ref } from 'vue';

import { ElMessage, ElMessageBox } from 'element-plus';

import { deleteAdminDocument, listAdminDocuments } from '../api/client';
import type { AdminDocumentSummary } from '../types/react';
import { isAuthError } from '../utils/format';
import { apiKeyInput, authContext, isAdmin, token } from './useAuthState';

export const adminDocs = ref<AdminDocumentSummary[]>([]);
export const adminDocsLoading = ref(false);
export const adminDocsTotal = ref(0);
export const adminDocsPage = ref(1);
export const adminDocsPageSize = ref(20);
export const adminDocsSearch = ref('');
export const adminNeedsAuth = ref(false);

export async function loadAdminDocuments(silent = false): Promise<void> {
  if (!isAdmin.value) {
    return;
  }
  if (!token.value && !apiKeyInput.value) {
    // 压根没登录过，别去打接口，页面里提示即可
    adminNeedsAuth.value = true;
    adminDocs.value = [];
    return;
  }
  if (!silent) {
    adminDocsLoading.value = true;
  }
  try {
    const result = await listAdminDocuments(authContext(), {
      page: adminDocsPage.value,
      pageSize: adminDocsPageSize.value,
      search: adminDocsSearch.value.trim() || undefined,
    });
    adminDocs.value = result.items;
    adminDocsTotal.value = result.total;
    adminNeedsAuth.value = false;
  } catch (error) {
    if (isAuthError(error)) {
      // 登录过期（JWT 两小时失效），页面里提示，不弹报错
      adminNeedsAuth.value = true;
      adminDocs.value = [];
    } else if (!silent) {
      const message = error instanceof Error ? error.message : '文档总览加载失败';
      ElMessage.error(message);
    }
  } finally {
    if (!silent) {
      adminDocsLoading.value = false;
    }
  }
}

export function handleAdminSearch(): void {
  adminDocsPage.value = 1;
  void loadAdminDocuments();
}

export function handleAdminPageChange(page: number): void {
  adminDocsPage.value = page;
  void loadAdminDocuments();
}

export async function removeAdminDocument(row: AdminDocumentSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除租户 ${row.tenantId} 的「${row.sourceName}」？将同时清掉它的向量切片和原文件，不可恢复。`,
      '删除文档',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return; // 用户点了取消
  }
  try {
    const msg = await deleteAdminDocument(row.tenantId, row.chatId, authContext());
    ElMessage.success(msg || '已删除');
    // 当前页删得只剩这一条且不是第一页时回退一页，避免停在空页上
    if (adminDocs.value.length === 1 && adminDocsPage.value > 1) {
      adminDocsPage.value -= 1;
    }
    await loadAdminDocuments(true);
  } catch (error) {
    const message = error instanceof Error ? error.message : '删除失败';
    ElMessage.error(message);
  }
}
