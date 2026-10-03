// 全局 UI 状态（叶模块）：界面偏好 + 侧栏/弹层开关。从 App.vue 单体拆出，
// 字段与原定义逐字一致。
// ⚠ 本模块只放状态；activateView 在 useViewActivation——它要反向触发各页签
// 的懒加载，放进本模块会与 knowledge 等功能模块成环。
import { computed, ref, watch } from 'vue';

import type { ConsoleView } from '../types/console';
import { persistState, readBootstrap, registerPersistSlice } from './persistence';

const bootstrap = readBootstrap();

export const darkMode = ref(Boolean(bootstrap.darkMode));
export const activeView = ref<ConsoleView>(
  ['evaluation', 'knowledge', 'admin', 'usage'].includes(bootstrap.activeView as string)
    ? (bootstrap.activeView as ConsoleView)
    : 'chat',
);
// 会话栏只在聊天页且未折叠时占一列；其它页签由内容区占满整行
export const sessionColVisible = computed(
  () => activeView.value === 'chat' && !sessionColCollapsed.value,
);
export const sessionSearch = ref((bootstrap.sessionSearch as string | undefined) ?? '');
export const workspaceFilter = ref((bootstrap.workspaceFilter as string | undefined) ?? 'all');
export const showArchivedSessions = ref(Boolean(bootstrap.showArchivedSessions));
// 会话栏折叠状态（仅聊天页生效），随其它界面偏好一起持久化
export const sessionColCollapsed = ref(Boolean(bootstrap.sessionColCollapsed));
// 鉴权与模型弹窗 / 分支树抽屉：临时 UI 状态，不持久化
export const opsDialogVisible = ref(false);
export const branchDrawerVisible = ref(false);

registerPersistSlice(() => ({
  darkMode: darkMode.value,
  activeView: activeView.value,
  sessionSearch: sessionSearch.value,
  workspaceFilter: workspaceFilter.value,
  showArchivedSessions: showArchivedSessions.value,
  sessionColCollapsed: sessionColCollapsed.value,
}));

/** 全局副作用（darkMode → html.dark class + 持久化），由 useAppBootstrap 恰好调用一次。 */
export function registerGlobalUiEffects(): void {
  watch(
    darkMode,
    () => {
      document.documentElement.classList.toggle('dark', darkMode.value);
      persistState();
    },
    { immediate: true },
  );
}
