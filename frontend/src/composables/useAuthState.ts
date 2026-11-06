// 认证状态（叶模块）：从 App.vue 单体拆出。
// 只含状态与派生值；登录/登出/身份探测等动作在 useAuthActions（动作会反向调用
// sessions/usage 等模块，与状态层分开避免成环）。
// 人的登录走服务端会话（Session + HttpOnly Cookie）：前端不持有任何凭据，
// 只持久化非敏感的 role/tenantId 供 UI 显隐，登录态以启动时 /auth/me 探测为准。
// API Key 是程序化接入通道（X-API-Key 直调后端），不出现在前端。
import { computed, ref } from 'vue';

import { readBootstrap, registerPersistSlice } from './persistence';

const bootstrap = readBootstrap();

export const tenantInput = ref((bootstrap.tenantId as string | undefined) ?? '');
// 登录用户的首个角色（ADMIN/USER），控制管理员 UI 显隐
export const role = ref((bootstrap.role as string | undefined) ?? '');
// 登录态：先按上次的 role 乐观恢复（避免刷新闪登录页），启动 /auth/me 探测后校正
export const loggedIn = ref(Boolean(bootstrap.role));
export const authMode = ref<'login' | 'register'>('login');
export const authUsername = ref('');
export const authPassword = ref('');
// 登录表单"记住密码"勾选态（AuthGate 预填时按已存密码初始化，handlePasswordAuth 据此存/清）
export const rememberLogin = ref(false);
export const isAdmin = computed(() => role.value === 'ADMIN');
export const authLoading = ref(false);
export const canUseRemoteSync = computed(() => loggedIn.value);

registerPersistSlice(() => ({
  tenantId: tenantInput.value,
  role: role.value,
}));

// 登录态字段变化 → 持久化 + 用量刷新的 watch 在 registerAuthEffects（useAuthActions，
// 3.13 步）；这里只放状态与切片，保持依赖单向（authState 不能反向依赖 usage）。

/** 请求上下文：会话凭据在 HttpOnly Cookie 里由浏览器自动携带，这里只剩租户提示。 */
export function authContext() {
  return {
    tenantId: tenantInput.value || undefined,
  };
}
