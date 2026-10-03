// 认证动作：API-Key 换 JWT、JWT 刷新、密码登录/注册、退出。
// 从 App.vue 单体拆出，函数体逐字一致。依赖 useAuthState（状态）、
// useSessions（登录后拉云端会话）、useUsage（登出清空用量）、useGlobalUi（回聊天页）。
// registerAuthEffects() 只允许被调用一次（原 App.vue 的 auth watch 原文搬入）。
import { ElMessage } from 'element-plus';

import { exchangeApiKey, loginWithPassword, refreshJwt, registerUser } from '../api/client';
import { persistState } from './persistence';
import {
  apiKeyInput,
  authLoading,
  authMode,
  authPassword,
  authUsername,
  canUseRemoteSync,
  refreshToken,
  refreshing,
  role,
  tenantInput,
  token,
} from './useAuthState';
import { loadSessionsFromCloud } from './useSessions';
import {
  costSummary,
  loadUsageTrend,
  refreshCostSummary,
  usageNeedsAuth,
  usagePoints,
} from './useUsage';
import { activeView } from './useGlobalUi';
import { watch } from 'vue';

export async function handleLogin(): Promise<void> {
  if (!apiKeyInput.value.trim()) {
    ElMessage.warning('请先输入 API Key');
    return;
  }

  authLoading.value = true;
  try {
    const auth = await exchangeApiKey(
      apiKeyInput.value.trim(),
      tenantInput.value.trim() || undefined,
    );
    token.value = auth.token ?? '';
    refreshToken.value = auth.refreshToken ?? '';
    role.value = auth.role ?? '';
    if (auth.tenantId) {
      tenantInput.value = auth.tenantId;
    }
    ElMessage.success('JWT 获取成功');
    persistState();
    await loadSessionsFromCloud();
  } catch (error) {
    const message = error instanceof Error ? error.message : 'API Key 登录失败';
    ElMessage.error(message);
  } finally {
    authLoading.value = false;
  }
}

export async function handleRefresh(): Promise<void> {
  if (!refreshToken.value) {
    ElMessage.warning('当前没有 refresh token');
    return;
  }

  refreshing.value = true;
  try {
    const auth = await refreshJwt(refreshToken.value);
    token.value = auth.token ?? token.value;
    refreshToken.value = auth.refreshToken ?? refreshToken.value;
    role.value = auth.role ?? role.value;
    if (auth.tenantId) {
      tenantInput.value = auth.tenantId;
    }
    ElMessage.success('令牌已刷新');
    persistState();
    await refreshCostSummary();
  } catch (error) {
    const message = error instanceof Error ? error.message : '登录刷新失败';
    ElMessage.error(message);
  } finally {
    refreshing.value = false;
  }
}

export function clearAuth(): void {
  token.value = '';
  refreshToken.value = '';
  role.value = '';
  apiKeyInput.value = '';
  tenantInput.value = '';
  costSummary.value = null;
  // 退出时离开管理员视图，避免下一个普通用户登录后残留无权限的页面
  activeView.value = 'chat';
  persistState();
}

/** 退出登录：清干净全部凭据回到登录页（门闩由 canUseRemoteSync 驱动）。 */
export function logout(): void {
  clearAuth();
  ElMessage.success('已退出登录');
}

/** 密码登录 / 注册共用：成功即拿到 JWT 并进入控制台。 */
export async function handlePasswordAuth(): Promise<void> {
  if (!authUsername.value.trim() || !authPassword.value) {
    ElMessage.warning('请输入用户名和密码');
    return;
  }
  authLoading.value = true;
  try {
    const credentials = {
      username: authUsername.value.trim(),
      password: authPassword.value,
    };
    const auth =
      authMode.value === 'login'
        ? await loginWithPassword(credentials.username, credentials.password)
        : await registerUser(credentials.username, credentials.password);
    token.value = auth.token ?? '';
    refreshToken.value = auth.refreshToken ?? '';
    role.value = auth.role ?? 'USER';
    if (auth.tenantId) {
      tenantInput.value = auth.tenantId;
    }
    authPassword.value = '';
    ElMessage.success(authMode.value === 'login' ? '登录成功' : '注册成功，已自动登录');
    persistState();
    await loadSessionsFromCloud();
  } catch (error) {
    const message = error instanceof Error ? error.message : '登录失败';
    ElMessage.error(message);
  } finally {
    authLoading.value = false;
  }
}

/** 认证副作用（凭据变化 → 持久化 + 用量刷新/清空），只允许被调用一次。 */
export function registerAuthEffects(): void {
  watch([apiKeyInput, tenantInput, token, refreshToken, role], () => {
    persistState();
    if (canUseRemoteSync.value) {
      void refreshCostSummary();
      if (activeView.value === 'usage') {
        void loadUsageTrend(true);
      }
    } else {
      costSummary.value = null;
      usageNeedsAuth.value = true;
      usagePoints.value = [];
    }
  });
}
