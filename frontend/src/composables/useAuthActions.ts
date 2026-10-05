// 认证动作：API-Key 换 JWT、JWT 刷新、密码登录/注册、退出。
// 从 App.vue 单体拆出，函数体逐字一致。依赖 useAuthState（状态）、
// useSessions（登录后拉云端会话）、useUsage（登出清空用量）、useGlobalUi（回聊天页）。
// registerAuthEffects() 只允许被调用一次（原 App.vue 的 auth watch 原文搬入）。
import { ElMessage } from 'element-plus';

import { exchangeApiKey, loginWithPassword, refreshJwt, registerUser, setUnauthorizedHandler } from '../api/client';
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
  tokenExpiresAt,
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

/** 记录访问令牌到期时刻：静默续期定时器据此判断临期；响应缺字段时记 0（不做定时续期，401 兜底）。 */
function applyTokenExpiry(auth: { expiresInSeconds?: number }): void {
  tokenExpiresAt.value = auth.expiresInSeconds ? Date.now() + auth.expiresInSeconds * 1000 : 0;
}

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
    applyTokenExpiry(auth);
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

/** 续期令牌：silent=true 为后台静默续期（成败都不弹提示）；手动按钮走默认 false。 */
export async function handleRefresh(silent = false): Promise<void> {
  if (!refreshToken.value) {
    if (!silent) {
      ElMessage.warning('当前没有 refresh token');
    }
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
    applyTokenExpiry(auth);
    if (!silent) {
      ElMessage.success('令牌已刷新');
    }
    persistState();
    await refreshCostSummary();
  } catch (error) {
    // 静默续期失败不打扰用户：访问令牌还能撑到过期，届时由 401 兜底弹回登录页
    if (!silent) {
      const message = error instanceof Error ? error.message : '登录刷新失败';
      ElMessage.error(message);
    }
  } finally {
    refreshing.value = false;
  }
}

/** 令牌临期（剩不足 3 分钟）静默续期；定时器 + 页面切回前台双触发，见 registerAuthEffects。 */
export function maybeRefreshToken(): void {
  if (!token.value || !refreshToken.value || refreshing.value || !tokenExpiresAt.value) {
    return;
  }
  if (tokenExpiresAt.value - Date.now() > 3 * 60 * 1000) {
    return;
  }
  void handleRefresh(true);
}

export function clearAuth(): void {
  token.value = '';
  refreshToken.value = '';
  role.value = '';
  apiKeyInput.value = '';
  tenantInput.value = '';
  tokenExpiresAt.value = 0;
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
    applyTokenExpiry(auth);
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

/** 认证副作用（凭据变化 → 持久化 + 用量刷新/清空 + 401 兜底 + 静默续期），只允许被调用一次。 */
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

  // 任意接口 401（令牌过期/无效）→ 清凭据，门闩（canUseRemoteSync 变 false）整页弹回登录。
  // 先到的 401 已把凭据清空，并发 401 再进来时直接短路，不会重复弹提示。
  setUnauthorizedHandler(() => {
    if (!token.value && !apiKeyInput.value.trim()) {
      return;
    }
    clearAuth();
    ElMessage.warning('登录已过期，请重新登录');
  });

  // 静默续期：每分钟查一次临期；页面从后台切回（电脑休眠唤醒）立刻补查；
  // 注册时也补查一次（刷新页面进来就续，不用等第一个 60 秒）
  window.setInterval(maybeRefreshToken, 60 * 1000);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) {
      maybeRefreshToken();
    }
  });
  maybeRefreshToken();
}
