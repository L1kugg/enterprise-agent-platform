<template>
  <section
    ref="messageContainer"
    class="messages"
    @scroll="onMessageScroll"
    @click="handleMarkdownClick"
  >
    <div v-if="hydrating" class="hydration-skeleton">
      <div class="skeleton-line lg"></div>
      <div class="skeleton-line"></div>
      <div class="skeleton-line short"></div>
      <div class="skeleton-bubble"></div>
      <div class="skeleton-bubble alt"></div>
    </div>

    <template v-else>
      <div v-if="isEmptyConversation" class="welcome-block">
        <h3>开始一个新问题</h3>
        <p>上传文档或直接提问，回答会标注出处；编辑已发送的消息可以重新生成回答。</p>
        <div class="welcome-suggestions">
          <button
            v-for="question in welcomeSuggestions"
            :key="question"
            type="button"
            class="welcome-chip"
            @click="applySuggestion(question)"
          >
            {{ question }}
          </button>
        </div>
      </div>

      <div class="virtual-spacer" :style="{ height: `${virtualTopSpacer}px` }"></div>

      <article
        v-for="entry in virtualMessages"
        :key="entry.item.id"
        :ref="(el) => setMessageRowRef(entry.item.id, el as HTMLElement | null)"
        :data-msg-id="entry.item.id"
        class="message-row"
        :class="[entry.item.role, entry.item.state || 'done']"
      >
        <div class="avatar">{{ entry.item.role === 'user' ? 'U' : 'AI' }}</div>
        <div class="bubble-wrap">
          <div class="bubble-meta">
            <span>{{ entry.item.role === 'user' ? 'You' : 'Assistant' }}</span>
            <span>{{ formatTime(entry.item.createdAt) }}</span>
            <span v-if="entry.item.kind === 'research'" class="research-badge">深度研究</span>
            <span v-if="entry.item.state === 'streaming'" class="status-dot">生成中</span>
            <span v-if="entry.item.state === 'pending'" class="status-dot">思考中</span>
          </div>

          <div class="bubble">
            <template v-if="entry.item.role === 'assistant'">
              <div
                v-if="entry.item.state === 'pending' && !entry.item.content"
                class="assistant-skeleton"
              >
                <div></div>
                <div></div>
                <div></div>
              </div>
              <div v-else>
                <!-- eslint-disable-next-line vue/no-v-html -->
                <div class="markdown" v-html="renderMarkdown(entry.item.content)"></div>
                <div v-if="entry.item.citations?.length" class="citation-panel">
                  <p class="citation-title">来源引用</p>
                  <div class="citation-list">
                    <button
                      v-for="(citation, citationIndex) in entry.item.citations"
                      :key="`${entry.item.id}-${citationIndex}`"
                      type="button"
                      class="citation-chip"
                      @click="openCitation(citation)"
                    >
                      [{{ citationIndex + 1 }}] {{ citation }}
                    </button>
                  </div>
                </div>
                <div v-if="entry.item.evidence?.length" class="evidence-panel">
                  <p class="citation-title">证据片段</p>
                  <ul>
                    <li
                      v-for="(snippet, snippetIndex) in entry.item.evidence"
                      :key="`${entry.item.id}-ev-${snippetIndex}`"
                    >
                      {{ snippet }}
                    </li>
                  </ul>
                </div>
                <!-- Agent 执行轨迹时间线 -->
                <div
                  v-if="traceSteps.length && entry.index === virtualMessages.length - 1"
                  class="trace-timeline-panel"
                >
                  <div class="trace-timeline-header">
                    <span class="trace-timeline-title">Agent 执行轨迹</span>
                    <span class="trace-timeline-meta"
                      >{{ traceSteps.length }} 步 · {{ traceDurationMs }}ms</span
                    >
                  </div>
                  <div class="trace-timeline">
                    <div
                      v-for="(ts, tsIdx) in traceSteps"
                      :key="`trace-${tsIdx}`"
                      class="trace-timeline-step"
                      :class="[
                        `trace-action-${ts.action}`,
                        { 'trace-last': tsIdx === traceSteps.length - 1 },
                      ]"
                    >
                      <div class="trace-timeline-rail">
                        <div class="trace-node"></div>
                        <div
                          v-if="tsIdx < traceSteps.length - 1"
                          class="trace-connector"
                        ></div>
                      </div>
                      <div class="trace-timeline-content">
                        <div class="trace-step-header">
                          <span class="trace-step-label">Step {{ ts.step }}</span>
                          <span class="trace-action-badge" :class="`badge-${ts.action}`">{{
                            ts.action
                          }}</span>
                        </div>
                        <div v-if="ts.thought" class="trace-thought-block">
                          <span class="trace-field-label">💭 Thought</span>
                          <p>{{ ts.thought }}</p>
                        </div>
                        <div
                          v-if="ts.actionInput && Object.keys(ts.actionInput).length"
                          class="trace-input-block"
                        >
                          <span class="trace-field-label">🔧 Input</span>
                          <pre>{{ JSON.stringify(ts.actionInput, null, 2) }}</pre>
                        </div>
                        <details v-if="ts.observation" class="trace-obs-block">
                          <summary>
                            <span class="trace-field-label">📋 Observation</span>
                          </summary>
                          <div class="trace-obs-content">
                            <pre>{{
                              typeof ts.observation === 'string'
                                ? ts.observation
                                : JSON.stringify(ts.observation, null, 2)
                            }}</pre>
                          </div>
                        </details>
                        <div v-if="tsIdx === 0" class="trace-retrieval-lanes">
                          <span class="trace-field-label">检索四路召回</span>
                          <div class="retrieval-bar">
                            <div class="retrieval-lane vector" style="width: 40%">
                              <span>Vector 40%</span>
                            </div>
                            <div class="retrieval-lane keyword" style="width: 25%">
                              <span>Keyword 25%</span>
                            </div>
                            <div class="retrieval-lane graph" style="width: 20%">
                              <span>Graph 20%</span>
                            </div>
                            <div class="retrieval-lane web" style="width: 15%">
                              <span>Web 15%</span>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <template v-else>
              <div v-if="editingMessageId === entry.item.id" class="edit-box">
                <el-input
                  v-model="editingMessageDraft"
                  type="textarea"
                  :rows="3"
                  resize="none"
                />
                <div class="edit-actions">
                  <el-button size="small" @click="cancelEditMessage">取消</el-button>
                  <el-button
                    size="small"
                    type="primary"
                    :disabled="!editingMessageDraft.trim() || sending"
                    @click="submitEditAndResend(entry.index, entry.item.id)"
                  >
                    编辑后重发
                  </el-button>
                </div>
              </div>
              <p v-else class="plain">{{ entry.item.content }}</p>
            </template>
          </div>

          <div class="message-actions">
            <button type="button" @click="copyMessage(entry.item.content)">
              <el-icon :size="12"><CopyDocument /></el-icon>
              复制
            </button>
            <button
              v-if="entry.item.role === 'assistant'"
              type="button"
              @click="regenerateFrom(entry.index)"
            >
              <el-icon :size="12"><RefreshRight /></el-icon>
              重新生成
            </button>
            <button
              v-if="entry.item.role === 'assistant'"
              type="button"
              :disabled="
                Boolean(answerFeedbackMap[entry.item.id]) ||
                Boolean(answerFeedbackLoading[entry.item.id])
              "
              @click="rateAnswer(entry.index, entry.item, 5)"
            >
              <el-icon :size="12"><CircleCheck /></el-icon>
              有帮助
            </button>
            <button
              v-if="entry.item.role === 'assistant'"
              type="button"
              :disabled="
                Boolean(answerFeedbackMap[entry.item.id]) ||
                Boolean(answerFeedbackLoading[entry.item.id])
              "
              @click="rateAnswer(entry.index, entry.item, 1)"
            >
              <el-icon :size="12"><CircleClose /></el-icon>
              待改进
            </button>
            <button
              v-if="entry.item.role === 'user'"
              type="button"
              @click="startEditMessage(entry.item)"
            >
              <el-icon :size="12"><Edit /></el-icon>
              编辑后重发
            </button>
          </div>
        </div>
      </article>

      <div class="virtual-spacer" :style="{ height: `${virtualBottomSpacer}px` }"></div>

      <div v-if="sending && isStreamingResponse" class="thinking">
        {{ streamStatusLabel }} · {{ streamStatusDetail || '处理中...' }}
      </div>
    </template>
  </section>

  <footer class="composer-shell">
    <div class="composer">
      <div v-if="composerUploadChip" class="upload-chip" :class="composerUploadChip.kind">
        <span class="upload-chip-text"
          >📄 {{ composerUploadChip.name }} · {{ composerUploadChip.text }}</span
        >
        <button
          type="button"
          class="upload-chip-close"
          aria-label="关闭状态提示"
          @click="dismissComposerUploadChip"
        >
          ×
        </button>
      </div>
      <el-input
        ref="composerInputRef"
        v-model="prompt"
        class="composer-input"
        type="textarea"
        :rows="3"
        resize="none"
        placeholder="输入问题，Enter 发送，Shift + Enter 换行"
        @keydown.enter.exact.prevent="send"
      />
      <div class="composer-footer">
        <div class="composer-left">
          <div class="composer-tools">
            <button
              type="button"
              class="composer-tool"
              :class="{ active: researchMode }"
              :disabled="sending"
              @click="researchMode = !researchMode"
            >
              <el-icon :size="14"><Compass /></el-icon>
              深度研究
            </button>
            <el-tooltip content="支持 PDF / Word（doc、docx）/ Markdown 文件" placement="top">
              <button
                type="button"
                class="composer-tool"
                :disabled="composerUploading"
                @click="triggerComposerUpload"
              >
                <el-icon :size="14"><Paperclip /></el-icon>
                上传文档
              </button>
            </el-tooltip>
            <input
              ref="composerFileInput"
              type="file"
              accept=".pdf,.doc,.docx,.md"
              class="composer-file-input"
              @change="onComposerFileChosen"
            />
          </div>
        </div>
        <div class="composer-actions">
          <el-button :disabled="!sending" @click="stopGenerating">停止</el-button>
          <el-button
            type="primary"
            :loading="sending"
            :disabled="!prompt.trim() || sending"
            @click="send"
            >发送</el-button
          >
        </div>
      </div>
    </div>
  </footer>
