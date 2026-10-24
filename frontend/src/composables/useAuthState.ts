// 认证状态（叶模块）：从 App.vue 单体拆出，字段与原定义逐字一致。
// 只含状态与派生值；登录/刷新/登出等动作在 useAuthActions（动作会反向调用
// sessions/usage 等模块，与状态层分开避免成环）。
// 人的登录只有密码一条路；API Key 是程序化接入通道（X-API-Key 直调后端），
// 不再出现在前端登录界面（2026-10-24 移除）。
import { computed, ref } from 'vue';

import { readBootstrap, registerPersistSlice } from './persistence';

const bootstrap = readBootstrap();

export const tenantInput = ref((bootstrap.tenantId as string | undefined) ?? '');
export const token = ref((bootstrap.token as string | undefined) ?? '');
export const refreshToken = ref((bootstrap.refreshToken as string | undefined) ?? '');
/** 访问令牌到期时刻（毫秒时间戳，0 = 未知）：静默续期定时器据此判断临期。 */
export const tokenExpiresAt = ref((bootstrap.tokenExpiresAt as number | undefined) ?? 0);
// 登录用户的首个角色（ADMIN/USER），控制管理员 UI 显隐
export const role = ref((bootstrap.role as string | undefined) ?? '');
export const authMode = ref<'login' | 'register'>('login');
export const authUsername = ref('');
export const authPassword = ref('');
export const isAdmin = computed(() => role.value === 'ADMIN');
export const authLoading = ref(false);
export const refreshing = ref(false);
export const canUseRemoteSync = computed(() => Boolean(token.value));

registerPersistSlice(() => ({
  tenantId: tenantInput.value,
  token: token.value,
  refreshToken: refreshToken.value,
  role: role.value,
  tokenExpiresAt: tokenExpiresAt.value,
}));

// 登录态字段变化 → 持久化 + 用量刷新的 watch 在 registerAuthEffects（useAuthActions，
// 3.13 步）；这里只放状态与切片，保持依赖单向（authState 不能反向依赖 usage）。

export function authContext() {
  return {
    token: token.value || undefined,
    tenantId: tenantInput.value || undefined,
  };
}
