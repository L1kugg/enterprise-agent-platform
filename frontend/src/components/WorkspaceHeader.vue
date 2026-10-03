<template>
  <header class="workspace-head">
    <div v-if="activeView === 'chat'" class="head-title">
      <button
        v-if="sessionColCollapsed"
        type="button"
        class="expand-col-btn"
        title="展开会话栏"
        @click="sessionColCollapsed = false"
      >
        <el-icon :size="14"><CaretRight /></el-icon>
      </button>
      <p class="workspace-kicker">Active Session</p>
      <h2>{{ activeSession?.title || '新会话' }}</h2>
      <p class="workspace-sub">
        {{ modelProfile }} · {{ agentEngine === 'workflow' ? '工作流引擎' : '标准 ReAct' }} ·
        {{ streaming ? 'SSE 流式' : 'JSON 单次' }}
      </p>
    </div>
    <div v-else-if="activeView === 'evaluation'">
      <p class="workspace-kicker">RAG Evaluation</p>
      <h2>{{ selectedEvalDataset?.name || '评测工作台' }}</h2>
      <p class="workspace-sub">
        {{ evalCurrentRun?.runId || 'no run' }} · {{ evalCurrentRun?.status || 'idle' }}
      </p>
    </div>
    <div v-else-if="activeView === 'knowledge'">
      <p class="workspace-kicker">Knowledge Base</p>
      <h2>知识库</h2>
      <p class="workspace-sub">上传文档 → 自动切分入库 → 参与全库检索</p>
    </div>
    <div v-else-if="activeView === 'admin'">
      <p class="workspace-kicker">Admin Documents</p>
      <h2>文档总览</h2>
      <p class="workspace-sub">跨租户查看所有用户上传的文档</p>
    </div>
    <div v-else-if="activeView === 'usage'">
      <p class="workspace-kicker">Tenant Usage</p>
      <h2>用量统计</h2>
      <p class="workspace-sub">本租户 token 用量与每日费用趋势</p>
    </div>
    <div v-if="activeView === 'chat'" class="head-actions">
      <!-- 工作区：选择已有，或直接输入新名字回车即创建并切换（filterable + allow-create）。
           这是工作区的新建/切换唯一入口，所以不在单工作区时隐藏，只加前缀说明 -->
      <span class="stream-detail">工作区</span>
      <el-select
        v-model="activeWorkspaceId"
        size="small"
        class="workspace-select"
        filterable
        allow-create
        default-first-option
        placeholder="选择或输入工作区"
        @change="handleWorkspaceChange"
      >
        <el-option
          v-for="workspace in workspaceOptions"
          :key="workspace"
          :label="workspace"
          :value="workspace"
        />
      </el-select>
      <el-switch v-model="darkMode" inline-prompt active-text="Dark" inactive-text="Light" />
      <!-- 空闲状态是默认态，不占位置；只在有动态（思考/输出/失败等）时亮出来 -->
      <el-tag v-if="streamPhase !== 'idle'" :type="streamStatusTagType" effect="plain">{{
        streamStatusLabel
      }}</el-tag>
      <span class="stream-detail">{{ streamStatusDetail }}</span>
      <!-- 只有 1 条分支时没有可切换/对比的内容，不显示入口 -->
      <el-button
        v-if="(activeSession?.branches.length ?? 0) > 1"
        size="small"
        @click="branchDrawerVisible = true"
      >
        分支 ({{ activeSession?.branches.length }})
      </el-button>
      <el-button size="small" @click="clearConversation">清空会话</el-button>
    </div>
    <div v-else-if="activeView === 'evaluation'" class="head-actions">
      <el-button size="small" :loading="evalLoading" @click="loadEvalDatasets">刷新</el-button>
      <el-button
        size="small"
        type="primary"
        :loading="evalRunning"
        :disabled="!evalSelectedDatasetId"
        @click="runSelectedEvalDataset"
        >运行评测</el-button
      >
      <el-button
        size="small"
        :disabled="!evalCurrentRun"
        :loading="evalReportExporting"
        @click="downloadEvalReport"
        >导出报告</el-button
      >
      <el-button size="small" :disabled="!evalCurrentRun" @click="markCurrentEvalRunBaseline"
        >设为基线</el-button
      >
    </div>
  </header>
</template>

<script setup lang="ts">
// 顶栏：各页签的标题区 + 聊天页工具行（工作区/暗色/流式状态/分支/清空）+
// 评测页动作行。模板与样式从 App.vue 原文搬入，行为零变化。
// 状态全部来自 useGlobalUi/useChatState/useSessions/useEvaluation 单例，
// 无需 props/emits。"展开会话栏"按钮直接改 sessionColCollapsed（与会话栏共享）。
import { CaretRight } from '@element-plus/icons-vue';
import {
  activeView,
  branchDrawerVisible,
  darkMode,
  sessionColCollapsed,
} from '../composables/useGlobalUi';
import {
  agentEngine,
  modelProfile,
  streamPhase,
  streamStatusDetail,
  streamStatusLabel,
  streamStatusTagType,
  streaming,
} from '../composables/useChatState';
import { clearConversation } from '../composables/useChatEngine';
import {
  activeSession,
  activeWorkspaceId,
  handleWorkspaceChange,
  workspaceOptions,
} from '../composables/useSessions';
import {
  downloadEvalReport,
  evalCurrentRun,
  evalLoading,
  evalReportExporting,
  evalRunning,
  selectedEvalDataset,
  evalSelectedDatasetId,
  loadEvalDatasets,
  markCurrentEvalRunBaseline,
  runSelectedEvalDataset,
} from '../composables/useEvaluation';
</script>

<style scoped>
.workspace-head {
  position: sticky;
  top: 0;
  z-index: 8;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-card) 84%, transparent);
  backdrop-filter: blur(10px);
}

.workspace-kicker {
  margin: 0;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--ui-muted);
}

/* 聊天页标题块：折叠后左缘挂"展开会话栏"小按钮 */
.head-title {
  position: relative;
  min-width: 0;
}

.expand-col-btn {
  position: absolute;
  left: -8px;
  top: 14px;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border: 1px solid var(--ui-border);
  border-radius: 7px;
  color: var(--ui-muted);
  background: color-mix(in oklab, var(--ui-card) 90%, transparent);
  cursor: pointer;
  transition:
    color 160ms ease,
    border-color 160ms ease;
}

.expand-col-btn:hover {
  color: var(--ui-text);
  border-color: rgba(14, 116, 144, 0.4);
}

h2 {
  margin: 8px 0 0;
  font-size: 22px;
  line-height: 1.22;
}

.workspace-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--ui-muted);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.workspace-select {
  width: 160px;
}

.stream-detail {
  font-size: 12px;
  color: var(--ui-muted);
}

@media (max-width: 980px) {
  .workspace-head {
    position: static;
  }
}

@media (max-width: 680px) {
  .workspace-head {
    padding-left: 12px;
    padding-right: 12px;
  }

  .head-actions {
    justify-content: flex-start;
  }

  .workspace-select {
    width: 120px;
  }
}
</style>