</template>

<script setup lang="ts">
// 聊天页：消息流（虚拟滚动）+ 输入区。模板与样式从 App.vue 原文搬入，行为零变化。
// 显隐开关在 App.vue 的组件标签上（v-if 是 v-if/v-else-if 页面链的起点）。
// 虚拟滚动三件套都住在 useChatViewport 单例：messageContainer 容器 ref、
// setMessageRowRef 行高采集（v-for 内函数式 ref 原样保留）、上下 spacer 高度。
// 子组件的模板 ref 在父组件 onMounted 之前绑定，与拆分前内联模板时序一致。
import {
  CircleCheck,
  CircleClose,
  Compass,
  CopyDocument,
  Edit,
  Paperclip,
  RefreshRight,
} from '@element-plus/icons-vue';
import { welcomeSuggestions } from '../../utils/constants';
import { renderMarkdown } from '../../utils/markdown';
import { formatTime } from '../../utils/format';
import {
  answerFeedbackLoading,
  answerFeedbackMap,
  applySuggestion,
  composerInputRef,
  editingMessageDraft,
  editingMessageId,
  isEmptyConversation,
  isStreamingResponse,
  prompt,
  researchMode,
  sending,
  streamStatusDetail,
  streamStatusLabel,
  traceDurationMs,
  traceSteps,
} from '../../composables/useChatState';
import {
  hydrating,
  messageContainer,
  onMessageScroll,
  setMessageRowRef,
  virtualBottomSpacer,
  virtualMessages,
  virtualTopSpacer,
} from '../../composables/useChatViewport';
import {
  composerFileInput,
  composerUploading,
  composerUploadChip,
  dismissComposerUploadChip,
  onComposerFileChosen,
  triggerComposerUpload,
} from '../../composables/useComposerUpload';
import {
  cancelEditMessage,
  copyMessage,
  handleMarkdownClick,
  openCitation,
  rateAnswer,
  regenerateFrom,
  send,
  startEditMessage,
  stopGenerating,
  submitEditAndResend,
} from '../../composables/useChatEngine';
</script>

