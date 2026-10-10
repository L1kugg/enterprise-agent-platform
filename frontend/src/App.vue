<template>
  <AuthGate />

  <div
    v-if="canUseRemoteSync"
    class="app-shell"
    :class="{ 'shell-with-sessions': sessionColVisible }"
  >
    <AppSidebar />

    <SessionSidebar />

    <main class="workspace">
      <WorkspaceHeader />

      <ChatView v-if="activeView === 'chat'" />

      <KnowledgeView v-else-if="activeView === 'knowledge'" />

      <PlatformView v-else-if="isAdmin && activeView === 'platform'" />

      <section v-else-if="activeView === 'platform'" class="no-permission-panel" aria-label="需要管理员身份">
        <el-alert
          type="warning"
          show-icon
          :closable="false"
          title="平台配置需要管理员身份"
          description="当前登录账号不是 ADMIN，无法访问平台总览、智能体、工作流、工具、安全防护、模型服务、数据库与系统管理。请使用管理员账号登录（右侧设置可退出后重新登录）。"
        />
      </section>

      <AdminView v-else-if="isAdmin && activeView === 'admin'" />

      <UsageView v-else-if="activeView === 'usage'" />

      <EvaluationView v-else />

    </main>

    <SettingsDialog />

    <BranchDrawer />

  </div>
</template>

<script setup lang="ts">
import AuthGate from './components/AuthGate.vue';
import BranchDrawer from './components/BranchDrawer.vue';
import ChatView from './components/chat/ChatView.vue';
import AppSidebar from './components/AppSidebar.vue';
import SessionSidebar from './components/SessionSidebar.vue';
import UsageView from './components/usage/UsageView.vue';
import AdminView from './components/admin/AdminView.vue';
import EvaluationView from './components/evaluation/EvaluationView.vue';
import KnowledgeView from './components/knowledge/KnowledgeView.vue';
import PlatformView from './components/platform/PlatformView.vue';
import WorkspaceHeader from './components/WorkspaceHeader.vue';
import SettingsDialog from './components/SettingsDialog.vue';
import { onBeforeUnmount, onMounted } from 'vue';
import { canUseRemoteSync, isAdmin } from './composables/useAuthState';
import {
  activeView,
  registerGlobalUiEffects,
  sessionColVisible,
} from './composables/useGlobalUi';
import { clearStreamResetTimer } from './composables/useChatState';
import {
  createMessageResizeObserver,
  hydrating,
  registerViewportEffects,
  scrollToBottom,
  teardownViewport,
  updateViewport,
} from './composables/useChatViewport';
import {
  loadUsageTrend,
  refreshCostSummary,
  registerUsageEffects,
} from './composables/useUsage';
import { loadEvalDatasets } from './composables/useEvaluation';
import {
  loadKnowledgeDocuments,
  loadKnowledgeJobs,
  stopKnowledgePolling,
} from './composables/useKnowledge';
import { loadPlatformOverview } from './composables/usePlatformAssets';
import {
  dismissComposerUploadChip,
  stopComposerUploadPolling,
} from './composables/useComposerUpload';
import {
  activeSessionId,
  initChatFromActiveSession,
  loadSession,
  registerSessionEffects,
} from './composables/useSessions';
import { stopResearchPolling } from './composables/useResearch';
import { registerAuthEffects } from './composables/useAuthActions';

// 聊天状态初始化自当前会话分支：setup 第一条语句（原 activeBranch 依赖初始化，
// 逐字等价；onMounted 的 loadSession 随后会重读一遍）
initChatFromActiveSession();

registerUsageEffects();

registerGlobalUiEffects();

registerSessionEffects();

registerAuthEffects();

registerViewportEffects();

onMounted(() => {
  loadSession(activeSessionId.value);
  updateViewport();

  createMessageResizeObserver();

  window.addEventListener('resize', updateViewport);
  window.setTimeout(() => {
    hydrating.value = false;
    void scrollToBottom(true);
  }, 220);

  if (canUseRemoteSync.value) {
    void refreshCostSummary();
  }

  if (activeView.value === 'evaluation') {
    void loadEvalDatasets();
  }

  if (activeView.value === 'platform' && isAdmin.value) {
    void loadPlatformOverview();
  }

  if (activeView.value === 'knowledge') {
    void loadKnowledgeJobs();
    void loadKnowledgeDocuments();
  }

  if (activeView.value === 'usage') {
    void loadUsageTrend();
  }

  void scrollToBottom(true);
});

onBeforeUnmount(() => {
  stopKnowledgePolling();
  stopResearchPolling();
  stopComposerUploadPolling();
  dismissComposerUploadChip();

  teardownViewport();

  clearStreamResetTimer();
});
</script>

<style scoped>
.app-shell {
  /* 定高框架：页面本体永远等于一屏。此前用 min-height，侧栏内容一长
     就把整页撑高，聊天区滚到底再往下滚，整页跟着滚、输入框下方露出大片空白。 */
  height: 100vh;
  display: grid;
  grid-template-columns: 236px minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  overflow: hidden;
  color: var(--ui-text);
}

/* 聊天页且未折叠时，会话栏占中间一列；其它页签内容区占满整行 */
.app-shell.shell-with-sessions {
  grid-template-columns: 236px 260px minmax(0, 1fr);
}

.no-permission-panel {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 32px;
}

.no-permission-panel :deep(.el-alert) {
  max-width: 560px;
}

.workspace {
  /* 固定一屏高、内部滚动：所有页面（flex:1 + min-height:0 链、height:100% 表格、
     sticky 输入框）都按"定高框架"设计，此前 min-height 让高度链断裂，
     内容一长就把输入框/分页条顶出屏幕外 */
  height: 100vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

@media (max-width: 1160px) {
  .app-shell.shell-with-sessions {
    grid-template-columns: 210px 224px minmax(0, 1fr);
  }
}

@media (max-width: 980px) {
  .app-shell,
  .app-shell.shell-with-sessions {
    grid-template-columns: 76px minmax(0, 1fr);
  }
}
</style>
