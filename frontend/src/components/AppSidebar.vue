<template>
  <aside class="app-sidebar">
    <div class="brand">
      <div class="brand-mark" aria-hidden="true">AI</div>
      <div>
        <strong>Enterprise Agent Platform</strong>
        <small>企业智能体控制台</small>
      </div>
    </div>

    <div class="current-module" aria-label="当前模块">
      <el-tag size="small" effect="plain">{{ currentModuleLabel }}</el-tag>
      <button
        v-if="activeView === 'chat' && sessionColCollapsed"
        type="button"
        class="expand-session-btn"
        @click="sessionColCollapsed = false"
      >
        展开会话栏
      </button>
    </div>

    <nav class="sidebar-scroll" aria-label="主导航">
      <p class="nav-group">工作空间</p>
      <button
        v-if="isAdmin"
        type="button"
        :class="{ active: activeView === 'platform' && platformSection === 'overview' }"
        @click="goPlatform('overview')"
      >
        <el-icon><Odometer /></el-icon><span>平台总览</span>
      </button>
      <button
        v-if="isAdmin"
        type="button"
        :class="{ active: activeView === 'platform' && platformSection === 'agents' }"
        @click="goPlatform('agents')"
      >
        <el-icon><Avatar /></el-icon><span>智能体</span>
      </button>
      <button
        v-if="isAdmin"
        type="button"
        :class="{ active: activeView === 'platform' && platformSection === 'workflows' }"
        @click="goPlatform('workflows')"
      >
        <el-icon><Share /></el-icon><span>工作流</span>
      </button>
      <button
        v-if="isAdmin"
        type="button"
        :class="{ active: activeView === 'platform' && platformSection === 'tools' }"
        @click="goPlatform('tools')"
      >
        <el-icon><SetUp /></el-icon><span>工具管理</span>
      </button>
      <button
        type="button"
        :class="{ active: activeView === 'knowledge' }"
        @click="activateView('knowledge')"
      >
        <el-icon><FolderOpened /></el-icon><span>知识库</span>
      </button>

      <p class="nav-group">平台配置</p>
      <button
        v-if="isAdmin"
        type="button"
        :class="{ active: activeView === 'platform' && platformSection === 'safety-guards' }"
        @click="goPlatform('safety-guards')"
      >
        <el-icon><Lock /></el-icon><span>安全防护</span>
      </button>
      <button
        v-if="isAdmin"
        type="button"
        :class="{ active: activeView === 'platform' && platformSection === 'model-services' }"
        @click="goPlatform('model-services')"
      >
        <el-icon><Cpu /></el-icon><span>模型服务</span>
      </button>
      <button
        v-if="isAdmin"
        type="button"
        :class="{ active: activeView === 'platform' && platformSection === 'databases' }"
        @click="goPlatform('databases')"
      >
        <el-icon><Coin /></el-icon><span>数据库管理</span>
      </button>
      <button
        v-if="isAdmin"
        type="button"
        :class="{ active: activeView === 'platform' && platformSection === 'system' }"
        @click="goPlatform('system')"
      >
        <el-icon><UserFilled /></el-icon><span>系统管理</span>
      </button>

      <p class="nav-group">运营分析</p>
      <button
        type="button"
        :class="{ active: activeView === 'chat' }"
        @click="activateView('chat')"
      >
        <el-icon><ChatDotRound /></el-icon><span>对话工作台</span>
      </button>
      <button
        type="button"
        :class="{ active: activeView === 'evaluation' }"
        @click="activateView('evaluation')"
      >
        <el-icon><DataAnalysis /></el-icon><span>评测中心</span>
      </button>
      <button
        type="button"
        :class="{ active: activeView === 'usage' }"
        @click="activateView('usage')"
      >
        <el-icon><TrendCharts /></el-icon><span>用量统计</span>
      </button>
      <button
        v-if="isAdmin"
        type="button"
        :class="{ active: activeView === 'admin' }"
        @click="activateView('admin')"
      >
        <el-icon><Notebook /></el-icon><span>文档总览</span>
      </button>
    </nav>

    <div class="sidebar-foot">
      <div class="foot-meta">
        <el-tag size="small" effect="plain">{{ role || 'GUEST' }}</el-tag>
        <span class="foot-tenant" :title="tenantInput || 'public'">{{
          tenantInput || 'public'
        }}</span>
      </div>
      <div class="foot-buttons">
        <button type="button" class="foot-button" @click="opsDialogVisible = true">
          <el-icon><Setting /></el-icon><span>设置</span>
        </button>
        <button type="button" class="foot-button" @click="darkMode = !darkMode">
          <el-icon><Moon v-if="!darkMode" /><Sunny v-else /></el-icon
          ><span>{{ darkMode ? '浅色' : '深色' }}</span>
        </button>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import {
  Avatar,
  ChatDotRound,
  Coin,
  Cpu,
  DataAnalysis,
  FolderOpened,
  Moon,
  Notebook,
  Odometer,
  Setting,
  SetUp,
  Share,
  Lock,
  Sunny,
  TrendCharts,
  UserFilled,
} from '@element-plus/icons-vue';
import {
  activeView,
  darkMode,
  opsDialogVisible,
  sessionColCollapsed,
} from '../composables/useGlobalUi';
import { isAdmin, role, tenantInput } from '../composables/useAuthState';
import { activateView } from '../composables/useViewActivation';
import {
  platformSection,
  setPlatformSection,
  type PlatformSection,
} from '../composables/usePlatformAssets';

