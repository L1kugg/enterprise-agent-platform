// 页签激活：切换视图 + 各功能页首次进入时的懒加载。
// 从 App.vue 单体拆出，函数体逐字一致。不放进 useGlobalUi——它要反向调用
// evaluation/knowledge/admin/usage 的加载函数，放那里会与这些模块成环
// （knowledge 轮询要读 activeView）。
import { persistState } from './persistence';
import { activeView } from './useGlobalUi';
import { isAdmin } from './useAuthState';
import type { ConsoleView } from '../types/console';
import { evalDatasets, evalLoading, loadEvalDatasets } from './useEvaluation';
import {
  knowledgeLoading,
  loadKnowledgeDocuments,
  loadKnowledgeJobs,
} from './useKnowledge';
import { loadAdminDocuments } from './useAdmin';
import { loadPlatformOverview } from './usePlatformAssets';
import { loadUsageTrend } from './useUsage';

export function activateView(view: ConsoleView): void {
  activeView.value = view;
  persistState();
  if (view === 'evaluation' && evalDatasets.value.length === 0 && !evalLoading.value) {
    void loadEvalDatasets();
  }
  if (view === 'knowledge' && !knowledgeLoading.value) {
    void loadKnowledgeJobs();
    void loadKnowledgeDocuments();
  }
  if (view === 'platform' && isAdmin.value) {
    void loadPlatformOverview();
  }
  if (view === 'admin' && isAdmin.value) {
    void loadAdminDocuments();
  }
  if (view === 'usage') {
    void loadUsageTrend();
  }
}
