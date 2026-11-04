<template>
  <!-- 会话栏：仅聊天页显示，可折叠 -->
  <aside v-if="sessionColVisible" class="session-col">
    <button class="new-chat-btn" type="button" @click="createAndSwitchSession">
      <el-icon :size="14"><Plus /></el-icon>
      新建会话
    </button>

    <section class="session-tools">
      <el-input
        v-model="sessionSearch"
        size="small"
        placeholder="搜索会话标题或 ID"
        clearable
        :prefix-icon="Search"
      />
      <div class="tool-row">
        <el-select v-model="workspaceFilter" size="small" class="tool-select">
          <el-option label="全部工作区" value="all" />
          <el-option
            v-for="workspace in workspaceOptions"
            :key="workspace"
            :label="workspace"
            :value="workspace"
          />
        </el-select>
        <el-switch
          v-model="showArchivedSessions"
          size="small"
          inline-prompt
          active-text="含归档"
          inactive-text="隐藏归档"
        />
      </div>
      <div class="ws-row" title="当前会话所在的工作区；在此输入新名称可移动并新建">
        <span class="ws-label">当前</span>
        <el-select
          v-model="activeWorkspaceId"
          size="small"
          filterable
          allow-create
          default-first-option
          placeholder="切换或输入新工作区"
          @change="handleWorkspaceChange"
        >
          <el-option
            v-for="workspace in workspaceOptions"
            :key="workspace"
            :label="workspace"
            :value="workspace"
          />
        </el-select>
      </div>
    </section>

    <section class="session-panel">
      <div class="section-head">
        <p class="section-label">会话</p>
        <div class="branch-head-actions">
          <span class="section-meta">{{ filteredSessions.length }}/{{ sessionCount }}</span>
          <el-tooltip
            :content="'会话分支 (' + (activeSession?.branches.length ?? 0) + ')'"
            placement="bottom"
            :show-after="300"
          >
            <button
              type="button"
              aria-label="查看分支"
              :disabled="(activeSession?.branches.length ?? 0) <= 1"
              @click="branchDrawerVisible = true"
            >
              <el-icon :size="13"><Share /></el-icon>
            </button>
          </el-tooltip>
          <el-tooltip content="清空当前会话" placement="bottom" :show-after="300">
            <button type="button" aria-label="清空当前会话" @click="clearConversation">
              <el-icon :size="13"><Delete /></el-icon>
            </button>
          </el-tooltip>
          <el-tooltip content="云端拉取" placement="bottom" :show-after="300">
            <button
              type="button"
              :disabled="cloudSyncing || !canUseRemoteSync"
              @click="loadSessionsFromCloud"
            >
              <el-icon :size="13"><Download /></el-icon>
            </button>
          </el-tooltip>
          <el-tooltip content="保存当前会话到云端" placement="bottom" :show-after="300">
            <button
              type="button"
              :disabled="cloudSyncing || !canUseRemoteSync"
              @click="syncActiveSessionToCloud"
            >
              <el-icon :size="13"><Upload /></el-icon>
            </button>
          </el-tooltip>
        </div>
      </div>
      <div class="session-list">
        <template v-for="group in groupedSessions" :key="group.key">
          <p class="session-group-label">{{ group.label }}</p>
          <div
            v-for="session in group.items"
            :key="session.id"
            class="session-item"
            :class="{ active: session.id === activeSessionId }"
            role="button"
            tabindex="0"
            @click="switchSession(session.id)"
            @keydown.enter.prevent="switchSession(session.id)"
          >
            <div class="session-content" @dblclick.stop="renameSession(session.id)">
              <div class="session-title-row">
                <p class="session-title">{{ session.title }}</p>
                <el-tag v-if="session.pinned" size="small" type="success" effect="plain"
                  >置顶</el-tag
                >
                <el-tag v-if="session.archived" size="small" type="info" effect="plain"
                  >归档</el-tag
                >
              </div>
              <p class="session-meta-row">
                {{ session.workspaceId }} · {{ sessionMessageCount(session) }} 条 ·
                {{ formatRelativeTime(session.updatedAt) }}
              </p>
            </div>
            <div class="session-actions">
              <el-tooltip content="重命名" placement="bottom" :show-after="300">
                <button type="button" aria-label="重命名" @click.stop="renameSession(session.id)">
                  <el-icon :size="12"><Edit /></el-icon>
                </button>
              </el-tooltip>
              <el-tooltip content="生成交接摘要" placement="bottom" :show-after="300">
                <button
                  type="button"
                  aria-label="生成交接摘要"
                  @click.stop="generateHandoffSummary(session.id)"
                >
                  <el-icon :size="12"><DocumentCopy /></el-icon>
                </button>
              </el-tooltip>
              <el-tooltip
                :content="session.pinned ? '取消置顶' : '置顶'"
                placement="bottom"
                :show-after="300"
              >
                <button
                  type="button"
                  :aria-label="session.pinned ? '取消置顶' : '置顶'"
                  @click.stop="toggleSessionPin(session.id)"
                >
                  <el-icon :size="12"><Top /></el-icon>
                </button>
              </el-tooltip>
              <el-tooltip
                :content="session.archived ? '取消归档' : '归档'"
                placement="bottom"
                :show-after="300"
              >
                <button
                  type="button"
                  :aria-label="session.archived ? '取消归档' : '归档'"
                  @click.stop="toggleSessionArchive(session.id)"
                >
                  <el-icon :size="12"><Box /></el-icon>
                </button>
              </el-tooltip>
              <el-tooltip content="删除" placement="bottom" :show-after="300">
                <button
                  type="button"
                  class="danger"
                  aria-label="删除"
                  @click.stop="removeSession(session.id)"
                >
                  <el-icon :size="12"><Delete /></el-icon>
                </button>
              </el-tooltip>
            </div>
          </div>
        </template>
        <div v-if="filteredSessions.length === 0" class="session-empty">
          <p>没有匹配的会话</p>
          <p class="empty-hint">试试调整关键词、工作区或归档筛选</p>
          <button type="button" class="empty-reset" @click="clearSessionFilters">
            清除筛选条件
          </button>
        </div>
      </div>
    </section>

    <button class="collapse-col-btn" type="button" @click="sessionColCollapsed = true">
      <el-icon :size="14"><CaretLeft /></el-icon>
      收起会话栏
    </button>
  </aside>