<style scoped>
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 14px 0;
}

.hydration-skeleton {
  max-width: 930px;
  margin: 0 auto;
  padding: 0 20px;
  display: grid;
  gap: 10px;
}

.skeleton-line,
.skeleton-bubble {
  border-radius: 10px;
  background: linear-gradient(
    90deg,
    rgba(148, 163, 184, 0.18),
    rgba(148, 163, 184, 0.34),
    rgba(148, 163, 184, 0.18)
  );
  background-size: 220% 100%;
  animation: shimmer 1.3s linear infinite;
}

.skeleton-line {
  height: 12px;
}

.skeleton-line.lg {
  width: 52%;
}

.skeleton-line.short {
  width: 36%;
}

.skeleton-bubble {
  height: 84px;
}

.skeleton-bubble.alt {
  width: 76%;
  justify-self: end;
}

.welcome-block {
  max-width: 930px;
  margin: 0 auto 12px;
  padding: 0 20px;
}

.welcome-block h3 {
  margin: 0;
  font-size: 28px;
  line-height: 1.16;
}

.welcome-block p {
  margin: 8px 0 0;
  color: var(--ui-muted);
}

/* 首屏示例问题：样式与输入框工具按钮保持同一套胶囊语言 */
.welcome-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

.welcome-chip {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-panel) 88%, transparent);
  color: var(--ui-text);
  border-radius: 999px;
  padding: 7px 14px;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 160ms ease;
}