const platformModuleLabels: Record<PlatformSection, string> = {
  overview: '平台总览',
  agents: '智能体',
  workflows: '工作流',
  tools: '工具管理',
  knowledge: '知识库',
  'safety-guards': '安全防护',
  'model-services': '模型服务',
  databases: '数据库管理',
  system: '系统管理',
};

const viewModuleLabels: Record<string, string> = {
  chat: '对话工作台',
  knowledge: '知识库',
  evaluation: '评测中心',
  admin: '文档总览',
  usage: '用量统计',
};

const currentModuleLabel = computed(() =>
  activeView.value === 'platform'
    ? (platformModuleLabels[platformSection.value] ?? '平台配置')
    : (viewModuleLabels[activeView.value] ?? '工作台'),
);

function goPlatform(section: PlatformSection): void {
  activeView.value = 'platform';
  setPlatformSection(section);
}
</script>

<style scoped>
.app-sidebar {
  min-width: 0;
  min-height: 0;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: var(--ui-card);
  color: var(--ui-text);
  overflow: hidden;
}

html.dark .app-sidebar {
  background: #0f1724;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 16px 6px;
}

.current-module {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px 12px;
  border-bottom: 1px solid var(--ui-border);
}

.expand-session-btn {
  border: 1px solid var(--ui-border);
  border-radius: 6px;
  background: transparent;
  color: var(--ui-muted);
  font-size: 11px;
  padding: 2px 6px;
  cursor: pointer;
}

.expand-session-btn:hover {
  color: var(--ui-accent);
  border-color: var(--ui-accent);
}
.brand-mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: var(--ui-accent);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}
.brand strong {
  display: block;
  font-size: 14px;
}
.brand small {
  display: block;
  margin-top: 2px;
  color: var(--ui-muted);
  font-size: 11px;
}

.sidebar-scroll {
  min-height: 0;
  overflow-y: auto;
  padding: 2px 10px 12px;
}
.nav-group {
  margin: 14px 8px 6px;
  color: var(--ui-muted);
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.sidebar-scroll button {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 38px;
  padding: 0 9px;
  margin-bottom: 3px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--ui-text);
  cursor: pointer;
  text-align: left;
  font-size: 13px;
}
.sidebar-scroll button:hover {
  background: rgba(100, 116, 139, 0.1);
}
.sidebar-scroll button.active {
  background: color-mix(in oklab, var(--ui-accent) 12%, transparent);
  color: var(--ui-accent);
  font-weight: 650;
}

.sidebar-foot {
  border-top: 1px solid var(--ui-border);
  padding: 9px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.foot-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.foot-tenant {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ui-muted);
  font-size: 11px;
}

.foot-buttons {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}
.foot-button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 34px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--ui-muted);
  cursor: pointer;
}
.foot-button:hover {
  background: rgba(100, 116, 139, 0.1);
  color: var(--ui-text);
}
</style>

@media (max-width: 980px) { .brand div:last-child, .sidebar-scroll button span, .foot-button span {
display: none; } .brand { justify-content: center; } .sidebar-scroll button { justify-content:
center; } .foot-button { grid-template-columns: 1fr; } }