</template>

<script setup lang="ts">
// 会话栏：模板与样式从 App.vue 原文搬入，行为零变化。
// 显隐开关 sessionColVisible / 折叠状态 sessionColCollapsed 在 useGlobalUi
// （折叠按钮在这里，展开按钮在顶栏，所以折叠状态归全局而不是本组件）。
// 列表/筛选状态与全部会话动作在 useSessions 单例，无需 props/emits。
import { computed } from 'vue';
import {
  Box,
  CaretLeft,
  Delete,
  DocumentCopy,
  Download,
  Edit,
  Plus,
  Search,
  Share,
  Top,
  Upload,
} from '@element-plus/icons-vue';
import {
  branchDrawerVisible,
  sessionColCollapsed,
  sessionColVisible,
  sessionSearch,
  showArchivedSessions,
  workspaceFilter,
} from '../composables/useGlobalUi';
import { canUseRemoteSync } from '../composables/useAuthState';
import { clearConversation } from '../composables/useChatEngine';
import type { SessionRecord } from '../types/console';
import {
  activeSession,
  activeSessionId,
  activeWorkspaceId,
  cloudSyncing,
  createAndSwitchSession,
  filteredSessions,
  generateHandoffSummary,
  handleWorkspaceChange,
  loadSessionsFromCloud,
  removeSession,
  renameSession,
  sessionCount,
  switchSession,
  syncActiveSessionToCloud,
  toggleSessionArchive,
  toggleSessionPin,
  workspaceOptions,
} from '../composables/useSessions';
import { formatRelativeTime } from '../utils/format';

/** 时间分组：置顶 / 今天 / 近 7 天 / 更早（组内保持"置顶优先 + 最近更新"排序） */
interface SessionGroup {
  key: string;
  label: string;
  items: SessionRecord[];
}

