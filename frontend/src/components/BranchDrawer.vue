<template>
  <!-- 分支树：从顶栏「分支」按钮打开（原侧栏 branch-panel） -->
  <el-drawer v-model="branchDrawerVisible" title="分支树" size="360px">
    <section class="branch-panel in-drawer">
      <div class="section-head">
        <p class="section-label">分支列表</p>
        <div class="branch-head-actions">
          <span class="section-meta">{{ activeSession?.branches.length ?? 0 }} 条</span>
          <button type="button" @click="forkFromCurrent">从当前分叉</button>
          <button
            type="button"
            :disabled="!activeBranch?.parentBranchId"
            @click="compareWithParent"
          >
            对比父分支
          </button>
          <button
            type="button"
            :disabled="!activeBranch?.parentBranchId"
            @click="mergeIntoParent"
          >
            合并到父分支
          </button>
        </div>
      </div>
      <div class="branch-list">
        <div
          v-for="node in branchTreeItems"
          :key="node.branch.id"
          class="branch-item"
          :class="{ active: node.branch.id === activeBranch?.id }"
          :style="{ paddingLeft: `${12 + node.depth * 14}px` }"
          role="button"
          tabindex="0"
          @click="switchBranch(node.branch.id)"
          @keydown.enter.prevent="switchBranch(node.branch.id)"
        >
          <span class="branch-line" :style="{ opacity: node.depth > 0 ? 1 : 0 }"></span>
          <div class="branch-content">
            <p>{{ node.branch.title }}</p>
            <small>{{ formatTime(node.branch.updatedAt) }}</small>
          </div>
        </div>
        <div v-if="branchTreeItems.length === 0" class="session-empty">暂无分支</div>
      </div>
    </section>
  </el-drawer>
</template>

<script setup lang="ts">
// 分支树抽屉：模板与样式从 App.vue 原文搬入，行为零变化。
// 打开开关在 useGlobalUi，会话/分支状态与动作在 useSessions 单例。
// 注意：el-drawer 可能把内容传送到 body，但 scoped 属性长在元素上，
// `.branch-panel.in-drawer` 等规则照样命中（与拆分前一致）。
import { branchDrawerVisible } from '../composables/useGlobalUi';
import {
  activeBranch,
  activeSession,
  branchTreeItems,
  compareWithParent,
  forkFromCurrent,
  mergeIntoParent,
  switchBranch,
} from '../composables/useSessions';
import { formatTime } from '../utils/format';
</script>

<style scoped>
/* 分支面板住在抽屉里：同样撑满抽屉高度，列表内部滚动 */
.branch-panel.in-drawer {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.branch-panel.in-drawer .branch-list {
  flex: 1;
}

.branch-item {
  position: relative;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: color-mix(in oklab, var(--ui-card) 80%, transparent);
  cursor: pointer;
}

.branch-item.active {
  border-color: rgba(14, 116, 144, 0.45);
}

.branch-line {
  width: 10px;
  height: 1px;
  background: var(--ui-muted);
}

.branch-content {
  min-width: 0;
}

.branch-content p,
.branch-content small {
  margin: 0;
}

.branch-content p {
  font-size: 12px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.branch-content small {
  color: var(--ui-muted);
}
</style>