.welcome-chip:hover {
  border-color: var(--ui-accent);
}

.message-actions button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-panel) 88%, transparent);
  color: var(--ui-text);
  border-radius: 999px;
  padding: 6px 11px;
  font-size: 12px;
  cursor: pointer;
}

.virtual-spacer {
  width: 100%;
  pointer-events: none;
}

.message-row {
  max-width: 930px;
  margin: 0 auto;
  padding: 0 20px 18px;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
  gap: 12px;
  align-items: flex-start;
}

.message-row.user {
  grid-template-columns: minmax(0, 1fr) 38px;
}

.avatar {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
  background: linear-gradient(150deg, #0f766e, #0369a1);
  color: #f8fafc;
}

.message-row.user .avatar {
  grid-column: 2;
  background: linear-gradient(150deg, #1d4ed8, #4338ca);
}

.bubble-wrap {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.message-row.user .bubble-wrap {
  align-items: flex-end;
}

.bubble-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--ui-muted);
  font-size: 12px;
}

.status-dot {
  color: var(--ui-accent);
}

.bubble {
  width: min(100%, 860px);
  border-radius: 14px;
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-card) 86%, transparent);
  padding: 12px 14px;
}

.message-row.assistant .bubble {
  border: none;
  background: transparent;
  padding: 2px 0;
}

.message-row.user .bubble {
  background: linear-gradient(150deg, rgba(29, 78, 216, 0.15), rgba(67, 56, 202, 0.12));
}

.assistant-skeleton {
  display: grid;
  gap: 8px;
}

.assistant-skeleton div {
  height: 12px;
  border-radius: 8px;
  background: linear-gradient(
    90deg,
    rgba(148, 163, 184, 0.16),
    rgba(148, 163, 184, 0.3),
    rgba(148, 163, 184, 0.16)
  );
  background-size: 220% 100%;
  animation: shimmer 1.3s linear infinite;
}

.assistant-skeleton div:nth-child(3) {
  width: 65%;
}

.edit-box {
  display: grid;
  gap: 8px;
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.plain {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.6;
}

.message-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.citation-panel,
.evidence-panel {
  margin-top: 10px;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 8px 10px;
  background: color-mix(in oklab, var(--ui-panel) 86%, transparent);
}

.citation-title {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--ui-muted);
}

.citation-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.citation-chip {
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-card) 88%, transparent);
  color: var(--ui-text);
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  text-align: left;
}

.evidence-panel ul {
  margin: 0;
  padding-left: 18px;
  display: grid;
  gap: 4px;
  color: var(--ui-muted);
  font-size: 12px;
}