const groupedSessions = computed<SessionGroup[]>(() => {
  const now = Date.now();
  const pinned = filteredSessions.value.filter((session) => session.pinned);
  const rest = filteredSessions.value.filter((session) => !session.pinned);
  const buckets: Record<string, SessionRecord[]> = { today: [], week: [], earlier: [] };
  rest.forEach((session) => {
    const age = now - session.updatedAt;
    const sameDay = new Date(session.updatedAt).toDateString() === new Date(now).toDateString();
    if (sameDay) {
      buckets.today.push(session);
    } else if (age < 7 * 86_400_000) {
      buckets.week.push(session);
    } else {
      buckets.earlier.push(session);
    }
  });
  const groups: SessionGroup[] = [];
  if (pinned.length > 0) {
    groups.push({ key: 'pinned', label: '置顶', items: pinned });
  }
  if (buckets.today.length > 0) {
    groups.push({ key: 'today', label: '今天', items: buckets.today });
  }
  if (buckets.week.length > 0) {
    groups.push({ key: 'week', label: '近 7 天', items: buckets.week });
  }
  if (buckets.earlier.length > 0) {
    groups.push({ key: 'earlier', label: '更早', items: buckets.earlier });
  }
  return groups;
});

/** 会话全部分支的消息总数（识别"哪条会话聊得多"的信息线索） */
function sessionMessageCount(session: SessionRecord): number {
  return session.branches.reduce((sum, branch) => sum + (branch.messages?.length ?? 0), 0);
}

/** 空态出路：一键还原三个筛选条件（refs 由 registerSessionEffects 的 watch 自动持久化） */
function clearSessionFilters(): void {
  sessionSearch.value = '';
  workspaceFilter.value = 'all';
  showArchivedSessions.value = false;
}
</script>

<style scoped>
.session-col {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: var(--ui-card);
}

/* flex 纵向布局默认"先压缩孩子、再出滚动条"：按钮/工具区锁 flex-shrink，
   超高时只让会话列表（flex:1 + 内部滚动）收缩，其余面板保持自然高度。 */
.session-col > *,
.session-col .session-tools {
  flex-shrink: 0;
}

.collapse-col-btn {
  flex-shrink: 0;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 6px 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--ui-muted);
  background: transparent;
  font-size: 12px;
  cursor: pointer;
  transition:
    color 160ms ease,
    border-color 160ms ease;
}

.collapse-col-btn:hover {
  color: var(--ui-text);
  border-color: rgba(49, 93, 235, 0.4);
}

/* 新建会话：保留主操作的品牌底身份，收敛视觉重量（图标化 + 更轻的悬浮） */
.new-chat-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: none;
  background: var(--ui-accent-strong);
  color: #fff;
  border-radius: 10px;
  padding: 9px 14px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 4px 12px color-mix(in oklab, var(--ui-accent-strong) 24%, transparent);
  transition:
    box-shadow 180ms ease,
    filter 180ms ease;
}

.new-chat-btn:hover {
  filter: brightness(1.06);
  box-shadow: 0 6px 16px color-mix(in oklab, var(--ui-accent-strong) 32%, transparent);
}

.session-tools {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 9px 10px;
  background: color-mix(in oklab, var(--ui-panel) 88%, transparent);
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.tool-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.tool-select {
  min-width: 0;
}

/* 当前工作区行：浅品牌底色把它与上方"筛选"下拉视觉分组（两个 select 长得一样是旧版最大的困惑点） */
.ws-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 6px;
  align-items: center;
  padding: 5px 7px;
  border-radius: 8px;
  background: color-mix(in oklab, var(--ui-accent) 6%, transparent);
}

.ws-label {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: color-mix(in oklab, var(--ui-accent-strong) 78%, var(--ui-text));
}

/* 分组标签：吸顶小标签（列表滚动时保持当前组名可见），与上一组卡片留出呼吸间距 */
.session-group-label {
  position: sticky;
  top: 0;
  z-index: 1;
  margin: 6px 2px 2px;
  padding: 4px 2px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.05em;
  color: var(--ui-muted);
  background: var(--ui-card);
}

