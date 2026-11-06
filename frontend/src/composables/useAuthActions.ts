// 认证动作：会话登录/注册/登出/身份探测。
// 人类通道 = 服务端会话（Session + HttpOnly Cookie，2026-11-05 起）：登录后凭据
// 不再进 localStorage，前端只持有非敏感的 role/tenantId 供 UI 显隐，登录态以
// /auth/me 启动探测为准。程序化通道（JWT / API Key）与前端无关。
// registerAuthEffects() 只允许被调用一次（原 App.vue 的 auth watch 原文搬入）。
import { ElMessage } from 'element-plus';

import { authLogout, authMe, loginWithPassword, registerUser, setUnauthorizedHandler } from '../api/client';
import { persistState } from './persistence';
import {
  authLoading,
  authMode,
  authPassword,
  authUsername,
  canUseRemoteSync,
  loggedIn,
  role,
  tenantInput,
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

export function clearAuth(): void {
  loggedIn.value = false;
  role.value = '';
  tenantInput.value = '';
  costSummary.value = null;
  // 退出时离开管理员视图，避免下一个普通用户登录后残留无权限的页面
  activeView.value = 'chat';
  persistState();
}

/** 退出登录：服务端吊销会话（幂等）+ 清本地态，回到登录页。 */
export async function logout(): Promise<void> {
  try {
    await authLogout();
  } catch {
    // 服务端不可达也要完成本地登出（Cookie 过期由浏览器 Max-Age 兜底）
  }
  clearAuth();
  ElMessage.success('已退出登录');
}

/** 密码登录 / 注册共用：成功即建立服务端会话（HttpOnly Cookie）并进入控制台。 */
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
    loggedIn.value = true;
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

/** 启动探测：/auth/me 校正登录态与角色（Cookie 有效则恢复，失效/未登录回登录页）。静默，不打扰。 */
async function verifySession(): Promise<void> {
  try {
    const identity = await authMe();
    if (!identity.ok) {
      clearAuth();
      return;
    }
    loggedIn.value = true;
    role.value = identity.role ?? '';
    if (identity.tenantId) {
      tenantInput.value = identity.tenantId;
    }
    persistState();
  } catch {
    clearAuth();
  }
}

/** 认证副作用（凭据变化 → 持久化 + 用量刷新/清空 + 401 兜底 + 启动身份探测），只允许被调用一次。 */
export function registerAuthEffects(): void {
  watch([tenantInput, loggedIn, role], () => {
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

  // 任意接口 401（会话过期/被吊销）→ 清本地态，门闩（canUseRemoteSync 变 false）整页弹回登录。
  // 先到的 401 已清态，并发 401 再进来时直接短路，不会重复弹提示。
  setUnauthorizedHandler(() => {
    if (!loggedIn.value) {
      return;
    }
    clearAuth();
    ElMessage.warning('登录已过期，请重新登录');
  });

  // 启动探测一次：Cookie 是否仍有效（服务端吊销/过期/换机都能被这一下校正）
  void verifySession();
}
