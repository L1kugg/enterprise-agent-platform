<template>
  <aside class="app-sidebar">
    <div class="brand">
      <div class="brand-mark" aria-hidden="true">AI</div>
      <div>
        <strong>Enterprise Agent Platform</strong>
        <small>AI Agent Platform</small>
      </div>
    </div>

    <nav class="sidebar-scroll" aria-label="主导航">
      <p class="nav-group">工作空间</p>
      <button type="button" :class="{active: activeView === 'platform' && platformSection === 'overview'}" @click="goPlatform('overview')">
        <el-icon><Odometer /></el-icon><span>平台总览</span>
      </button>
      <button type="button" :class="{active: activeView === 'platform' && platformSection === 'agents'}" @click="goPlatform('agents')">
        <el-icon><Avatar /></el-icon><span>智能体</span>
      </button>
      <button type="button" :class="{active: activeView === 'platform' && platformSection === 'workflows'}" @click="goPlatform('workflows')">
        <el-icon><Share /></el-icon><span>工作流</span>
      </button>
      <button type="button" :class="{active: activeView === 'platform' && platformSection === 'tools'}" @click="goPlatform('tools')">
        <el-icon><SetUp /></el-icon><span>工具管理</span>
      </button>
      <button type="button" :class="{active: activeView === 'knowledge'}" @click="activateView('knowledge')">
        <el-icon><FolderOpened /></el-icon><span>知识库</span>
      </button>

      <p class="nav-group">平台配置</p>
      <button type="button" :class="{active: activeView === 'platform' && platformSection === 'safety-guards'}" @click="goPlatform('safety-guards')">
        <el-icon><Lock /></el-icon><span>安全防护</span>
      </button>
      <button type="button" :class="{active: activeView === 'platform' && platformSection === 'model-services'}" @click="goPlatform('model-services')">
        <el-icon><Cpu /></el-icon><span>模型服务</span>
      </button>
      <button type="button" :class="{active: activeView === 'platform' && platformSection === 'databases'}" @click="goPlatform('databases')">
        <el-icon><Coin /></el-icon><span>系统管理</span>
      </button>

      <p class="nav-group">运营分析</p>
      <button type="button" :class="{active: activeView === 'chat'}" @click="activateView('chat')">
        <el-icon><ChatDotRound /></el-icon><span>对话工作台</span>
      </button>
      <button type="button" :class="{active: activeView === 'evaluation'}" @click="activateView('evaluation')">
        <el-icon><DataAnalysis /></el-icon><span>评测中心</span>
      </button>
      <button type="button" :class="{active: activeView === 'usage'}" @click="activateView('usage')">
        <el-icon><TrendCharts /></el-icon><span>用量统计</span>
      </button>
      <button v-if="isAdmin" type="button" :class="{active: activeView === 'admin'}" @click="activateView('admin')">
        <el-icon><Notebook /></el-icon><span>文档总览</span>
      </button>
    </nav>

    <div class="sidebar-foot">
      <button type="button" class="foot-button" @click="opsDialogVisible = true">
        <el-icon><Setting /></el-icon><span>设置</span>
      </button>
      <button type="button" class="foot-button" @click="darkMode = !darkMode">
        <el-icon><Moon v-if="!darkMode" /><Sunny v-else /></el-icon><span>{{ darkMode ? '浅色' : '深色' }}</span>
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { Avatar, ChatDotRound, Coin, Cpu, DataAnalysis, FolderOpened, Moon, Notebook, Odometer, Setting, SetUp, Share, Lock, Sunny, TrendCharts } from '@element-plus/icons-vue';
import { activeView, darkMode, opsDialogVisible } from '../composables/useGlobalUi';
import { isAdmin } from '../composables/useAuthState';
import { activateView } from '../composables/useViewActivation';
import { platformSection, setPlatformSection, type PlatformSection } from '../composables/usePlatformAssets';

function goPlatform(section: PlatformSection): void {
  activeView.value = 'platform';
  setPlatformSection(section);
}
</script>

<style scoped>
.app-sidebar {
  height: 100vh;
  min-width: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  border-right: 1px solid var(--ui-border);
  background: #fff;
  color: var(--ui-text);
}

html.dark .app-sidebar { background: #0f1724; border-right-color: var(--ui-border); }

.brand { display: flex; align-items: center; gap: 10px; padding: 16px 16px 13px; }
.brand-mark { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 9px; background: var(--ui-accent); color: #fff; font-size: 12px; font-weight: 700; }
.brand strong { display: block; font-size: 14px; }
.brand small { display: block; margin-top: 2px; color: var(--ui-muted); font-size: 11px; }

.sidebar-scroll { min-height: 0; overflow-y: auto; padding: 2px 10px 12px; }
.nav-group { margin: 14px 8px 6px; color: var(--ui-muted); font-size: 11px; letter-spacing: .08em; text-transform: uppercase; }
.sidebar-scroll button { width: 100%; display: flex; align-items: center; gap: 9px; min-height: 38px; padding: 0 9px; margin-bottom: 3px; border: 0; border-radius: 7px; background: transparent; color: var(--ui-text); cursor: pointer; text-align: left; font-size: 13px; }
.sidebar-scroll button:hover { background: rgba(100, 116, 139, .1); }
.sidebar-scroll button.active { background: color-mix(in oklab, var(--ui-accent) 12%, transparent); color: var(--ui-accent); font-weight: 650; }

.sidebar-foot { border-top: 1px solid var(--ui-border); padding: 9px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }
.foot-button { display: flex; align-items: center; justify-content: center; gap: 6px; min-height: 34px; border: 0; border-radius: 7px; background: transparent; color: var(--ui-muted); cursor: pointer; }
.foot-button:hover { background: rgba(100, 116, 139, .1); color: var(--ui-text); }
</style>

@media (max-width: 980px) {
  .brand div:last-child,
  .sidebar-scroll button span,
  .foot-button span { display: none; }
  .brand { justify-content: center; }
  .sidebar-scroll button { justify-content: center; }
  .foot-button { grid-template-columns: 1fr; }
}