/* 首个分组贴住面板头，不叠加额外上间距 */
.session-group-label:first-child {
  margin-top: 0;
}

/* 归档开关：内联文案 4 个汉字，轨道放宽 + 禁换行，否则文字挤压截断 */
.tool-row :deep(.el-switch .el-switch__core) {
  min-width: 76px;
}

.tool-row :deep(.el-switch .el-switch__inner-wrapper span) {
  white-space: nowrap;
}

/* 会话面板撑满会话栏剩余高度：列表内部滚动，会话再多也不挤压其它区域 */
.session-col .session-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.session-col .session-list {
  flex: 1;
}

.session-item {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 9px 10px;
  background: color-mix(in oklab, var(--ui-card) 76%, transparent);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 6px;
  transition:
    border-color 160ms ease,
    background 160ms ease;
}

.session-item:hover {
  border-color: color-mix(in oklab, var(--ui-accent) 45%, transparent);
  background: color-mix(in oklab, var(--ui-accent) 4%, var(--ui-card));
}

.session-item.active {
  border-color: color-mix(in oklab, var(--ui-accent) 60%, transparent);
  background: color-mix(in oklab, var(--ui-accent) 9%, var(--ui-card));
  box-shadow: inset 3px 0 0 var(--ui-accent-strong);
}

.session-content {
  min-width: 0;
}

.session-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.session-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta-row {
  margin: 3px 0 0;
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
  color: color-mix(in oklab, var(--ui-muted) 88%, transparent);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-actions {
  display: flex;
  gap: 2px;
  flex-wrap: wrap;
  /* 悬浮/聚焦时才出现：选中会话保持干净（不再常驻一排按钮），触屏端整栏本就隐藏 */
  opacity: 0;
  transition: opacity 120ms ease;
}

.session-item:hover .session-actions,
.session-item:focus-within .session-actions {
  opacity: 1;
}

/* 卡片操作钮：ghost 图标位（与消息操作按钮同一语言），替代描边胶囊 */
.session-actions button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--ui-muted);
  border-radius: 6px;
  padding: 4px 6px;
  font-size: 11px;
  cursor: pointer;
  transition:
    color 140ms ease,
    background 140ms ease;
}

.session-actions button:hover {
  color: var(--ui-accent);
  background: color-mix(in oklab, var(--ui-accent) 9%, transparent);
}

.session-actions .danger {
  color: #dc2626;
}

.session-actions .danger:hover {
  color: #dc2626;
  background: color-mix(in oklab, rgba(220, 38, 38, 0.08), transparent);
}

/* 面板头部动作钮：ghost 图标位（scoped 覆盖 shared.css 的描边胶囊，不影响 BranchDrawer） */
.branch-head-actions {
  gap: 2px;
}

.branch-head-actions button {
  border: none;
  background: transparent;
  color: var(--ui-muted);
  border-radius: 7px;
  padding: 5px 7px;
  transition:
    color 140ms ease,
    background 140ms ease;
}

.branch-head-actions button:hover {
  color: var(--ui-accent);
  background: color-mix(in oklab, var(--ui-accent) 9%, transparent);
}

/* 空态：区分"无匹配"并给出路（一键清除筛选），不再是死胡同 */
.session-empty {
  text-align: center;
  padding: 18px 6px;
}

.session-empty p {
  margin: 0;
}

.session-empty .empty-hint {
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--ui-muted);
}

.session-empty .empty-reset {
  margin-top: 10px;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  padding: 4px 12px;
  background: transparent;
  color: var(--ui-accent);
  font-size: 12px;
  cursor: pointer;
  transition:
    border-color 140ms ease,
    background 140ms ease;
}

.session-empty .empty-reset:hover {
  border-color: color-mix(in oklab, var(--ui-accent) 45%, transparent);
  background: color-mix(in oklab, var(--ui-accent) 8%, transparent);
}

@media (max-width: 980px) {
  .session-col {
    display: none;
  }
}
</style>
