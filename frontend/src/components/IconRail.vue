<template>
  <!-- 图标栏：只管"去哪个页面"，所有页签常驻 -->
  <nav class="icon-rail" role="tablist" aria-label="Console views">
    <div class="rail-brand" title="Enterprise Agent Platform">K</div>
    <div class="rail-nav">
      <el-tooltip content="聊天" placement="right" :show-after="300">
        <button
          type="button"
          class="rail-btn"
          :class="{ active: activeView === 'chat' }"
          @click="activateView('chat')"
        >
          <el-icon :size="18"><ChatDotRound /></el-icon>
          <span>聊天</span>
        </button>
      </el-tooltip>
      <el-tooltip content="RAG 评测" placement="right" :show-after="300">
        <button
          type="button"
          class="rail-btn"
          :class="{ active: activeView === 'evaluation' }"
          @click="activateView('evaluation')"
        >
          <el-icon :size="18"><DataAnalysis /></el-icon>
          <span>评测</span>
        </button>
      </el-tooltip>
      <el-tooltip content="知识库" placement="right" :show-after="300">
        <button
          type="button"
          class="rail-btn"
          :class="{ active: activeView === 'knowledge' }"
          @click="activateView('knowledge')"
        >
          <el-icon :size="18"><FolderOpened /></el-icon>
          <span>知识库</span>
        </button>
      </el-tooltip>
      <el-tooltip v-if="isAdmin" content="平台基础配置" placement="right" :show-after="300">
        <button
          type="button"
          class="rail-btn"
          :class="{ active: activeView === 'platform' }"
          @click="activateView('platform')"
        >
          <el-icon :size="18"><Connection /></el-icon>
          <span>平台</span>
        </button>
      </el-tooltip>      <el-tooltip v-if="isAdmin" content="管理员文档总览" placement="right" :show-after="300">
        <button
          type="button"
          class="rail-btn"
          :class="{ active: activeView === 'admin' }"
          @click="activateView('admin')"
        >
          <el-icon :size="18"><Notebook /></el-icon>
          <span>总览</span>
        </button>
      </el-tooltip>
      <el-tooltip content="用量统计" placement="right" :show-after="300">
        <button
          type="button"
          class="rail-btn"
          :class="{ active: activeView === 'usage' }"
          @click="activateView('usage')"
        >
          <el-icon :size="18"><TrendCharts /></el-icon>
          <span>用量</span>
        </button>
      </el-tooltip>
    </div>
    <div class="rail-foot">
      <el-tooltip content="鉴权与模型" placement="right" :show-after="300">
        <button type="button" class="rail-btn" @click="opsDialogVisible = true">
          <el-icon :size="18"><Setting /></el-icon>
          <span>设置</span>
        </button>
      </el-tooltip>
    </div>
  </nav>
</template>

<script setup lang="ts">
// 图标栏：模板与样式从 App.vue 原文搬入，行为零变化。
// 页签状态/切换在 useGlobalUi + useViewActivation 单例；"设置"按钮直接打开
// SettingsDialog 的开关。管理员页签按 isAdmin 显隐。
import { ChatDotRound, Connection, DataAnalysis, FolderOpened, Notebook, Setting, TrendCharts } from '@element-plus/icons-vue';
import { activeView, opsDialogVisible } from '../composables/useGlobalUi';
import { isAdmin } from '../composables/useAuthState';
import { activateView } from '../composables/useViewActivation';
</script>

<style scoped>
.icon-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-right: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-card) 88%, transparent);
  backdrop-filter: blur(12px);
}

.rail-brand {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: var(--ui-accent-strong);
  color: #fff;
  font-size: 17px;
  font-weight: 800;
  flex-shrink: 0;
  box-shadow: 0 4px 10px color-mix(in oklab, var(--ui-accent-strong) 30%, transparent);
}

.rail-nav {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  min-height: 0;
  overflow-y: auto;
  padding-top: 4px;
}

.rail-foot {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.rail-btn {
  position: relative;
  width: 48px;
  border: 0;
  border-radius: 9px;
  padding: 7px 0 5px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  color: var(--ui-muted);
  background: transparent;
  font-size: 10px;
  font-weight: 600;
  cursor: pointer;
  transition:
    color 160ms ease,
    background 160ms ease;
}

.rail-btn:hover {
  color: var(--ui-text);
  background: color-mix(in oklab, var(--ui-panel) 80%, transparent);
}

.rail-btn.active {
  color: var(--ui-accent-strong);
  background: color-mix(in oklab, var(--ui-accent) 14%, transparent);
}

/* 左缘指示条：当前页签一眼可辨，不靠大色块 */
.rail-btn.active::before {
  content: '';
  position: absolute;
  left: -10px;
  top: 9px;
  bottom: 9px;
  width: 3px;
  border-radius: 999px;
  background: var(--ui-accent-strong);
}
</style>
