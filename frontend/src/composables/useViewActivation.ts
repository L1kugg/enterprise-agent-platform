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
  knowledgeCatalog,
  knowledgeLoading,
  loadKnowledgeCatalog,
  loadKnowledgeDocuments,
  loadKnowledgeJobs,
} from './useKnowledge';
import { loadAdminDocuments } from './useAdmin';
import { loadPlatformOverview } from './usePlatformAssets';
import { loadUsageTrend } from './useUsage';
import { confirmLeaveUnsaved } from './useUnsavedGuard';

export async function activateView(view: ConsoleView): Promise<void> {
  // 未保存守卫：各编辑页（Studio）注册脏检查，有修改时切页签先确认（取消则留在本页）
  if (view !== activeView.value && !(await confirmLeaveUnsaved('切换页签将丢失当前未保存的修改，确定离开？'))) {
    return;
  }
  activeView.value = view;
  persistState();
  if (view === 'evaluation' && evalDatasets.value.length === 0 && !evalLoading.value) {
    void loadEvalDatasets();
  }
  if (view === 'knowledge' && !knowledgeLoading.value) {
    void loadKnowledgeJobs();
    void loadKnowledgeDocuments();
  }
  // 知识库目录（FR-R5-01）：上传归属/按库过滤/试搜圈库的选择器数据源，首次进入加载
  if (view === 'knowledge' && knowledgeCatalog.value.length === 0) {
    void loadKnowledgeCatalog();
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
