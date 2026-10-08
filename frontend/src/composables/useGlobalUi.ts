// 全局 UI 状态（叶模块）：界面偏好 + 侧栏/弹层开关。从 App.vue 单体拆出，
// 字段与原定义逐字一致。
// ⚠ 本模块只放状态；activateView 在 useViewActivation——它要反向触发各页签
// 的懒加载，放进本模块会与 knowledge 等功能模块成环。
import { computed, ref, watch } from 'vue';

import type { ConsoleView } from '../types/console';
import { persistState, readBootstrap, registerPersistSlice } from './persistence';

const bootstrap = readBootstrap();

// 全部合法视图（同时也是地址栏路径名：/chat /evaluation /knowledge /admin /usage）。
// 视图与地址栏的单向镜像同步不引 vue-router：activeView 仍是唯一状态源，
// watch 里 pushState、popstate 里回写；nginx 的 try_files 已把任意路径回退到
// index.html，刷新/直达/分享链接天然可用。
const CONSOLE_VIEWS: readonly ConsoleView[] = ['chat', 'evaluation', 'knowledge', 'admin', 'usage'];

function isConsoleView(value: unknown): value is ConsoleView {
  return CONSOLE_VIEWS.includes(value as ConsoleView);
}

/** 路径 → 视图：首段是合法视图名则原样返回；根路径返回 rootFallback；未知路径返回 null。 */
function viewFromPath(pathname: string, rootFallback: ConsoleView | null): ConsoleView | null {
  const first = pathname.split('/').filter(Boolean)[0];
  if (!first) {
    return rootFallback;
  }
  return isConsoleView(first) ? first : null;
}

export const darkMode = ref(Boolean(bootstrap.darkMode));
// 初始视图：地址栏带视图路径（直达/刷新/分享链接）优先，根路径沿用上次的本地偏好
export const activeView = ref<ConsoleView>(
  viewFromPath(window.location.pathname, null) ??
    (isConsoleView(bootstrap.activeView) ? bootstrap.activeView : 'chat'),
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

/** 全局副作用（darkMode → html.dark class + 持久化；activeView → 地址栏同步），由 useAppBootstrap 恰好调用一次。 */
export function registerGlobalUiEffects(): void {
  watch(
    darkMode,
    () => {
      document.documentElement.classList.toggle('dark', darkMode.value);
      persistState();
    },
    { immediate: true },
  );

  // 切页签把路径写进地址栏（浏览器前进/后退可在页签间回退）。
  // popstate 场景 URL 已是目标视图，下面的 pathname 比对不成立，不会重复 pushState。
  watch(activeView, (view) => {
    if (window.location.pathname !== `/${view}`) {
      window.history.pushState(null, '', `/${view}`);
    }
  });
  window.addEventListener('popstate', () => {
    // 前进/后退到根路径按 chat 处理（根路径本就是聊天页的别名）；未知路径不动视图
    const view = viewFromPath(window.location.pathname, 'chat');
    if (view !== null && view !== activeView.value) {
      activeView.value = view;
    }
  });
}