/* ── Agent 执行轨迹时间线 ─────────────────────────── */
.trace-timeline-panel {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 12px;
  background: color-mix(in oklab, var(--ui-panel) 92%, transparent);
  border: 1px solid var(--ui-border);
}
.trace-timeline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.trace-timeline-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--ui-muted);
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.trace-timeline-meta {
  font-size: 11px;
  color: var(--ui-muted);
}
.trace-timeline {
  display: flex;
  flex-direction: column;
  gap: 0;
}
.trace-timeline-step {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 12px;
  min-height: 48px;
}
.trace-timeline-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
}
.trace-node {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #6366f1;
  border: 2px solid var(--ui-card);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.25);
  flex-shrink: 0;
  z-index: 1;
}
.trace-action-tool_call .trace-node {
  background: #8b5cf6;
  box-shadow: 0 0 0 3px rgba(139, 92, 246, 0.25);
}
.trace-action-finish .trace-node {
  background: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.25);
}
.trace-action-error .trace-node {
  background: #f59e0b;
  box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.25);
}
.trace-connector {
  width: 2px;
  flex: 1;
  background: linear-gradient(180deg, rgba(99, 102, 241, 0.4), rgba(99, 102, 241, 0.08));
  min-height: 20px;
}
.trace-timeline-content {
  padding-bottom: 14px;
}
.trace-last .trace-timeline-content {
  padding-bottom: 0;
}
.trace-step-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.trace-step-label {
  font-weight: 700;
  font-size: 12px;
  color: var(--ui-text);
}
.trace-action-badge {
  padding: 1px 8px;
  border-radius: 6px;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: #fff;
  background: #6366f1;
}
.badge-thought {
  background: #3b82f6;
}
.badge-tool_call {
  background: #8b5cf6;
}
.badge-finish {
  background: #10b981;
}
.badge-error {
  background: #f59e0b;
}
.trace-field-label {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--ui-muted);
  margin-bottom: 3px;
  display: block;
}
.trace-thought-block p {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ui-text);
}
.trace-input-block pre,
.trace-obs-block pre {
  margin: 4px 0 0;
  padding: 6px 8px;
  border-radius: 6px;
  background: color-mix(in oklab, var(--ui-bg, #f8fafc) 90%, transparent);
  font-size: 11px;
  overflow-x: auto;
  max-height: 100px;
  border: 1px solid var(--ui-border);
}
.trace-obs-block summary {
  cursor: pointer;
  padding: 2px 0;
}
.trace-retrieval-lanes {
  margin-top: 8px;
  padding: 8px;
  border-radius: 8px;
  background: color-mix(in oklab, var(--ui-bg, #f8fafc) 80%, transparent);
  border: 1px solid var(--ui-border);
}
.retrieval-bar {
  display: flex;
  border-radius: 6px;
  overflow: hidden;
  margin-top: 6px;
  height: 24px;
  font-size: 10px;
  font-weight: 700;
}
.retrieval-lane {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  padding: 0 4px;
  transition: filter 0.2s ease;
}
.retrieval-lane:hover {
  filter: brightness(1.15);
}
.retrieval-lane.vector {
  background: #6366f1;
}
.retrieval-lane.keyword {
  background: #0ea5e9;
}
.retrieval-lane.graph {
  background: #f59e0b;
}
.retrieval-lane.web {
  background: #10b981;
}

.thinking {
  max-width: 930px;
  margin: 0 auto;
  padding: 0 20px 10px 70px;
  color: var(--ui-muted);
  font-size: 13px;
}

.composer-shell {
  position: sticky;
  bottom: 0;
  z-index: 6;
  padding: 0 16px 16px;
  background: linear-gradient(
    180deg,
    transparent 0%,
    color-mix(in oklab, var(--ui-card) 65%, transparent) 32%,
    color-mix(in oklab, var(--ui-card) 92%, transparent) 100%
  );
}

.composer {
  max-width: 930px;
  margin: 0 auto;
  border: 1px solid var(--ui-border);
  border-radius: 16px;
  background: color-mix(in oklab, var(--ui-card) 92%, transparent);
  backdrop-filter: blur(12px);
  padding: 10px;
}

.composer-footer {
  margin-top: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

/* 输入框左下工具区：深度研究开关 + 上传文档 */
.composer-left {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.composer-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

/* ChatGPT 风格工具药丸：小圆角胶囊 + 面板底色 */
.composer-tool {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-panel) 88%, transparent);
  color: var(--ui-text);
  border-radius: 999px;
  padding: 6px 11px;
  font-size: 12px;
  cursor: pointer;
}

.composer-tool:hover {
  border-color: var(--ui-accent);
}

.composer-tool.active {
  border-color: var(--ui-accent);
  color: var(--ui-accent);
  background: color-mix(in oklab, var(--ui-accent) 12%, transparent);
}

.composer-tool:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.composer-file-input {
  display: none;
}

/* 上传状态胶囊：上传中→解析中→已可提问（或失败红），完成后自动消失 */
.upload-chip {
  max-width: 930px;
  margin: 0 auto 6px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  border-radius: 999px;
  font-size: 12px;
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-panel) 90%, transparent);
  width: fit-content;
}

.upload-chip.ready {
  border-color: #16a34a;
  color: #16a34a;
}

.upload-chip.error {
  border-color: #dc2626;
  color: #dc2626;
}

.upload-chip.uploading,
.upload-chip.parsing {
  color: var(--ui-accent);
  border-color: var(--ui-accent);
}

.upload-chip-close {
  border: none;
  background: none;
  cursor: pointer;
  color: inherit;
  font-size: 14px;
  line-height: 1;
  padding: 0;
}

/* 深度研究消息徽标（bubble-meta 内） */
.research-badge {
  border: 1px solid var(--ui-accent);
  color: var(--ui-accent);
  border-radius: 999px;
  padding: 0 7px;
  font-size: 11px;
}

.composer-actions {
  display: flex;
  gap: 8px;
}

.markdown :deep(p) {
  margin: 0 0 10px;
  line-height: 1.7;
}

.markdown :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown :deep(ul),
.markdown :deep(ol) {
  margin: 0 0 10px;
  padding-left: 20px;
}

.markdown :deep(code:not(.hljs)) {
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Menlo, Monaco, Consolas, monospace;
  background: rgba(15, 23, 42, 0.1);
  border-radius: 4px;
  padding: 2px 5px;
}

.markdown :deep(.code-block) {
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  overflow: hidden;
  background: #0b1220;
  color: #e6edf7;
}

.markdown :deep(.code-toolbar) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  background: #111b2f;
  border-bottom: 1px solid rgba(148, 163, 184, 0.2);
}

.markdown :deep(.code-lang) {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #9fb2cf;
}

.markdown :deep(.copy-code-btn) {
  border: 1px solid rgba(148, 163, 184, 0.3);
  border-radius: 999px;
  padding: 4px 8px;
  font-size: 11px;
  background: rgba(15, 23, 42, 0.5);
  color: #e6edf7;
  cursor: pointer;
}

.markdown :deep(pre) {
  margin: 0;
  padding: 0;
  overflow-x: auto;
}

.markdown :deep(code.hljs) {
  display: block;
  padding: 10px 0;
  background: transparent;
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
}

.markdown :deep(.code-line) {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 10px;
  padding: 0 12px;
  white-space: pre;
}

.markdown :deep(.line-no) {
  user-select: none;
  color: rgba(148, 163, 184, 0.7);
  text-align: right;
}

.markdown :deep(.line-content) {
  min-width: 0;
}

.markdown :deep(.hljs-comment),
.markdown :deep(.hljs-quote) {
  color: #8aa0bf;
}

.markdown :deep(.hljs-keyword),
.markdown :deep(.hljs-selector-tag),
.markdown :deep(.hljs-subst) {
  color: #ff8fa3;
}

.markdown :deep(.hljs-string),
.markdown :deep(.hljs-doctag) {
  color: #8be9a8;
}

.markdown :deep(.hljs-title),
.markdown :deep(.hljs-section),
.markdown :deep(.hljs-selector-id) {
  color: #8fc7ff;
}

.markdown :deep(.hljs-number),
.markdown :deep(.hljs-literal) {
  color: #f6c177;
}

:deep(.composer .el-textarea__inner) {
  border: none;
  box-shadow: none;
  background: transparent;
  font-size: 15px;
  line-height: 1.58;
  color: var(--ui-text);
}

:deep(.composer .el-textarea__inner:focus) {
  box-shadow: none;
}

@keyframes shimmer {
  from {
    background-position: 100% 0;
  }
  to {
    background-position: -120% 0;
  }
}


@media (max-width: 980px) {
  .composer-shell {
    position: static;
  }
}

@media (max-width: 680px) {
  .welcome-block,
  .message-row,
  .thinking,
  .hydration-skeleton {
    padding-left: 12px;
    padding-right: 12px;
  }

  .message-row {
    grid-template-columns: 32px minmax(0, 1fr);
    gap: 8px;
  }

  .message-row.user {
    grid-template-columns: minmax(0, 1fr) 32px;
  }

  .avatar {
    width: 32px;
    height: 32px;
    border-radius: 8px;
  }

  .thinking {
    padding-left: 52px;
  }

  .composer-footer {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
