<template>
  <!-- 登录门闩：未登录（无 token 且无 API Key）时整页显示登录/注册，登录后才渲染控制台 -->
  <div v-if="!canUseRemoteSync" class="auth-gate">
    <div class="auth-card">
      <p class="eyebrow">KnowledgeOps Agent</p>
      <h1>{{ authMode === 'login' ? '登录' : '注册新账号' }}</h1>
      <el-tabs v-model="authMode">
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>
      <el-input
        v-model="authUsername"
        placeholder="用户名：3-32 位小写字母、数字、- 或 _"
        @keyup.enter="handlePasswordAuth"
      />
      <el-input
        v-model="authPassword"
        type="password"
        show-password
        :placeholder="authMode === 'login' ? '密码' : '密码（至少 8 位）'"
        @keyup.enter="handlePasswordAuth"
      />
      <el-button
        type="primary"
        class="auth-submit"
        :loading="authLoading"
        @click="handlePasswordAuth"
      >{{ authMode === 'login' ? '登录' : '注册并登录' }}</el-button>
      <details class="admin-key-login">
        <summary>管理员 API Key 登录</summary>
        <el-input
          v-model="apiKeyInput"
          type="password"
          show-password
          placeholder="X-API-Key（管理员用）"
        />
        <el-input v-model="tenantInput" placeholder="租户（默认 public）" />
        <el-button :loading="authLoading" @click="handleLogin">换取 JWT</el-button>
      </details>
    </div>
  </div>

  <div
    v-if="canUseRemoteSync"
    class="app-shell"
    :class="{ 'shell-with-sessions': sessionColVisible }"
  >
    <!-- 图标栏：只管"去哪个页面"，所有页签常驻 -->
    <nav class="icon-rail" role="tablist" aria-label="Console views">
      <div class="rail-brand" title="KnowledgeOps Agent">K</div>
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
        <el-tooltip v-if="isAdmin" content="管理员文档总览" placement="right" :show-after="300">
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

    <!-- 会话栏：仅聊天页显示，可折叠 -->
    <aside v-if="sessionColVisible" class="session-col">
      <button class="new-chat-btn" type="button" @click="createAndSwitchSession">+ 新建会话</button>

      <section class="session-tools">
        <el-input v-model="sessionSearch" size="small" placeholder="搜索会话标题或 ID" clearable />
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
      </section>

      <section class="session-panel">
        <div class="section-head">
          <p class="section-label">会话</p>
          <div class="branch-head-actions">
            <span class="section-meta">{{ filteredSessions.length }}/{{ sessionCount }}</span>
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
          <div
            v-for="session in filteredSessions"
            :key="session.id"
            class="session-item"
            :class="{ active: session.id === activeSessionId }"
            role="button"
            tabindex="0"
            @click="switchSession(session.id)"
            @keydown.enter.prevent="switchSession(session.id)"
          >
            <div class="session-content">
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
                {{ session.workspaceId }} · {{ formatTime(session.updatedAt) }} ·
                {{ shortId(session.id) }}
              </p>
            </div>
            <div class="session-actions">
              <button type="button" @click.stop="toggleSessionPin(session.id)">
                {{ session.pinned ? '取消置顶' : '置顶' }}
              </button>
              <button type="button" @click.stop="toggleSessionArchive(session.id)">
                {{ session.archived ? '取消归档' : '归档' }}
              </button>
              <button type="button" class="danger" @click.stop="removeSession(session.id)">
                删除
              </button>
            </div>
          </div>
          <div v-if="filteredSessions.length === 0" class="session-empty">没有匹配会话</div>
        </div>
      </section>

      <button class="collapse-col-btn" type="button" @click="sessionColCollapsed = true">
        <el-icon :size="14"><CaretLeft /></el-icon>
        收起会话栏
      </button>
    </aside>

    <main class="workspace">
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
          <h2>{{ selectedEvalDataset?.name || 'Evaluation Studio' }}</h2>
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
          <!-- 工作区：选择已有，或直接输入新名字回车即创建并切换（filterable + allow-create） -->
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
          <el-tag :type="streamStatusTagType" effect="plain">{{ streamStatusLabel }}</el-tag>
          <span class="stream-detail">{{ streamStatusDetail }}</span>
          <span v-if="costSummary" class="stream-detail"
            >成本: 本月 ${{ costSummary.monthCostUsd.toFixed(4) }} / 预算 ${{
              costSummary.monthlyBudgetUsd.toFixed(4)
            }}</span
          >
          <el-button size="small" @click="branchDrawerVisible = true">
            分支{{ activeSession?.branches.length ? ` (${activeSession.branches.length})` : '' }}
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

      <template v-if="activeView === 'chat'">
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
              <p>支持消息编辑后重发分支、流式轨迹、长会话虚拟渲染。</p>
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
                          编辑后重发分支
                        </el-button>
                      </div>
                    </div>
                    <p v-else class="plain">{{ entry.item.content }}</p>
                  </template>
                </div>

                <div class="message-actions">
                  <button type="button" @click="copyMessage(entry.item.content)">复制</button>
                  <button
                    v-if="entry.item.role === 'assistant'"
                    type="button"
                    @click="regenerateFrom(entry.index)"
                  >
                    重试分支
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
                    👍有帮助
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
                    👎待改进
                  </button>
                  <button
                    v-if="entry.item.role === 'user'"
                    type="button"
                    @click="startEditMessage(entry.item)"
                  >
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

      <section v-else-if="activeView === 'knowledge'" class="knowledge-page">
        <aside class="eval-side-panel">
          <div class="eval-panel-head">
            <div>
              <p class="section-label">文档入库</p>
              <strong>知识库</strong>
            </div>
          </div>

          <el-upload
            v-model:file-list="knowledgeUploadFiles"
            class="knowledge-uploader"
            drag
            accept=".pdf,.doc,.docx,.md"
            :auto-upload="false"
            :limit="1"
            :on-exceed="() => ElMessage.warning('一次只能传一个文件，先移除已选文件')"
          >
            <p class="uploader-title">点击或拖拽文档到这里</p>
            <p class="uploader-sub">支持 PDF / Word（doc、docx）/ Markdown，上传后自动切分、向量化并入知识库</p>
          </el-upload>

          <el-button
            type="primary"
            :loading="knowledgeUploading"
            :disabled="knowledgeUploadFiles.length === 0"
            @click="submitKnowledgeUpload"
            >提交入库</el-button
          >

          <p class="knowledge-tip">入库是异步的：提交后等状态变成 SUCCEEDED 才能被检索到；失败会自动重试。</p>
        </aside>

        <section v-loading="knowledgeLoading" class="eval-main-panel">
          <div class="knowledge-list-head">
            <p class="section-label">入库任务（最近 20 条）</p>
            <el-button size="small" @click="loadKnowledgeJobs()">刷新</el-button>
          </div>
          <el-alert
            v-if="knowledgeNeedsAuth"
            class="kb-auth-alert"
            type="info"
            show-icon
            :closable="false"
            title="登录后才能看到入库任务"
            description="请先登录（右上角退出后可重新登录）；如果登录已过期，重新登录后回来点「刷新」即可。"
          />
          <el-table :data="knowledgeJobs" height="100%" empty-text="还没有入库记录，先上传一个 PDF">
            <el-table-column prop="sourceName" label="文件" min-width="180" show-overflow-tooltip />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="重试" width="80">
              <template #default="{ row }">{{ row.attemptCount ?? 0 }}/{{ row.maxRetries ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="上传时间" width="120">
              <template #default="{ row }">{{ formatJobTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column prop="chatId" label="批次" min-width="140" show-overflow-tooltip />
            <el-table-column prop="errorMessage" label="错误" min-width="160" show-overflow-tooltip />
            <el-table-column v-if="isAdmin" label="操作" width="90">
              <template #default="{ row }">
                <el-button size="small" type="danger" link @click="removeKnowledgeJob(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </section>

      <section v-else-if="isAdmin && activeView === 'admin'" class="admin-docs-page">
        <section v-loading="adminDocsLoading" class="eval-main-panel admin-docs-main">
          <div class="knowledge-list-head">
            <p class="section-label">文档总览（跨租户）</p>
            <div class="admin-docs-toolbar">
              <el-input
                v-model="adminDocsSearch"
                size="small"
                clearable
                placeholder="搜索租户 / 批次 / 文件名"
                @keyup.enter="handleAdminSearch"
                @clear="handleAdminSearch"
              />
              <el-button size="small" @click="handleAdminSearch">搜索</el-button>
              <el-button size="small" @click="loadAdminDocuments()">刷新</el-button>
            </div>
          </div>
          <el-alert
            v-if="adminNeedsAuth"
            class="kb-auth-alert"
            type="info"
            show-icon
            :closable="false"
            title="需要 ADMIN 身份"
            description="请使用管理员账号登录后查看文档总览。"
          />
          <el-table :data="adminDocs" height="100%" empty-text="还没有任何入库文档">
            <el-table-column prop="tenantId" label="租户" min-width="120" show-overflow-tooltip />
            <el-table-column prop="sourceName" label="文件名" min-width="180" show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="尝试" width="70">
              <template #default="{ row }">{{ row.attemptCount ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="大小" width="90">
              <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
            </el-table-column>
            <el-table-column label="创建时间" width="150">
              <template #default="{ row }">{{ formatJobTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column prop="errorMessage" label="错误" min-width="140" show-overflow-tooltip />
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="danger" link @click="removeAdminDocument(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="adminDocsPage"
            :page-size="adminDocsPageSize"
            :total="adminDocsTotal"
            layout="total, prev, pager, next"
            class="admin-docs-pagination"
            @current-change="handleAdminPageChange"
          />
        </section>
      </section>

      <section v-else-if="activeView === 'usage'" class="usage-page">
        <section v-loading="usageLoading" class="eval-main-panel usage-main">
          <div class="knowledge-list-head">
            <p class="section-label">用量统计（本租户）</p>
            <div class="admin-docs-toolbar">
              <el-select v-model="usageRange" size="small" class="usage-range-select">
                <el-option label="近 7 天" :value="7" />
                <el-option label="近 14 天" :value="14" />
                <el-option label="近 30 天" :value="30" />
              </el-select>
              <el-button size="small" @click="loadUsageTrend()">刷新</el-button>
            </div>
          </div>
          <el-alert
            v-if="usageNeedsAuth"
            class="kb-auth-alert"
            type="info"
            show-icon
            :closable="false"
            title="需要登录"
            description="请先完成鉴权后查看本租户用量统计。"
          />
          <div class="usage-cards">
            <div v-for="card in usageMetricCards" :key="card.label" class="eval-metric-card">
              <span>{{ card.label }}</span>
              <strong :class="card.tone">{{ card.value }}</strong>
            </div>
          </div>
          <div class="usage-chart-block">
            <div class="usage-chart-head">
              <p class="usage-chart-title">使用趋势</p>
              <span class="usage-chart-range">近 {{ usageRange }} 天</span>
            </div>
            <svg
              v-if="usagePoints.length"
              class="usage-chart-svg"
              :viewBox="`0 0 ${usageTrendChart.W} ${usageTrendChart.H}`"
              role="img"
              aria-label="使用趋势图：输入、输出 tokens 与费用"
              @mouseleave="usageHoverIndex = null"
            >
              <line
                v-for="(tick, i) in usageTrendChart.yTicksLeft"
                :key="`grid-${i}`"
                class="grid-line"
                :x1="usageTrendChart.padL"
                :x2="usageTrendChart.W - usageTrendChart.padR"
                :y1="tick.y"
                :y2="tick.y"
              />
              <text
                v-for="(tick, i) in usageTrendChart.yTicksLeft"
                :key="`yl-${i}`"
                class="axis-text"
                :x="usageTrendChart.padL - 6"
                :y="tick.y + 3"
                text-anchor="end"
              >{{ tick.text }}</text>
              <text
                v-for="(tick, i) in usageTrendChart.yTicksRight"
                :key="`yr-${i}`"
                class="axis-text"
                :x="usageTrendChart.W - usageTrendChart.padR + 6"
                :y="tick.y + 3"
                text-anchor="start"
              >{{ tick.text }}</text>
              <path v-if="usageTrendChart.areaPath" class="area-input" :d="usageTrendChart.areaPath" />
              <polyline class="line-input" :points="usageTrendChart.inputLine" />
              <polyline class="line-output" :points="usageTrendChart.outputLine" />
              <polyline class="line-cost" :points="usageTrendChart.costLine" />
              <g v-for="day in usageTrendChart.days" :key="`xl-${day.key}`">
                <text
                  v-if="day.showXLabel"
                  class="axis-text"
                  :x="day.x"
                  :y="usageTrendChart.labelY"
                  text-anchor="middle"
                >{{ day.shortDate }}</text>
              </g>
              <g v-if="usageHoverDay" class="usage-hover">
                <line
                  class="hover-line"
                  :x1="usageHoverDay.x"
                  :x2="usageHoverDay.x"
                  :y1="usageTrendChart.padT"
                  :y2="usageTrendChart.baseY"
                />
                <rect
                  class="tooltip-box"
                  :x="usageHoverDay.boxX"
                  :y="usageHoverDay.boxY"
                  :width="usageHoverDay.boxW"
                  :height="usageHoverDay.boxH"
                  rx="8"
                />
                <text class="tooltip-title" :x="usageHoverDay.boxX + 12" :y="usageHoverDay.boxY + 22">{{ usageHoverDay.key.replaceAll('-', '/') }}</text>
                <circle class="tt-dot input" :cx="usageHoverDay.boxX + 16" :cy="usageHoverDay.boxY + 42" r="4" />
                <text class="tooltip-text" :x="usageHoverDay.boxX + 26" :y="usageHoverDay.boxY + 46">输入：{{ usageHoverDay.input.toLocaleString() }}</text>
                <circle class="tt-dot output" :cx="usageHoverDay.boxX + 16" :cy="usageHoverDay.boxY + 64" r="4" />
                <text class="tooltip-text" :x="usageHoverDay.boxX + 26" :y="usageHoverDay.boxY + 68">输出：{{ usageHoverDay.output.toLocaleString() }}</text>
                <circle class="tt-dot cost" :cx="usageHoverDay.boxX + 16" :cy="usageHoverDay.boxY + 86" r="4" />
                <text class="tooltip-text" :x="usageHoverDay.boxX + 26" :y="usageHoverDay.boxY + 90">成本：${{ usageHoverDay.cost.toFixed(6) }}</text>
              </g>
              <rect
                v-for="(day, i) in usageTrendChart.days"
                :key="`hit-${day.key}`"
                :x="day.hitX"
                :y="usageTrendChart.padT"
                :width="day.hitWidth"
                :height="usageTrendChart.plotH"
                fill="transparent"
                @mouseenter="usageHoverIndex = i"
                @mouseleave="usageHoverIndex = null"
              />
            </svg>
            <p v-else class="usage-chart-empty">暂无用量数据</p>
            <div class="usage-legend-bottom">
              <span class="usage-legend"><i class="usage-dot blue"></i>输入</span>
              <span class="usage-legend"><i class="usage-dot green"></i>输出</span>
              <span class="usage-legend"><i class="usage-dot red"></i>成本</span>
            </div>
          </div>
          <el-table :data="usagePoints" height="100%" empty-text="暂无用量数据">
            <el-table-column prop="date" label="日期" min-width="100" />
            <el-table-column label="请求数" width="90">
              <template #default="{ row }">{{ row.requestCount.toLocaleString() }}</template>
            </el-table-column>
            <el-table-column label="输入 tokens" min-width="110">
              <template #default="{ row }">{{ row.inputTokens.toLocaleString() }}</template>
            </el-table-column>
            <el-table-column label="输出 tokens" min-width="110">
              <template #default="{ row }">{{ row.outputTokens.toLocaleString() }}</template>
            </el-table-column>
            <el-table-column label="费用" width="110">
              <template #default="{ row }">${{ row.costUsd.toFixed(4) }}</template>
            </el-table-column>
          </el-table>
        </section>
      </section>

      <section v-else class="evaluation-page">
        <aside class="eval-side-panel">
          <div class="eval-panel-head">
            <div>
              <p class="section-label">评测集</p>
              <strong>{{ evalDatasets.length }}</strong>
            </div>
          </div>

          <div class="eval-dataset-list">
            <button
              v-for="dataset in evalDatasets"
              :key="dataset.datasetId"
              type="button"
              :class="{ active: dataset.datasetId === evalSelectedDatasetId }"
              @click="selectEvalDataset(dataset.datasetId)"
            >
              <span>{{ dataset.name }}</span>
              <small>{{ dataset.caseCount }} 道题 · {{ shortId(dataset.datasetId) }}</small>
            </button>
            <div v-if="!evalDatasets.length" class="session-empty">暂无评测集</div>
          </div>

          <div class="eval-create-panel">
            <p class="section-label">创建评测集</p>
            <el-input v-model="evalDatasetName" size="small" placeholder="评测集名称" />
            <el-input v-model="evalDatasetDescription" size="small" placeholder="描述" />
            <el-input
              v-model="evalDatasetJson"
              class="eval-json-input"
              type="textarea"
              :rows="11"
              resize="none"
              spellcheck="false"
            />
            <el-button
              type="primary"
              :loading="evalCreating"
              :disabled="!evalDatasetName.trim() || !evalDatasetJson.trim()"
              @click="createEvalDatasetFromJson"
              >创建评测集</el-button
            >
          </div>
        </aside>

        <section class="eval-main-panel">
          <div class="eval-score-strip">
            <div v-for="metric in evalMetricCards" :key="metric.key" class="eval-metric-card">
              <span>{{ metric.label }}</span>
              <strong>{{ metric.current }}</strong>
              <small :class="metric.deltaClass">{{ metric.delta }}</small>
            </div>
          </div>

          <div class="eval-run-grid">
            <div class="eval-run-summary">
              <p class="section-label">基线</p>
              <strong>{{ evalBaselineRun?.runId || '未设置' }}</strong>
              <span>{{ formatRunScore(evalBaselineRun?.metrics.runScore) }}</span>
            </div>
            <div class="eval-run-summary current">
              <p class="section-label">本次运行</p>
              <strong>{{ evalCurrentRun?.runId || '无' }}</strong>
              <span>{{ formatRunScore(evalCurrentRun?.metrics.runScore) }}</span>
            </div>
          </div>

          <el-table
            :data="evalCurrentRun?.results ?? []"
            class="eval-result-table"
            height="100%"
            empty-text="暂无评测结果"
          >
            <el-table-column type="expand" width="44">
              <template #default="{ row }">
                <div class="eval-expand">
                  <div class="eval-expand-scores">
                    <span>检索命中 {{ formatPercent(row.retrievalHit) }}</span>
                    <span>引用覆盖 {{ formatPercent(row.citationCoverage) }}</span>
                    <span>关键词 {{ formatPercent(row.keywordScore) }}</span>
                    <span>忠实度 {{ formatPercent(row.answerFaithfulness) }}</span>
                  </div>
                  <p class="eval-expand-label">模型回答</p>
                  <div class="eval-expand-answer">{{ row.answer || '（无回答）' }}</div>
                  <template v-if="row.citations?.length">
                    <p class="eval-expand-label">引用来源</p>
                    <ul class="eval-expand-citations">
                      <li v-for="(cite, citeIndex) in row.citations" :key="citeIndex">{{ cite }}</li>
                    </ul>
                  </template>
                  <p v-if="row.errorMessage" class="eval-expand-error">失败原因：{{ row.errorMessage }}</p>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="caseId" label="题目ID" min-width="120" />
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column label="得分" width="110">
              <template #default="{ row }">{{ formatPercent(row.score) }}</template>
            </el-table-column>
            <el-table-column label="引用" width="120">
              <template #default="{ row }">{{ formatPercent(row.citationCoverage) }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="120">
              <template #default="{ row }">{{ row.latencyMs }}ms</template>
            </el-table-column>
            <el-table-column
              prop="question"
              label="问题"
              min-width="280"
              show-overflow-tooltip
            />
          </el-table>
        </section>
      </section>
    </main>

    <!-- 鉴权与模型：低频配置收进弹窗（原侧栏 ops-panel） -->
    <el-dialog v-model="opsDialogVisible" title="鉴权与模型" width="520px">
      <div class="ops-body">
        <el-form label-position="top" size="small">
          <el-form-item label="API Key">
            <el-input
              v-model="apiKeyInput"
              placeholder="输入 API Key（生产建议短时使用）"
              show-password
              type="password"
            />
          </el-form-item>
          <el-form-item label="Tenant (可选)">
            <el-input v-model="tenantInput" placeholder="public" />
          </el-form-item>
          <el-form-item label="Model Profile">
            <el-select v-model="modelProfile" class="full-width">
              <el-option label="economy（经济档 qwen-turbo）" value="economy" />
              <el-option label="balanced（均衡档 qwen-plus）" value="balanced" />
              <el-option label="quality（质量档 qwen-max）" value="quality" />
              <el-option label="ab_auto（A/B 自动对比实验）" value="ab_auto" />
              <el-option label="quality_first（固定最高档）" value="quality_first" />
              <el-option label="cost_first（固定最低档）" value="cost_first" />
            </el-select>
          </el-form-item>
          <el-form-item label="Agent 引擎（主聊天）">
            <el-radio-group v-model="agentEngine">
              <el-radio-button value="standard">标准 ReAct</el-radio-button>
              <el-radio-button value="workflow">工作流引擎</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <p class="engine-hint">
            {{
              agentEngine === 'workflow'
                ? '工作流引擎：每一步全留痕可回放、轨迹逐轮实时推送；但不读写会话记忆。'
                : '标准 ReAct：主聊天默认引擎，带会话记忆。'
            }}
          </p>
          <el-form-item label="响应模式">
            <el-switch v-model="streaming" inline-prompt active-text="SSE" inactive-text="JSON" />
          </el-form-item>
        </el-form>
        <div class="auth-buttons">
          <el-tag v-if="role" size="small" :type="isAdmin ? 'danger' : 'info'">{{ role }}</el-tag>
          <el-button type="primary" :loading="authLoading" @click="handleLogin">换取 JWT</el-button>
          <el-button :disabled="!refreshToken" :loading="refreshing" @click="handleRefresh"
            >刷新</el-button
          >
          <el-button @click="logout">退出登录</el-button>
        </div>
      </div>
    </el-dialog>

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
  </div>
</template>

<script setup lang="ts">
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/core';
import bashLang from 'highlight.js/lib/languages/bash';
import javaLang from 'highlight.js/lib/languages/java';
import javascriptLang from 'highlight.js/lib/languages/javascript';
import jsonLang from 'highlight.js/lib/languages/json';
import markdownLang from 'highlight.js/lib/languages/markdown';
import pythonLang from 'highlight.js/lib/languages/python';
import sqlLang from 'highlight.js/lib/languages/sql';
import typescriptLang from 'highlight.js/lib/languages/typescript';
import xmlLang from 'highlight.js/lib/languages/xml';
import yamlLang from 'highlight.js/lib/languages/yaml';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { UploadUserFile } from 'element-plus';
import {
  CaretLeft,
  CaretRight,
  ChatDotRound,
  Compass,
  DataAnalysis,
  Download,
  FolderOpened,
  Notebook,
  Paperclip,
  Setting,
  TrendCharts,
  Upload,
} from '@element-plus/icons-vue';
import { marked } from 'marked';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { Ref } from 'vue';
import {
  compareSessionBranches,
  createEvalDataset,
  createResearchTask,
  deleteAdminDocument,
  deleteIngestionDocument,
  exchangeApiKey,
  exportEvalRunReport,
  getEvalComparison,
  getTenantCostSummary,
  getTenantCostTrend,
  listAdminDocuments,
  listEvalDatasets,
  listRecentIngestionJobs,
  listWorkflowTasks,
  markEvalRunBaseline,
  listSessionStates,
  loginWithPassword,
  mergeSessionBranches,
  reactChat,
  refreshJwt,
  registerUser,
  saveSessionState,
  setSessionArchived,
  setSessionPinned,
  streamReactChat,
  submitAnswerFeedback,
  triggerEvalRun,
  uploadIngestionDocument,
} from './api/client';
import type {
  AdminDocumentSummary,
  AgentEngine,
  EvalCaseCreate,
  EvalComparison,
  EvalDataset,
  EvalMetricSummary,
  EvalRun,
  IngestionJob,
  IngestionJobStatus,
  ReactChatResponse,
  ReactErrorEvent,
  ReactTokenEvent,
  ReactTraceStep,
  SessionState,
  TenantCostSummary,
  TenantCostTrendPoint,
} from './types/react';

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: number;
  citations?: string[];
  evidence?: string[];
  state?: 'pending' | 'streaming' | 'done' | 'error' | 'stopped';
  kind?: 'research'; // 深度研究产生的助手消息（打徽标用）
}

interface SessionBranch {
  id: string;
  title: string;
  parentBranchId: string | null;
  parentMessageId: string | null;
  updatedAt: number;
  messages: ChatMessage[];
  traceSteps: ReactTraceStep[];
}

interface SessionRecord {
  id: string;
  title: string;
  updatedAt: number;
  modelProfile: string;
  streaming: boolean;
  pinned: boolean;
  archived: boolean;
  workspaceId: string;
  activeBranchId: string;
  branches: SessionBranch[];
}

interface BranchTreeItem {
  branch: SessionBranch;
  depth: number;
}

interface MessageMetric {
  item: ChatMessage;
  index: number;
  offset: number;
  height: number;
}

type StreamPhase = 'idle' | 'thinking' | 'tool' | 'streaming' | 'done' | 'error' | 'stopped';
type ConsoleView = 'chat' | 'evaluation' | 'knowledge' | 'admin' | 'usage';

interface EvalMetricCard {
  key: keyof EvalMetricSummary;
  label: string;
  current: string;
  delta: string;
  deltaClass: string;
}

const STORAGE_KEY = 'knowledgeops-agent-react-console-v2';
const LEGACY_STORAGE_KEY = 'knowledgeops-agent-react-console';
const DEFAULT_SYSTEM_MESSAGE =
  '欢迎使用 ReAct 控制台。你可以先输入 API Key 获取 JWT，然后发起带轨迹的问答。';
const DEFAULT_WORKSPACE = 'default';
const ESTIMATED_ROW_HEIGHT = 156;
const OVERSCAN_COUNT = 8;
const DEFAULT_EVAL_DATASET = [
  {
    caseId: 'rag_001',
    category: 'rag_recall',
    chatId: 'eval-rag-a',
    question: '根据知识库，课程预约需要哪些字段？',
    expectedKeywords: ['课程', '姓名', '联系方式', '校区'],
    forbiddenKeywords: ['我不知道', '无法回答'],
  },
  {
    caseId: 'rag_002',
    category: 'rag_precision',
    chatId: 'eval-rag-b',
    question: '请总结这个 PDF 里和高温健康风险相关的内容。',
    expectedKeywords: ['高温', '风险'],
    forbiddenKeywords: ['与问题无关', '瞎编'],
  },
  {
    caseId: 'rag_003',
    category: 'citation_coverage',
    chatId: 'eval-rag-b',
    question: '回答时列出引用来源，并说明高温风险处置建议。',
    expectedKeywords: ['引用', '高温', '风险'],
    expectedCitations: ['heat'],
  },
];

hljs.registerLanguage('bash', bashLang);
hljs.registerLanguage('java', javaLang);
hljs.registerLanguage('javascript', javascriptLang);
hljs.registerLanguage('json', jsonLang);
hljs.registerLanguage('markdown', markdownLang);
hljs.registerLanguage('python', pythonLang);
hljs.registerLanguage('sql', sqlLang);
hljs.registerLanguage('typescript', typescriptLang);
hljs.registerLanguage('xml', xmlLang);
hljs.registerLanguage('yaml', yamlLang);

function safeParse(raw: string | null): Record<string, unknown> {
  if (!raw) {
    return {};
  }
  try {
    return JSON.parse(raw) as Record<string, unknown>;
  } catch {
    return {};
  }
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function toBase64(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

function fromBase64(value: string): string {
  const binary = atob(value);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

// 剪贴板兜底：navigator.clipboard 只在 HTTPS（或 localhost）下存在，
// 线上是 HTTP 裸 IP 访问，该接口直接是 undefined；用隐藏文本框 +
// execCommand 的老办法不受这个限制，两种环境都能复制成功
async function writeClipboardText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // 权限被拒等场景，落到下面的老办法
    }
  }
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.select();
  try {
    if (!document.execCommand('copy')) {
      throw new Error('copy command failed');
    }
  } finally {
    document.body.removeChild(textarea);
  }
}

const renderer = new marked.Renderer();
renderer.code = ((token: { text: string; lang?: string }) => {
  const rawCode = token.text ?? '';
  const lang = token.lang?.trim().toLowerCase().split(/\s+/)[0] ?? 'plaintext';
  const language = hljs.getLanguage(lang) ? lang : 'plaintext';
  const highlighted =
    language === 'plaintext'
      ? escapeHtml(rawCode)
      : hljs.highlight(rawCode, { language, ignoreIllegals: true }).value;

  const lines = highlighted.split('\n');
  const numbered = lines
    .map((line, index) => {
      const content = line || '&nbsp;';
      return `<span class="code-line"><span class="line-no">${index + 1}</span><span class="line-content">${content}</span></span>`;
    })
    .join('');

  const payload = escapeHtml(toBase64(rawCode));

  return `<div class="code-block"><div class="code-toolbar"><span class="code-lang">${language}</span><button class="copy-code-btn" type="button" data-code="${payload}">复制代码</button></div><pre><code class="hljs language-${language}">${numbered}</code></pre></div>`;
}) as typeof renderer.code;

marked.use({
  gfm: true,
  breaks: true,
  renderer,
});

function createChatId(): string {
  const suffix = Math.random().toString(36).slice(2, 8);
  return `react-${Date.now()}-${suffix}`;
}

function createBranchId(): string {
  const suffix = Math.random().toString(36).slice(2, 8);
  return `branch-${Date.now()}-${suffix}`;
}

function createMessage(role: ChatMessage['role'], content: string): ChatMessage {
  return {
    id: `${role}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content,
    createdAt: Date.now(),
    state: 'done',
  };
}

function normalizeMessage(raw: unknown): ChatMessage {
  const candidate = (raw ?? {}) as Partial<ChatMessage>;
  const role = candidate.role === 'assistant' ? 'assistant' : 'user';
  return {
    id: candidate.id || `${role}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content: typeof candidate.content === 'string' ? candidate.content : '',
    createdAt: typeof candidate.createdAt === 'number' ? candidate.createdAt : Date.now(),
    citations: Array.isArray(candidate.citations)
      ? candidate.citations.map((item) => String(item).trim()).filter(Boolean)
      : [],
    evidence: Array.isArray(candidate.evidence)
      ? candidate.evidence.map((item) => String(item).trim()).filter(Boolean)
      : [],
    state: candidate.state || 'done',
    kind: candidate.kind === 'research' ? 'research' : undefined,
  };
}

function deriveTitle(text: string): string {
  const clean = text.trim().replace(/\s+/g, ' ');
  if (!clean) {
    return '新会话';
  }
  return clean.length > 28 ? `${clean.slice(0, 28)}...` : clean;
}

function createRootBranch(): SessionBranch {
  return {
    id: createBranchId(),
    title: '主分支',
    parentBranchId: null,
    parentMessageId: null,
    updatedAt: Date.now(),
    messages: [createMessage('assistant', DEFAULT_SYSTEM_MESSAGE)],
    traceSteps: [],
  };
}

function normalizeBranch(raw: unknown): SessionBranch {
  const candidate = (raw ?? {}) as Partial<SessionBranch>;
  const messages = Array.isArray(candidate.messages)
    ? candidate.messages.map(normalizeMessage)
    : [createMessage('assistant', DEFAULT_SYSTEM_MESSAGE)];

  return {
    id: candidate.id || createBranchId(),
    title: candidate.title || '分支',
    parentBranchId: candidate.parentBranchId ?? null,
    parentMessageId: candidate.parentMessageId ?? null,
    updatedAt: typeof candidate.updatedAt === 'number' ? candidate.updatedAt : Date.now(),
    messages,
    traceSteps: Array.isArray(candidate.traceSteps) ? candidate.traceSteps : [],
  };
}

function normalizeSession(raw: unknown): SessionRecord {
  const candidate = (raw ?? {}) as Record<string, unknown>;
  let branches: SessionBranch[] = [];

  if (Array.isArray(candidate.branches) && candidate.branches.length > 0) {
    branches = candidate.branches.map((item) => normalizeBranch(item));
  } else {
    const fallbackMessages = Array.isArray(candidate.messages)
      ? candidate.messages.map(normalizeMessage)
      : [createMessage('assistant', DEFAULT_SYSTEM_MESSAGE)];

    branches = [
      {
        id: createBranchId(),
        title: '主分支',
        parentBranchId: null,
        parentMessageId: null,
        updatedAt: typeof candidate.updatedAt === 'number' ? candidate.updatedAt : Date.now(),
        messages: fallbackMessages,
        traceSteps: Array.isArray(candidate.traceSteps)
          ? (candidate.traceSteps as ReactTraceStep[])
          : [],
      },
    ];
  }

  const activeBranchId =
    typeof candidate.activeBranchId === 'string' ? candidate.activeBranchId : branches[0].id;

  return {
    id: typeof candidate.id === 'string' ? candidate.id : createChatId(),
    title: typeof candidate.title === 'string' ? candidate.title : '新会话',
    updatedAt: typeof candidate.updatedAt === 'number' ? candidate.updatedAt : Date.now(),
    modelProfile: typeof candidate.modelProfile === 'string' ? candidate.modelProfile : 'balanced',
    streaming: Boolean(candidate.streaming ?? true),
    pinned: Boolean(candidate.pinned),
    archived: Boolean(candidate.archived),
    workspaceId:
      typeof candidate.workspaceId === 'string' ? candidate.workspaceId : DEFAULT_WORKSPACE,
    activeBranchId,
    branches,
  };
}

function createSession(): SessionRecord {
  const id = createChatId();
  const rootBranch = createRootBranch();

  return {
    id,
    title: '新会话',
    updatedAt: Date.now(),
    modelProfile: 'balanced',
    streaming: true,
    pinned: false,
    archived: false,
    workspaceId: DEFAULT_WORKSPACE,
    activeBranchId: rootBranch.id,
    branches: [rootBranch],
  };
}

function formatTime(value: number): string {
  return new Date(value).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  });
}

function shortId(id: string): string {
  return id.slice(0, 10);
}

// 模块级钩子：对 LLM 输出中渲染出的所有带 target="_blank" 的链接
// 强制添加 rel="noopener noreferrer"。否则被提示词注入的响应可能
// 打开新标签页，新标签页的 JS 就能回访原页面的 window.opener.location
// （反向 tabnabbing 攻击）。该钩子在模块加载时注册一次；DOMPurify 的
// 钩子按事件名作为键并会覆盖先前的注册，因此在多次重渲染间是安全的。
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A' && node.getAttribute('target') === '_blank') {
    const existing = (node.getAttribute('rel') || '').toLowerCase();
    const merged = new Set(existing.split(/\s+/).filter(Boolean));
    merged.add('noopener');
    merged.add('noreferrer');
    node.setAttribute('rel', Array.from(merged).join(' '));
  }
});

function renderMarkdown(content: string): string {
  if (!content?.trim()) {
    return '<p>等待模型输出...</p>';
  }
  const html = marked.parse(content) as string;
  // 纵深防御：显式禁止内联事件处理器、javascript: URL 以及
  // 未带 rel=noopener 的 target=_blank。DOMPurify 本身已清除
  // 危险形式（script、onerror、javascript:），但默认配置会保留
  // target 等少数属性，这足以让 LLM 输出渲染出的链接
  // 成为反向 tabnabbing 攻击的入口。
  return DOMPurify.sanitize(html, {
    ADD_ATTR: ['data-code'],
    ALLOWED_ATTR: [
      'href',
      'title',
      'alt',
      'src',
      'name',
      'target',
      'rel',
      'class',
      'id',
      'data-code',
      'data-line',
      'colspan',
      'rowspan',
      'align',
    ],
    FORBID_ATTR: ['style', 'onload', 'onclick', 'onerror', 'onmouseover'],
    FORBID_TAGS: ['style', 'iframe', 'object', 'embed', 'form', 'input'],
  });
}

const cached = safeParse(localStorage.getItem(STORAGE_KEY));
const legacy = safeParse(localStorage.getItem(LEGACY_STORAGE_KEY));
const bootstrap = Object.keys(cached).length > 0 ? cached : legacy;

const darkMode = ref(Boolean(bootstrap.darkMode));
const activeView = ref<ConsoleView>(
  ['evaluation', 'knowledge', 'admin', 'usage'].includes(bootstrap.activeView as string)
    ? (bootstrap.activeView as ConsoleView)
    : 'chat',
);
const apiKeyInput = ref((bootstrap.apiKey as string | undefined) ?? '');
const tenantInput = ref((bootstrap.tenantId as string | undefined) ?? '');
const token = ref((bootstrap.token as string | undefined) ?? '');
const refreshToken = ref((bootstrap.refreshToken as string | undefined) ?? '');
// 登录用户的首个角色（ADMIN/USER），控制管理员 UI 显隐
const role = ref((bootstrap.role as string | undefined) ?? '');
const authMode = ref<'login' | 'register'>('login');
const authUsername = ref('');
const authPassword = ref('');
const isAdmin = computed(() => role.value === 'ADMIN');
// 会话栏只在聊天页且未折叠时占一列；其它页签由内容区占满整行
const sessionColVisible = computed(() => activeView.value === 'chat' && !sessionColCollapsed.value);
const sessionSearch = ref((bootstrap.sessionSearch as string | undefined) ?? '');
const workspaceFilter = ref((bootstrap.workspaceFilter as string | undefined) ?? 'all');
const showArchivedSessions = ref(Boolean(bootstrap.showArchivedSessions));
// 会话栏折叠状态（仅聊天页生效），随其它界面偏好一起持久化
const sessionColCollapsed = ref(Boolean(bootstrap.sessionColCollapsed));
// 鉴权与模型弹窗 / 分支树抽屉：临时 UI 状态，不持久化
const opsDialogVisible = ref(false);
const branchDrawerVisible = ref(false);

const sessions = ref<SessionRecord[]>(
  Array.isArray(bootstrap.sessions) && bootstrap.sessions.length > 0
    ? (bootstrap.sessions as unknown[]).map((item) => normalizeSession(item))
    : [createSession()],
);

const activeSessionId = ref(
  (bootstrap.activeSessionId as string | undefined) ?? sessions.value[0].id,
);

const activeSession = computed(() => {
  const found = sessions.value.find((item) => item.id === activeSessionId.value);
  return found ?? sessions.value[0];
});

const activeBranch = computed(() => {
  const session = activeSession.value;
  return (
    session.branches.find((branch) => branch.id === session.activeBranchId) ?? session.branches[0]
  );
});

const chatId = ref(activeSession.value.id);
const modelProfile = ref(activeSession.value.modelProfile);
const streaming = ref(activeSession.value.streaming);
const messages = ref<ChatMessage[]>([...activeBranch.value.messages]);
const traceSteps = ref<ReactTraceStep[]>([...activeBranch.value.traceSteps]);
const traceDurationMs = computed(() => {
  if (!traceSteps.value.length) return 0;
  // 若无真实耗时数据，按每步约 2 秒粗略估算
  return traceSteps.value.length * 2000;
});

const authLoading = ref(false);
const refreshing = ref(false);
const sending = ref(false);
const isStreamingResponse = ref(false);
const hydrating = ref(true);
const prompt = ref('');
const messageContainer = ref<HTMLElement | null>(null);
const currentAbortController = ref<AbortController | null>(null);
const editingMessageId = ref<string | null>(null);
const editingMessageDraft = ref('');
const streamPhase = ref<StreamPhase>('idle');
const streamStatusDetail = ref('');
const cloudSyncing = ref(false);
const costSummary = ref<TenantCostSummary | null>(null);
const answerFeedbackMap = ref<Record<string, number>>({});
const answerFeedbackLoading = ref<Record<string, boolean>>({});
const evalDatasets = ref<EvalDataset[]>([]);
const evalSelectedDatasetId = ref((bootstrap.evalSelectedDatasetId as string | undefined) ?? '');
const evalComparison = ref<EvalComparison | null>(null);
const evalLoading = ref(false);
const evalCreating = ref(false);
const evalRunning = ref(false);
const evalReportExporting = ref(false);
const evalDatasetName = ref('RAG Evaluation Studio Demo');
const evalDatasetDescription = ref('RAG baseline regression set');
const evalDatasetJson = ref(JSON.stringify(DEFAULT_EVAL_DATASET, null, 2));
const knowledgeJobs = ref<IngestionJob[]>([]);
const knowledgeLoading = ref(false);
const knowledgeUploading = ref(false);
const knowledgeUploadFiles = ref<UploadUserFile[]>([]);
// 未登录 / 登录过期时不弹报错，改在页面里给一句提示
const knowledgeNeedsAuth = ref(false);
// 管理员文档总览（跨租户）：列表数据 + 分页/搜索 + 未登录提示
const adminDocs = ref<AdminDocumentSummary[]>([]);
const adminDocsLoading = ref(false);
const adminDocsTotal = ref(0);
const adminDocsPage = ref(1);
const adminDocsPageSize = ref(20);
const adminDocsSearch = ref('');
const adminNeedsAuth = ref(false);
// Agent 引擎：standard = 主聊天标准 ReAct；workflow = 工作流版（步骤全留痕可回放，不读写会话记忆）
const agentEngine = ref<AgentEngine>(bootstrap.agentEngine === 'workflow' ? 'workflow' : 'standard');

// ---------- 深度研究（聊天输入框模式，开关不持久化） ----------
const researchMode = ref(false); // 开着时，下一次「发送」改走深度研究
const researchRunning = ref(false); // 当前 in-flight 的发送是否为深度研究

// ---------- 聊天输入框上传文档（进知识库，非文档限定问答） ----------
const composerFileInput = ref<HTMLInputElement | null>(null);
const composerUploading = ref(false);
type ComposerChipKind = 'uploading' | 'parsing' | 'ready' | 'error';
interface ComposerUploadChip {
  kind: ComposerChipKind;
  name: string;
  chatId: string;
  text: string;
}
const composerUploadChip = ref<ComposerUploadChip | null>(null);
let composerUploadPollTimer: number | null = null;
let composerChipDismissTimer: number | null = null;

// ---------- 用量（本租户 token 统计 + 每日趋势） ----------
const usageRange = ref<7 | 14 | 30>(14);
const usagePoints = ref<TenantCostTrendPoint[]>([]);
const usageLoading = ref(false);
const usageNeedsAuth = ref(false);

// 趋势图为手写 SVG 双轴折线图（无图表库），固定 viewBox 随容器等比缩放：左轴 tokens 右轴费用 $
const USAGE_CHART_W = 720;
const USAGE_CHART_H = 260;

// 指标卡直接复用右上角同一份 costSummary 数据，不重复请求
const usageMetricCards = computed(() => {
  const summary = costSummary.value;
  return [
    { label: '本月费用', value: summary ? `$${summary.monthCostUsd.toFixed(4)}` : '—', tone: 'neutral' },
    {
      label: '预算余量',
      value: summary ? `$${summary.budgetRemainingUsd.toFixed(4)}` : '—',
      tone: summary?.budgetExceeded ? 'bad' : 'neutral',
    },
    { label: '本月请求', value: summary ? summary.monthRequestCount.toLocaleString() : '—', tone: 'neutral' },
    { label: '本月输入 token', value: summary ? summary.monthInputTokens.toLocaleString() : '—', tone: 'neutral' },
    { label: '本月输出 token', value: summary ? summary.monthOutputTokens.toLocaleString() : '—', tone: 'neutral' },
    { label: '今日费用', value: summary ? `$${summary.todayCostUsd.toFixed(4)}` : '—', tone: 'neutral' },
  ];
});

/** 向上取整到 1/2/2.5/5 × 10^k，让 y 轴刻度是整数。 */
function niceCeil(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const magnitude = 10 ** Math.floor(Math.log10(value));
  for (const multiple of [1, 2, 2.5, 5, 10]) {
    if (value <= multiple * magnitude) {
      return multiple * magnitude;
    }
  }
  return 10 * magnitude;
}

const usageHoverIndex = ref<number | null>(null);

/** y 轴刻度：≥1万 用「万」，≥1000 用 k，其余原样。 */
function formatTokenTick(value: number): string {
  if (value >= 10000) {
    const wan = value / 10000;
    return `${Number.isInteger(wan) ? wan : wan.toFixed(1)}万`;
  }
  if (value >= 1000) {
    return `${Math.round(value / 100) / 10}k`;
  }
  return String(value);
}

// 单图双轴：左轴输入/输出 tokens（蓝/绿实线），右轴费用 $（红色虚线），悬停出竖参考线+提示框
const usageTrendChart = computed(() => {
  const padL = 52;
  const padR = 52;
  const padT = 14;
  const padB = 26;
  const plotH = USAGE_CHART_H - padT - padB;
  const plotW = USAGE_CHART_W - padL - padR;
  const baseY = padT + plotH;
  const points = usagePoints.value;
  const count = Math.max(1, points.length);
  const band = plotW / count;
  // 三条线各自独立（不堆叠），tokens 轴取输入/输出中的较大者；全零时除零保护
  const tokensMax = niceCeil(Math.max(1, ...points.flatMap((point) => [point.inputTokens, point.outputTokens])));
  const costMax = niceCeil(Math.max(0.01, ...points.map((point) => point.costUsd)));
  const xAt = (index: number) => padL + index * band + band / 2;
  const yTok = (value: number) => baseY - (value / tokensMax) * plotH;
  const yCost = (value: number) => baseY - (value / costMax) * plotH;
  const joinPts = (coords: Array<{ x: number; y: number }>) => coords.map((d) => `${d.x},${d.y}`).join(' ');
  const inputPts = points.map((point, index) => ({ x: xAt(index), y: yTok(point.inputTokens) }));
  const fracs = [0, 0.25, 0.5, 0.75, 1];
  return {
    W: USAGE_CHART_W,
    H: USAGE_CHART_H,
    padL,
    padR,
    padT,
    plotH,
    baseY,
    inputLine: joinPts(inputPts),
    outputLine: joinPts(points.map((point, index) => ({ x: xAt(index), y: yTok(point.outputTokens) }))),
    costLine: joinPts(points.map((point, index) => ({ x: xAt(index), y: yCost(point.costUsd) }))),
    areaPath:
      inputPts.length >= 2
        ? `M ${inputPts[0].x} ${baseY} L ${inputPts.map((d) => `${d.x} ${d.y}`).join(' L ')} L ${inputPts[inputPts.length - 1].x} ${baseY} Z`
        : '',
    yTicksLeft: fracs.map((frac) => ({ text: formatTokenTick(tokensMax * frac), y: yTok(tokensMax * frac) })),
    yTicksRight: fracs.map((frac) => ({ text: formatCostTick(costMax * frac), y: yCost(costMax * frac) })),
    labelY: baseY + 16,
    days: points.map((point, index) => ({
      key: point.date,
      x: xAt(index),
      hitX: padL + index * band,
      hitWidth: band,
      showXLabel: count <= 7 || index % (count > 20 ? 5 : 2) === 0,
      shortDate: point.date.slice(5).replace('-', '/'),
      input: point.inputTokens,
      output: point.outputTokens,
      cost: point.costUsd,
    })),
  };
});

/** 悬停日 → 提示框几何（靠右时翻到竖线左边），null 表示未悬停。 */
const usageHoverDay = computed(() => {
  const index = usageHoverIndex.value;
  if (index === null) {
    return null;
  }
  const chart = usageTrendChart.value;
  const day = chart.days[index];
  if (!day) {
    return null;
  }
  const boxW = 176;
  const boxH = 104;
  const flip = day.x > chart.W - chart.padR - boxW - 12;
  return {
    ...day,
    boxW,
    boxH,
    boxX: flip ? day.x - boxW - 12 : day.x + 12,
    boxY: chart.padT + 4,
  };
});

/** 费用刻度：≥1 美元两位小数，≥1 美分两位小数，更小用三位小数。 */
function formatCostTick(value: number): string {
  if (value >= 1) {
    return `$${value.toFixed(2)}`;
  }
  if (value >= 0.01) {
    return `$${value.toFixed(2)}`;
  }
  return `$${value.toFixed(3)}`;
}

async function loadUsageTrend(silent = false): Promise<void> {
  if (!token.value && !apiKeyInput.value) {
    // 压根没登录过，别去打接口，页面里提示即可
    usageNeedsAuth.value = true;
    usagePoints.value = [];
    return;
  }
  if (!silent) {
    usageLoading.value = true;
  }
  try {
    usagePoints.value = await getTenantCostTrend(usageRange.value, authContext());
    usageNeedsAuth.value = false;
  } catch (error) {
    if (isAuthError(error)) {
      // 登录过期（JWT 两小时失效），页面里提示，不弹报错
      usageNeedsAuth.value = true;
      usagePoints.value = [];
    } else if (!silent) {
      const message = error instanceof Error ? error.message : '用量趋势加载失败';
      ElMessage.error(message);
    }
  } finally {
    if (!silent) {
      usageLoading.value = false;
    }
  }
}

watch(usageRange, () => {
  void loadUsageTrend();
});

function isAuthError(error: unknown): boolean {
  return error instanceof Error && error.message.startsWith('HTTP 401');
}

const messageHeights = ref<Record<string, number>>({});
const viewportHeight = ref(0);
const scrollTop = ref(0);
const messageRowElements = new Map<string, HTMLElement>();
let resizeObserver: ResizeObserver | null = null;
let streamResetTimer: number | null = null;

const sessionCount = computed(() => sessions.value.length);

const canUseRemoteSync = computed(() => Boolean(token.value || apiKeyInput.value.trim()));

const selectedEvalDataset = computed(() =>
  evalDatasets.value.find((dataset) => dataset.datasetId === evalSelectedDatasetId.value),
);

const evalCurrentRun = computed<EvalRun | null>(() => evalComparison.value?.current ?? null);

const evalBaselineRun = computed<EvalRun | null>(() => evalComparison.value?.baseline ?? null);

const evalMetricCards = computed<EvalMetricCard[]>(() => {
  const current = evalCurrentRun.value?.metrics;
  const baseline = evalBaselineRun.value?.metrics;
  return [
    metricCard('runScore', '总分', current, baseline, 'percent'),
    metricCard('retrievalHitRate', '检索命中率', current, baseline, 'percent'),
    metricCard('citationCoverageRate', '引用覆盖率', current, baseline, 'percent'),
    metricCard('answerFaithfulnessScore', '忠实度', current, baseline, 'percent'),
    metricCard('avgLatencyMs', '平均耗时', current, baseline, 'ms', true),
    metricCard('failureRate', '失败率', current, baseline, 'percent', true),
  ];
});

const workspaceOptions = computed(() => {
  const options = new Set<string>([DEFAULT_WORKSPACE]);
  sessions.value.forEach((session) => {
    options.add(session.workspaceId || DEFAULT_WORKSPACE);
  });
  return [...options].sort((a, b) => a.localeCompare(b, 'zh-CN'));
});

const activeWorkspaceId = computed({
  get: () => activeSession.value.workspaceId,
  set: (value: string) => {
    activeSession.value.workspaceId = value || DEFAULT_WORKSPACE;
    persistState();
  },
});

const orderedSessions = computed(() =>
  [...sessions.value].sort((a, b) => {
    if (a.pinned !== b.pinned) {
      return Number(b.pinned) - Number(a.pinned);
    }
    return b.updatedAt - a.updatedAt;
  }),
);

const filteredSessions = computed(() => {
  const keyword = sessionSearch.value.trim().toLowerCase();
  return orderedSessions.value.filter((session) => {
    if (!showArchivedSessions.value && session.archived) {
      return false;
    }

    if (workspaceFilter.value !== 'all' && session.workspaceId !== workspaceFilter.value) {
      return false;
    }

    if (!keyword) {
      return true;
    }

    return (
      session.title.toLowerCase().includes(keyword) || session.id.toLowerCase().includes(keyword)
    );
  });
});

const branchTreeItems = computed<BranchTreeItem[]>(() => {
  const session = activeSession.value;
  const children = new Map<string | null, SessionBranch[]>();

  session.branches.forEach((branch) => {
    const key = branch.parentBranchId ?? null;
    if (!children.has(key)) {
      children.set(key, []);
    }
    children.get(key)?.push(branch);
  });

  children.forEach((list) => {
    list.sort((a, b) => b.updatedAt - a.updatedAt);
  });

  const result: BranchTreeItem[] = [];

  function dfs(parentId: string | null, depth: number): void {
    const list = children.get(parentId) ?? [];
    list.forEach((branch) => {
      result.push({ branch, depth });
      dfs(branch.id, depth + 1);
    });
  }

  dfs(null, 0);
  return result;
});

const isEmptyConversation = computed(() => {
  const nonSystem = messages.value.filter((item) => item.role === 'user');
  return nonSystem.length === 0;
});

const messageMetrics = computed<MessageMetric[]>(() => {
  let offset = 0;
  return messages.value.map((item, index) => {
    const height = messageHeights.value[item.id] ?? ESTIMATED_ROW_HEIGHT;
    const metric = {
      item,
      index,
      offset,
      height,
    };
    offset += height;
    return metric;
  });
});

const totalVirtualHeight = computed(() => {
  const metrics = messageMetrics.value;
  if (metrics.length === 0) {
    return 0;
  }
  const last = metrics[metrics.length - 1];
  return last.offset + last.height;
});

function findMetricIndexByOffset(targetOffset: number): number {
  const metrics = messageMetrics.value;
  if (metrics.length === 0) {
    return 0;
  }

  let left = 0;
  let right = metrics.length - 1;
  let answer = metrics.length - 1;

  while (left <= right) {
    const mid = (left + right) >> 1;
    const metric = metrics[mid];
    if (metric.offset + metric.height >= targetOffset) {
      answer = mid;
      right = mid - 1;
    } else {
      left = mid + 1;
    }
  }

  return answer;
}

const virtualRange = computed(() => {
  const total = messages.value.length;
  if (total === 0) {
    return { start: 0, end: -1 };
  }

  const startAnchor = Math.max(0, scrollTop.value - viewportHeight.value * 0.8);
  const endAnchor = scrollTop.value + viewportHeight.value * 1.8;

  const start = Math.max(0, findMetricIndexByOffset(startAnchor) - OVERSCAN_COUNT);
  const end = Math.min(total - 1, findMetricIndexByOffset(endAnchor) + OVERSCAN_COUNT);

  return { start, end };
});

const virtualMessages = computed(() => {
  const metrics = messageMetrics.value;
  const { start, end } = virtualRange.value;
  if (end < start) {
    return [];
  }
  return metrics.slice(start, end + 1);
});

const virtualTopSpacer = computed(() => virtualMessages.value[0]?.offset ?? 0);

const virtualBottomSpacer = computed(() => {
  if (virtualMessages.value.length === 0) {
    return 0;
  }

  const last = virtualMessages.value[virtualMessages.value.length - 1];
  return Math.max(0, totalVirtualHeight.value - (last.offset + last.height));
});

const streamStatusLabel = computed(() => {
  switch (streamPhase.value) {
    case 'thinking':
      return '思考中';
    case 'tool':
      return '工具调用中';
    case 'streaming':
      return '输出中';
    case 'done':
      return '已完成';
    case 'error':
      return '失败';
    case 'stopped':
      return '已停止';
    default:
      return '空闲';
  }
});

const streamStatusTagType = computed(() => {
  switch (streamPhase.value) {
    case 'thinking':
      return 'warning';
    case 'tool':
      return 'success';
    case 'streaming':
      return 'primary';
    case 'done':
      return 'success';
    case 'error':
      return 'danger';
    case 'stopped':
      return 'info';
    default:
      return 'info';
  }
});

function scheduleStreamReset(): void {
  if (streamResetTimer) {
    window.clearTimeout(streamResetTimer);
  }
  streamResetTimer = window.setTimeout(() => {
    streamPhase.value = 'idle';
    streamStatusDetail.value = '';
    streamResetTimer = null;
  }, 1800);
}

function persistState(): void {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      darkMode: darkMode.value,
      activeView: activeView.value,
      apiKey: apiKeyInput.value,
      tenantId: tenantInput.value,
      token: token.value,
      refreshToken: refreshToken.value,
      role: role.value,
      agentEngine: agentEngine.value,
      evalSelectedDatasetId: evalSelectedDatasetId.value,
      activeSessionId: activeSessionId.value,
      sessionSearch: sessionSearch.value,
      workspaceFilter: workspaceFilter.value,
      showArchivedSessions: showArchivedSessions.value,
      sessionColCollapsed: sessionColCollapsed.value,
      sessions: sessions.value,
    }),
  );
}

function authContext() {
  return {
    token: token.value || undefined,
    apiKey: apiKeyInput.value || undefined,
    tenantId: tenantInput.value || undefined,
  };
}

function activateView(view: ConsoleView): void {
  activeView.value = view;
  persistState();
  if (view === 'evaluation' && evalDatasets.value.length === 0 && !evalLoading.value) {
    void loadEvalDatasets();
  }
  if (view === 'knowledge' && !knowledgeLoading.value) {
    void loadKnowledgeJobs();
  }
  if (view === 'admin' && isAdmin.value) {
    void loadAdminDocuments();
  }
  if (view === 'usage') {
    void loadUsageTrend();
  }
}

// ---------- 知识库（文档入库） ----------

function statusTagType(status: IngestionJobStatus): 'success' | 'danger' | 'warning' | 'info' | 'primary' {
  switch (status) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'RETRY':
      return 'warning';
    case 'RUNNING':
      return 'primary';
    default:
      return 'info';
  }
}

function formatJobTime(value: string | undefined): string {
  if (!value) {
    return '-';
  }
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

async function loadKnowledgeJobs(silent = false): Promise<void> {
  if (!token.value && !apiKeyInput.value) {
    // 压根没登录过，别去打接口，页面里提示即可
    knowledgeNeedsAuth.value = true;
    knowledgeJobs.value = [];
    return;
  }
  if (!silent) {
    knowledgeLoading.value = true;
  }
  try {
    knowledgeJobs.value = await listRecentIngestionJobs(authContext());
    knowledgeNeedsAuth.value = false;
  } catch (error) {
    if (isAuthError(error)) {
      // 登录过期（JWT 两小时失效），页面里提示，不弹报错
      knowledgeNeedsAuth.value = true;
      knowledgeJobs.value = [];
    } else if (!silent) {
      const message = error instanceof Error ? error.message : '入库任务加载失败';
      ElMessage.error(message);
    }
  } finally {
    if (!silent) {
      knowledgeLoading.value = false;
    }
  }
}

// 上传批次的 chatId：doc-年月日-时分秒，知识库页和输入框上传共用
function mintIngestionChatId(): string {
  const stamp = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `doc-${stamp.getFullYear()}${pad(stamp.getMonth() + 1)}${pad(stamp.getDate())}-${pad(stamp.getHours())}${pad(stamp.getMinutes())}${pad(stamp.getSeconds())}`;
}

async function submitIngestionUpload(
  files: Ref<UploadUserFile[]>,
  loading: Ref<boolean>,
  opts?: { afterUpload?: (chatId: string) => void },
): Promise<void> {
  const file = files.value[0]?.raw;
  if (!(file instanceof File)) {
    ElMessage.warning('请先选择一个文档文件（支持 PDF / Word / Markdown）');
    return;
  }
  loading.value = true;
  try {
    // 每次上传独立批次：chatId 用时间戳生成，入库任务按批次隔离
    const chatId = mintIngestionChatId();
    const result = await uploadIngestionDocument(chatId, file, authContext());
    ElMessage.success(`已提交入库任务 ${shortId(result.job?.jobId ?? '')}，切分入库需要一点时间`);
    files.value = [];
    opts?.afterUpload?.(chatId);
  } catch (error) {
    const message = error instanceof Error ? error.message : '上传失败';
    ElMessage.error(message);
  } finally {
    loading.value = false;
  }
}

function submitKnowledgeUpload(): Promise<void> {
  return submitIngestionUpload(knowledgeUploadFiles, knowledgeUploading, {
    afterUpload: () => {
      void loadKnowledgeJobs(true);
      startKnowledgePolling();
    },
  });
}

async function removeKnowledgeJob(row: IngestionJob): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.sourceName}」？将同时清掉它的向量切片和原文件，不可恢复。`,
      '删除文档',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return; // 用户点了取消
  }
  try {
    const msg = await deleteIngestionDocument(row.chatId, authContext());
    ElMessage.success(msg || '已删除');
    await loadKnowledgeJobs(true);
  } catch (error) {
    const message = error instanceof Error ? error.message : '删除失败';
    ElMessage.error(message);
  }
}

// ---------- 管理员文档总览（跨租户） ----------

async function loadAdminDocuments(silent = false): Promise<void> {
  if (!isAdmin.value) {
    return;
  }
  if (!token.value && !apiKeyInput.value) {
    // 压根没登录过，别去打接口，页面里提示即可
    adminNeedsAuth.value = true;
    adminDocs.value = [];
    return;
  }
  if (!silent) {
    adminDocsLoading.value = true;
  }
  try {
    const result = await listAdminDocuments(authContext(), {
      page: adminDocsPage.value,
      pageSize: adminDocsPageSize.value,
      search: adminDocsSearch.value.trim() || undefined,
    });
    adminDocs.value = result.items;
    adminDocsTotal.value = result.total;
    adminNeedsAuth.value = false;
  } catch (error) {
    if (isAuthError(error)) {
      // 登录过期（JWT 两小时失效），页面里提示，不弹报错
      adminNeedsAuth.value = true;
      adminDocs.value = [];
    } else if (!silent) {
      const message = error instanceof Error ? error.message : '文档总览加载失败';
      ElMessage.error(message);
    }
  } finally {
    if (!silent) {
      adminDocsLoading.value = false;
    }
  }
}

function handleAdminSearch(): void {
  adminDocsPage.value = 1;
  void loadAdminDocuments();
}

function handleAdminPageChange(page: number): void {
  adminDocsPage.value = page;
  void loadAdminDocuments();
}

function formatFileSize(size: number | null | undefined): string {
  if (size === null || size === undefined) {
    return '-';
  }
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

async function removeAdminDocument(row: AdminDocumentSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除租户 ${row.tenantId} 的「${row.sourceName}」？将同时清掉它的向量切片和原文件，不可恢复。`,
      '删除文档',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return; // 用户点了取消
  }
  try {
    const msg = await deleteAdminDocument(row.tenantId, row.chatId, authContext());
    ElMessage.success(msg || '已删除');
    // 当前页删得只剩这一条且不是第一页时回退一页，避免停在空页上
    if (adminDocs.value.length === 1 && adminDocsPage.value > 1) {
      adminDocsPage.value -= 1;
    }
    await loadAdminDocuments(true);
  } catch (error) {
    const message = error instanceof Error ? error.message : '删除失败';
    ElMessage.error(message);
  }
}

// 有进行中的任务时每 3 秒刷新列表，全部到终态自动停表
let knowledgePollTimer: number | null = null;

function startKnowledgePolling(): void {
  if (knowledgePollTimer !== null) {
    return;
  }
  knowledgePollTimer = window.setInterval(() => {
    const hasActive = knowledgeJobs.value.some(
      (job) => job.status === 'PENDING' || job.status === 'RUNNING' || job.status === 'RETRY',
    );
    if (!hasActive) {
      stopKnowledgePolling();
      return;
    }
    if (activeView.value === 'knowledge') {
      void loadKnowledgeJobs(true);
    }
  }, 3000);
}

function stopKnowledgePolling(): void {
  if (knowledgePollTimer !== null) {
    window.clearInterval(knowledgePollTimer);
    knowledgePollTimer = null;
  }
}

// ---------- 聊天输入框上传文档（进知识库，非文档限定问答） ----------

function triggerComposerUpload(): void {
  composerFileInput.value?.click();
}

async function onComposerFileChosen(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = ''; // 允许下次重选同一个文件
  if (!file) {
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先登录后再上传文档到知识库');
    return;
  }
  if (!/\.(pdf|docx?|md)$/i.test(file.name)) {
    ElMessage.warning('支持 PDF / Word（doc、docx）/ Markdown 文件');
    return;
  }
  if (
    composerUploadChip.value &&
    (composerUploadChip.value.kind === 'uploading' || composerUploadChip.value.kind === 'parsing')
  ) {
    ElMessage.warning('已有文件在解析中，稍等片刻再传');
    return;
  }
  await submitComposerUpload(file);
}

async function submitComposerUpload(file: File): Promise<void> {
  const chatId = mintIngestionChatId();
  composerUploadChip.value = { kind: 'uploading', name: file.name, chatId, text: '上传中…' };
  composerUploading.value = true;
  try {
    await uploadIngestionDocument(chatId, file, authContext());
    composerUploadChip.value = { kind: 'parsing', name: file.name, chatId, text: '解析中…' };
    startComposerUploadPolling();
  } catch (error) {
    const message = error instanceof Error ? error.message : '上传失败';
    composerUploadChip.value = { kind: 'error', name: file.name, chatId, text: message };
    scheduleComposerChipDismiss(8000);
  } finally {
    composerUploading.value = false;
  }
}

// 每 3 秒盯一次入库任务，把 解析中→已可提问/失败 刷到胶囊上
function startComposerUploadPolling(): void {
  stopComposerUploadPolling();
  let ticks = 0;
  composerUploadPollTimer = window.setInterval(() => {
    void (async () => {
      ticks += 1;
      const chip = composerUploadChip.value;
      if (!chip || chip.kind !== 'parsing') {
        stopComposerUploadPolling();
        return;
      }
      if (ticks > 100) {
        // 约 5 分钟兜底：别让胶囊永远转下去
        composerUploadChip.value = { ...chip, kind: 'error', text: '解析超时，可到知识库页查看状态' };
        stopComposerUploadPolling();
        scheduleComposerChipDismiss(8000);
        return;
      }
      try {
        const jobs = await listRecentIngestionJobs(authContext(), 20);
        const job = jobs.find((item) => item.chatId === chip.chatId);
        if (!job) {
          return; // 任务还没出现在列表里，等下一轮
        }
        if (job.status === 'SUCCEEDED') {
          composerUploadChip.value = { ...chip, kind: 'ready', text: '已入库，照常提问即可检索到' };
          stopComposerUploadPolling();
          scheduleComposerChipDismiss(5000);
        } else if (job.status === 'FAILED') {
          composerUploadChip.value = { ...chip, kind: 'error', text: job.errorMessage || '解析失败' };
          stopComposerUploadPolling();
          scheduleComposerChipDismiss(8000);
        }
      } catch (error) {
        if (isAuthError(error)) {
          composerUploadChip.value = { ...chip, kind: 'error', text: '登录已过期，重新登录后可见结果' };
          stopComposerUploadPolling();
          scheduleComposerChipDismiss(8000);
        }
        // 其他轮询失败不打断，等下一轮
      }
    })();
  }, 3000);
}

function stopComposerUploadPolling(): void {
  if (composerUploadPollTimer !== null) {
    window.clearInterval(composerUploadPollTimer);
    composerUploadPollTimer = null;
  }
}

function scheduleComposerChipDismiss(delay = 5000): void {
  if (composerChipDismissTimer !== null) {
    window.clearTimeout(composerChipDismissTimer);
  }
  composerChipDismissTimer = window.setTimeout(dismissComposerUploadChip, delay);
}

function dismissComposerUploadChip(): void {
  stopComposerUploadPolling();
  if (composerChipDismissTimer !== null) {
    window.clearTimeout(composerChipDismissTimer);
    composerChipDismissTimer = null;
  }
  composerUploadChip.value = null;
}

// ---------- 深度研究 ----------

// 工作流任务状态 → 中文进度文案
function researchStatusLabel(status: string | undefined): string {
  switch (status) {
    case 'CREATED':
      return '已创建';
    case 'PLANNING':
      return '正在规划';
    case 'SEARCHING':
      return '正在检索';
    case 'RETRIEVING':
      return '正在召回';
    case 'WRITING':
      return '正在撰写';
    case 'DONE':
      return '已完成';
    case 'FAILED':
      return '失败';
    default:
      return status || '-';
  }
}

// 研究进行中时每 3 秒盯一次任务列表，把中间状态回调给气泡文案
let researchPollTimer: number | null = null;

function stopResearchPolling(): void {
  if (researchPollTimer !== null) {
    window.clearInterval(researchPollTimer);
    researchPollTimer = null;
  }
}

function startResearchPolling(onStatus: (label: string) => void): void {
  stopResearchPolling();
  researchPollTimer = window.setInterval(() => {
    void (async () => {
      try {
        const tasks = await listWorkflowTasks(authContext(), 1, 20);
        const running = tasks.find(
          (task) => task.type === 'DEEP_RESEARCH' && task.status !== 'DONE' && task.status !== 'FAILED',
        );
        if (running) {
          onStatus(researchStatusLabel(running.status));
        }
      } catch {
        // 轮询失败不打断主流程，等下一轮
      }
    })();
  }, 3000);
}

// 输入框发起的深度研究：报告直接落到当前会话，当普通消息持久化
async function runResearchInChat(question: string): Promise<void> {
  if (!question || sending.value) {
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先登录后再发起深度研究');
    return;
  }

  sanitizeMessageStates();

  const assistantMsg: ChatMessage = {
    ...createMessage('assistant', ''),
    citations: [],
    evidence: [],
    state: 'pending',
    kind: 'research',
  };
  messages.value.push(createMessage('user', question));
  messages.value.push(assistantMsg);

  // 研究不是流式接口，清掉旧 trace 时间线，避免上一条的回答过程挂在研究气泡下面
  traceSteps.value = [];
  sending.value = true;
  researchRunning.value = true;
  isStreamingResponse.value = true;
  prompt.value = '';
  streamPhase.value = 'thinking';
  streamStatusDetail.value = '深度研究：任务排队中';

  syncCurrentSessionBranch();
  persistState();
  await scrollToBottom(true);

  // 创建接口是同步阻塞的（研究做完才返回报告），轮询器同时把
  // 规划→检索→召回→撰写 的中间状态刷到气泡里
  const controller = new AbortController();
  currentAbortController.value = controller;
  startResearchPolling((label) => {
    streamStatusDetail.value = `深度研究：${label}`;
    if (assistantMsg.state === 'pending') {
      assistantMsg.content = `**深度研究进行中：${label}**\n\n> 规划 → 检索 → 召回 → 撰写，全程约 1-3 分钟。`;
    }
  });

  try {
    const result = await createResearchTask(
      question,
      modelProfile.value,
      authContext(),
      controller.signal,
    );
    stopResearchPolling();
    if (result.status === 'FAILED' || !(result.report ?? '').trim()) {
      assistantMsg.content = `深度研究失败：后端返回「${researchStatusLabel(result.status)}」，且没有报告内容。`;
      assistantMsg.state = 'error';
      streamPhase.value = 'error';
      streamStatusDetail.value = '深度研究失败';
      ElMessage.error('深度研究任务失败');
    } else {
      assistantMsg.content = formatResearchReport(result.report ?? '');
      assistantMsg.state = 'done';
      streamPhase.value = 'done';
      streamStatusDetail.value = '深度研究完成';
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      assistantMsg.content = assistantMsg.content.trim() || '深度研究已手动停止（后端任务仍会跑完并计费）。';
      assistantMsg.state = 'stopped';
      streamPhase.value = 'stopped';
      streamStatusDetail.value = '你手动停止了本次研究';
    } else {
      const message = error instanceof Error ? error.message : 'research failed';
      assistantMsg.content = `深度研究失败：${message}`;
      assistantMsg.state = 'error';
      streamPhase.value = 'error';
      streamStatusDetail.value = message;
      ElMessage.error(message);
    }
  } finally {
    stopResearchPolling();
    sending.value = false;
    researchRunning.value = false;
    isStreamingResponse.value = false;
    currentAbortController.value = null;

    syncCurrentSessionBranch();
    persistState();
    await scrollToBottom(true);

    if (
      streamPhase.value === 'done' ||
      streamPhase.value === 'error' ||
      streamPhase.value === 'stopped'
    ) {
      scheduleStreamReset();
    }
  }
}

// 模型偶尔会直接吐 JSON 字符串当报告（economy 档实测如此），也可能是正经 Markdown；
// 检测到 JSON 就转成分节可读文本，其余原样交给渲染器
function formatResearchReport(raw: string): string {
  const text = (raw ?? '').trim();
  if (!text.startsWith('{') && !text.startsWith('[')) {
    return text;
  }
  try {
    return researchJsonToMarkdown(JSON.parse(text) as unknown);
  } catch {
    return text;
  }
}

function researchJsonToMarkdown(value: unknown): string {
  if (typeof value === 'string') {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map((item) => `- ${researchInlineValue(item)}`).join('\n');
  }
  if (value && typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([key, val]) => {
        if (Array.isArray(val)) {
          return `### ${key}\n${val.map((item) => `- ${researchInlineValue(item)}`).join('\n')}`;
        }
        return `### ${key}\n${researchInlineValue(val)}`;
      })
      .join('\n\n');
  }
  return String(value ?? '');
}

function researchInlineValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '-';
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}

function metricCard(
  key: keyof EvalMetricSummary,
  label: string,
  current: EvalMetricSummary | undefined,
  baseline: EvalMetricSummary | undefined,
  unit: 'percent' | 'ms',
  lowerIsBetter = false,
): EvalMetricCard {
  const currentValue = current?.[key];
  const baselineValue = baseline?.[key];
  const hasCurrent = typeof currentValue === 'number';
  const hasBaseline = typeof baselineValue === 'number';
  let delta = 'baseline -';
  let deltaClass = 'neutral';
  if (hasCurrent && hasBaseline) {
    const diff = currentValue - baselineValue;
    const good = lowerIsBetter ? diff <= 0 : diff >= 0;
    delta = `${diff >= 0 ? '+' : ''}${formatMetricValue(diff, unit)}`;
    deltaClass = good ? 'good' : 'bad';
  }
  return {
    key,
    label,
    current: hasCurrent ? formatMetricValue(currentValue, unit) : '-',
    delta,
    deltaClass,
  };
}

function formatMetricValue(value: number, unit: 'percent' | 'ms'): string {
  if (unit === 'ms') {
    return `${value.toFixed(0)}ms`;
  }
  return formatPercent(value);
}

function formatPercent(value: number | undefined): string {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return '-';
  }
  return `${(value * 100).toFixed(1)}%`;
}

function formatRunScore(value: number | undefined): string {
  return typeof value === 'number' ? formatPercent(value) : '-';
}

function normalizeEvalCase(raw: Record<string, unknown>, index: number): EvalCaseCreate {
  const expectedKeywords = raw.expectedKeywords ?? raw.expected_keywords;
  const expectedCitations = raw.expectedCitations ?? raw.expected_citations;
  const forbiddenKeywords = raw.forbiddenKeywords ?? raw.forbidden_keywords;
  return {
    caseId: String(raw.caseId ?? raw.id ?? `case-${index + 1}`).trim(),
    category: String(raw.category ?? 'rag').trim(),
    chatId: String(raw.chatId ?? raw.chat_id ?? '').trim(),
    question: String(raw.question ?? '').trim(),
    expectedKeywords: Array.isArray(expectedKeywords) ? expectedKeywords.map(String) : [],
    expectedCitations: Array.isArray(expectedCitations) ? expectedCitations.map(String) : [],
    forbiddenKeywords: Array.isArray(forbiddenKeywords) ? forbiddenKeywords.map(String) : [],
  };
}

function parseEvalDatasetJson(): EvalCaseCreate[] {
  const parsed = JSON.parse(evalDatasetJson.value) as unknown;
  let rawCases: unknown[] = [];
  if (Array.isArray(parsed)) {
    rawCases = parsed;
  } else if (parsed && typeof parsed === 'object') {
    const objectValue = parsed as Record<string, unknown>;
    if (Array.isArray(objectValue.cases)) {
      rawCases = objectValue.cases;
    } else if (objectValue.paths && typeof objectValue.paths === 'object') {
      Object.values(objectValue.paths as Record<string, unknown>).forEach((pathValue) => {
        if (pathValue && typeof pathValue === 'object') {
          const cases = (pathValue as Record<string, unknown>).cases;
          if (Array.isArray(cases)) {
            rawCases.push(...cases);
          }
        }
      });
    }
  }

  const cases = rawCases
    .filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object'))
    .map(normalizeEvalCase)
    .filter((item) => item.question);
  if (cases.length === 0) {
    throw new Error('评测集 JSON 没有可用 case');
  }
  return cases;
}

async function loadEvalDatasets(): Promise<void> {
  evalLoading.value = true;
  try {
    evalDatasets.value = await listEvalDatasets(authContext());
    if (!evalSelectedDatasetId.value && evalDatasets.value.length > 0) {
      evalSelectedDatasetId.value = evalDatasets.value[0].datasetId;
    }
    if (evalSelectedDatasetId.value) {
      await loadEvalComparison(evalSelectedDatasetId.value);
    }
    persistState();
  } catch (error) {
    const message = error instanceof Error ? error.message : '评测集加载失败';
    ElMessage.error(message);
  } finally {
    evalLoading.value = false;
  }
}

async function loadEvalComparison(datasetId: string): Promise<void> {
  try {
    evalComparison.value = await getEvalComparison(datasetId, authContext());
  } catch (error) {
    evalComparison.value = null;
    const message = error instanceof Error ? error.message : '评测结果加载失败';
    ElMessage.error(message);
  }
}

async function selectEvalDataset(datasetId: string): Promise<void> {
  evalSelectedDatasetId.value = datasetId;
  persistState();
  await loadEvalComparison(datasetId);
}

async function createEvalDatasetFromJson(): Promise<void> {
  evalCreating.value = true;
  try {
    const created = await createEvalDataset(
      {
        name: evalDatasetName.value.trim(),
        description: evalDatasetDescription.value.trim(),
        cases: parseEvalDatasetJson(),
      },
      authContext(),
    );
    evalDatasets.value = [created, ...evalDatasets.value];
    evalSelectedDatasetId.value = created.datasetId;
    await loadEvalComparison(created.datasetId);
    persistState();
    ElMessage.success('评测集已创建');
  } catch (error) {
    const message = error instanceof Error ? error.message : '评测集创建失败';
    ElMessage.error(message);
  } finally {
    evalCreating.value = false;
  }
}

async function runSelectedEvalDataset(): Promise<void> {
  if (!evalSelectedDatasetId.value) {
    return;
  }
  evalRunning.value = true;
  try {
    const run = await triggerEvalRun(
      evalSelectedDatasetId.value,
      {
        modelProfile: modelProfile.value,
        chatIdPrefix: 'eval-studio',
      },
      authContext(),
    );
    evalComparison.value = {
      dataset: selectedEvalDataset.value ??
        evalComparison.value?.dataset ?? {
          datasetId: run.datasetId,
          tenantId: run.tenantId,
          name: run.datasetId,
          caseCount: run.metrics.totalCases,
          createdAt: run.createdAt,
          updatedAt: run.createdAt,
        },
      baseline: evalComparison.value?.baseline ?? null,
      current: run,
    };
    ElMessage.success('评测完成');
  } catch (error) {
    const message = error instanceof Error ? error.message : '评测运行失败';
    ElMessage.error(message);
  } finally {
    evalRunning.value = false;
  }
}

async function markCurrentEvalRunBaseline(): Promise<void> {
  const run = evalCurrentRun.value;
  if (!run) {
    return;
  }
  try {
    await markEvalRunBaseline(run.runId, authContext());
    await loadEvalComparison(run.datasetId);
    ElMessage.success('Baseline 已更新');
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Baseline 更新失败';
    ElMessage.error(message);
  }
}

async function downloadEvalReport(): Promise<void> {
  const run = evalCurrentRun.value;
  if (!run) {
    return;
  }
  evalReportExporting.value = true;
  try {
    const report = await exportEvalRunReport(run.runId, authContext());
    const blob = new Blob([report], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `rag-evaluation-${run.runId}.md`;
    link.click();
    URL.revokeObjectURL(url);
    ElMessage.success('报告已导出');
  } catch (error) {
    const message = error instanceof Error ? error.message : '报告导出失败';
    ElMessage.error(message);
  } finally {
    evalReportExporting.value = false;
  }
}

async function refreshCostSummary(): Promise<void> {
  if (!canUseRemoteSync.value) {
    costSummary.value = null;
    return;
  }
  try {
    costSummary.value = await getTenantCostSummary(authContext());
  } catch {
    // 即使成本查询接口不可用，也保持界面可用。
  }
}

function normalizeRemoteSession(raw: unknown): SessionRecord {
  return normalizeSession(raw);
}

async function loadSessionsFromCloud(): Promise<void> {
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再同步');
    return;
  }
  cloudSyncing.value = true;
  try {
    const page = await listSessionStates(authContext(), {
      page: 1,
      pageSize: 200,
      includeArchived: true,
    });
    if (Array.isArray(page.items) && page.items.length > 0) {
      sessions.value = page.items.map((item) => normalizeRemoteSession(item));
      const current =
        sessions.value.find((item) => item.id === activeSessionId.value) ?? sessions.value[0];
      loadSession(current.id);
      persistState();
    }
    await refreshCostSummary();
    ElMessage.success('已从云端加载会话');
  } catch (error) {
    const message = error instanceof Error ? error.message : '云端拉取失败';
    ElMessage.error(message);
  } finally {
    cloudSyncing.value = false;
  }
}

async function syncActiveSessionToCloud(): Promise<void> {
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再同步');
    return;
  }
  syncCurrentSessionBranch();
  cloudSyncing.value = true;
  try {
    const saved = await saveSessionState(
      activeSession.value as unknown as SessionState,
      authContext(),
    );
    const normalized = normalizeRemoteSession(saved);
    const index = sessions.value.findIndex((item) => item.id === normalized.id);
    if (index >= 0) {
      sessions.value[index] = normalized;
    } else {
      sessions.value.unshift(normalized);
    }
    loadSession(normalized.id);
    persistState();
    await refreshCostSummary();
    ElMessage.success('当前会话已保存到云端');
  } catch (error) {
    const message = error instanceof Error ? error.message : '云端保存失败';
    ElMessage.error(message);
  } finally {
    cloudSyncing.value = false;
  }
}

function getSession(sessionId: string): SessionRecord | undefined {
  return sessions.value.find((item) => item.id === sessionId);
}

function getBranch(session: SessionRecord, branchId: string): SessionBranch | undefined {
  return session.branches.find((branch) => branch.id === branchId);
}

function syncCurrentSessionBranch(): void {
  const session = getSession(activeSessionId.value);
  if (!session) {
    return;
  }

  const branch = getBranch(session, session.activeBranchId);
  if (!branch) {
    return;
  }

  session.modelProfile = modelProfile.value;
  session.streaming = streaming.value;

  branch.messages = [...messages.value];
  branch.traceSteps = [...traceSteps.value];
  branch.updatedAt = Date.now();

  const firstUser = branch.messages.find((item) => item.role === 'user');
  if (firstUser?.content?.trim()) {
    branch.title = deriveTitle(firstUser.content);
    session.title = deriveTitle(firstUser.content);
  }

  session.updatedAt = Date.now();
}

function loadSession(sessionId: string): void {
  const session = getSession(sessionId);
  if (!session) {
    return;
  }

  activeSessionId.value = session.id;
  chatId.value = session.id;
  modelProfile.value = session.modelProfile;
  streaming.value = session.streaming;

  const branch = getBranch(session, session.activeBranchId) ?? session.branches[0];
  if (!branch) {
    const rootBranch = createRootBranch();
    session.branches = [rootBranch];
    session.activeBranchId = rootBranch.id;
    messages.value = [...rootBranch.messages];
    traceSteps.value = [...rootBranch.traceSteps];
  } else {
    messages.value = [...branch.messages];
    traceSteps.value = [...branch.traceSteps];
  }

  messageHeights.value = {};
  prompt.value = '';
  editingMessageId.value = null;
  editingMessageDraft.value = '';
  void scrollToBottom(true);
}

function switchSession(sessionId: string): void {
  if (sessionId === activeSessionId.value) {
    return;
  }

  syncCurrentSessionBranch();
  loadSession(sessionId);
  persistState();
}

function createAndSwitchSession(): void {
  syncCurrentSessionBranch();
  const session = createSession();
  sessions.value.unshift(session);
  loadSession(session.id);
  persistState();
}

function removeSession(sessionId: string): void {
  if (sessions.value.length <= 1) {
    ElMessage.warning('至少保留一个会话');
    return;
  }

  syncCurrentSessionBranch();
  const filtered = sessions.value.filter((item) => item.id !== sessionId);
  sessions.value = filtered;

  if (activeSessionId.value === sessionId) {
    const next = filtered.find((item) => !item.archived) ?? filtered[0];
    loadSession(next.id);
  }

  persistState();
}

async function toggleSessionPin(sessionId: string): Promise<void> {
  const session = getSession(sessionId);
  if (!session) {
    return;
  }
  session.pinned = !session.pinned;
  session.updatedAt = Date.now();
  persistState();
  if (canUseRemoteSync.value) {
    try {
      await setSessionPinned(sessionId, session.pinned, authContext());
    } catch (error) {
      const message = error instanceof Error ? error.message : '会话置顶同步失败';
      ElMessage.error(message);
    }
  }
}

async function toggleSessionArchive(sessionId: string): Promise<void> {
  const session = getSession(sessionId);
  if (!session) {
    return;
  }

  session.archived = !session.archived;
  session.updatedAt = Date.now();

  if (session.archived && !showArchivedSessions.value && activeSessionId.value === sessionId) {
    const next =
      sessions.value.find((item) => item.id !== sessionId && !item.archived) ??
      sessions.value.find((item) => item.id !== sessionId) ??
      createSession();

    if (!sessions.value.find((item) => item.id === next.id)) {
      sessions.value.unshift(next);
    }

    loadSession(next.id);
  }

  persistState();
  if (canUseRemoteSync.value) {
    try {
      await setSessionArchived(sessionId, session.archived, authContext());
    } catch (error) {
      const message = error instanceof Error ? error.message : '会话归档同步失败';
      ElMessage.error(message);
    }
  }
}

/**
 * 工作区下拉变更：选已有项直接切；allow-create 输入的新名字在这里落地——
 * 规范化（去空白/转小写）后把当前会话挪过去，并把会话栏过滤器同步到该工作区。
 */
function handleWorkspaceChange(value: string): void {
  const normalized = (value ?? '').trim().toLowerCase();
  if (!normalized) {
    return;
  }
  if (normalized !== value) {
    // 输入的新名字可能带空格/大写：纠正回规范化值再落库
    activeWorkspaceId.value = normalized;
  }
  workspaceFilter.value = normalized;
  persistState();
}

function switchBranch(branchId: string): void {
  const session = activeSession.value;
  if (session.activeBranchId === branchId) {
    return;
  }

  syncCurrentSessionBranch();
  session.activeBranchId = branchId;
  loadSession(session.id);
  persistState();
}

function forkBranch(
  title: string,
  baseMessages: ChatMessage[],
  parentBranchId: string | null,
  parentMessageId: string | null,
): SessionBranch {
  return {
    id: createBranchId(),
    title,
    parentBranchId,
    parentMessageId,
    updatedAt: Date.now(),
    messages: [...baseMessages],
    traceSteps: [],
  };
}

function forkFromCurrent(): void {
  const session = activeSession.value;
  const current = activeBranch.value;

  const branch = forkBranch(`${current.title} · fork`, [...messages.value], current.id, null);

  session.branches.unshift(branch);
  session.activeBranchId = branch.id;
  loadSession(session.id);
  persistState();
  ElMessage.success('已创建分支');
}

async function compareWithParent(): Promise<void> {
  const current = activeBranch.value;
  if (!current?.parentBranchId) {
    ElMessage.warning('当前分支没有父分支可对比');
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再执行云端分支对比');
    return;
  }
  syncCurrentSessionBranch();
  try {
    await syncActiveSessionToCloud();
    const result = await compareSessionBranches(
      activeSession.value.id,
      {
        sourceBranchId: current.id,
        targetBranchId: current.parentBranchId,
      },
      authContext(),
    );
    ElMessage.success(
      `对比完成：公共 ${result.commonMessageCount}，当前独有 ${result.sourceOnlyCount}，父分支独有 ${result.targetOnlyCount}`,
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : '分支对比失败';
    ElMessage.error(message);
  }
}

async function mergeIntoParent(): Promise<void> {
  const current = activeBranch.value;
  if (!current?.parentBranchId) {
    ElMessage.warning('当前分支没有父分支可合并');
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再执行云端分支合并');
    return;
  }
  syncCurrentSessionBranch();
  try {
    await syncActiveSessionToCloud();
    const result = await mergeSessionBranches(
      activeSession.value.id,
      {
        sourceBranchId: current.id,
        targetBranchId: current.parentBranchId,
        title: `${current.title} -> ${current.parentBranchId} merge`,
      },
      authContext(),
    );
    const normalized = normalizeRemoteSession(result.session);
    const index = sessions.value.findIndex((item) => item.id === normalized.id);
    if (index >= 0) {
      sessions.value[index] = normalized;
    } else {
      sessions.value.unshift(normalized);
    }
    loadSession(normalized.id);
    persistState();
    ElMessage.success(`合并完成，分支消息数 ${result.mergedMessageCount}`);
  } catch (error) {
    const message = error instanceof Error ? error.message : '分支合并失败';
    ElMessage.error(message);
  }
}

function startEditMessage(message: ChatMessage): void {
  if (message.role !== 'user') {
    return;
  }

  editingMessageId.value = message.id;
  editingMessageDraft.value = message.content;
}

function cancelEditMessage(): void {
  editingMessageId.value = null;
  editingMessageDraft.value = '';
}

async function submitEditAndResend(messageIndex: number, messageId: string): Promise<void> {
  const question = editingMessageDraft.value.trim();
  if (!question) {
    return;
  }

  if (sending.value) {
    ElMessage.warning('请等待当前请求完成');
    return;
  }

  syncCurrentSessionBranch();

  const session = activeSession.value;
  const current = activeBranch.value;
  const baseMessages = messages.value.slice(0, Math.max(0, messageIndex));
  const branch = forkBranch(`${deriveTitle(question)} · edit`, baseMessages, current.id, messageId);

  session.branches.unshift(branch);
  session.activeBranchId = branch.id;
  loadSession(session.id);

  cancelEditMessage();
  persistState();

  await ask(question, true);
}

function upsertTrace(step: ReactTraceStep): void {
  const index = traceSteps.value.findIndex((item) => item.step === step.step);
  if (index >= 0) {
    traceSteps.value[index] = step;
  } else {
    traceSteps.value.push(step);
    traceSteps.value.sort((a, b) => a.step - b.step);
  }
}

function updateViewport(): void {
  viewportHeight.value = messageContainer.value?.clientHeight ?? 0;
}

function onMessageScroll(): void {
  const element = messageContainer.value;
  if (!element) {
    return;
  }
  scrollTop.value = element.scrollTop;
}

function syncMessageHeight(messageId: string, height: number): void {
  if (height <= 0) {
    return;
  }

  const current = messageHeights.value[messageId] ?? 0;
  if (Math.abs(current - height) <= 1) {
    return;
  }

  messageHeights.value = {
    ...messageHeights.value,
    [messageId]: height,
  };
}

function setMessageRowRef(messageId: string, element: HTMLElement | null): void {
  const previous = messageRowElements.get(messageId);
  if (previous && previous !== element && resizeObserver) {
    resizeObserver.unobserve(previous);
    messageRowElements.delete(messageId);
  }

  if (!element) {
    return;
  }

  messageRowElements.set(messageId, element);
  syncMessageHeight(messageId, Math.ceil(element.getBoundingClientRect().height));
  if (resizeObserver) {
    resizeObserver.observe(element);
  }
}

async function scrollToBottom(force = false): Promise<void> {
  await nextTick();
  const element = messageContainer.value;
  if (!element) {
    return;
  }

  const remaining = element.scrollHeight - element.scrollTop - element.clientHeight;
  if (force || remaining < 180 || sending.value) {
    element.scrollTop = element.scrollHeight;
    scrollTop.value = element.scrollTop;
  }
}

async function handleMarkdownClick(event: MouseEvent): Promise<void> {
  const target = event.target as HTMLElement | null;
  const button = target?.closest('.copy-code-btn') as HTMLElement | null;
  if (!button) {
    return;
  }

  const payload = button.getAttribute('data-code');
  if (!payload) {
    return;
  }

  try {
    const raw = fromBase64(payload);
    await writeClipboardText(raw);
    ElMessage.success('代码已复制');
  } catch {
    ElMessage.error('代码复制失败');
  }
}

async function copyMessage(content: string): Promise<void> {
  try {
    await writeClipboardText(content);
    ElMessage.success('已复制');
  } catch {
    ElMessage.error('复制失败');
  }
}

function parseCitation(citation: string): { source: string; chunk: string } | null {
  const matched = citation.match(/source=([^,]+),\s*chunk=(.+)$/i);
  if (!matched) {
    return null;
  }
  return {
    source: matched[1].trim(),
    chunk: matched[2].trim(),
  };
}

function openCitation(citation: string): void {
  const base = (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api';
  const target = parseCitation(citation);
  const url = `${base}/ai/pdf/file/${encodeURIComponent(chatId.value)}${
    target
      ? `?source=${encodeURIComponent(target.source)}&chunk=${encodeURIComponent(target.chunk)}`
      : ''
  }`;
  window.open(url, '_blank', 'noopener,noreferrer');
}

function findPreviousUserQuestion(index: number): string {
  for (let i = index - 1; i >= 0; i -= 1) {
    const candidate = messages.value[i];
    if (candidate.role === 'user' && candidate.content.trim()) {
      return candidate.content.trim();
    }
  }
  return '';
}

async function rateAnswer(index: number, message: ChatMessage, rating: number): Promise<void> {
  if (message.role !== 'assistant') {
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再提交反馈');
    return;
  }
  if (answerFeedbackMap.value[message.id]) {
    ElMessage.info('该回答已评分');
    return;
  }
  answerFeedbackLoading.value = {
    ...answerFeedbackLoading.value,
    [message.id]: true,
  };
  try {
    await submitAnswerFeedback(
      {
        chatId: chatId.value,
        sessionId: activeSession.value.id,
        branchId: activeBranch.value.id,
        messageId: message.id,
        rating,
        question: findPreviousUserQuestion(index),
        answer: message.content,
        comment: rating >= 4 ? '回答有帮助' : '回答需要改进',
      },
      authContext(),
    );
    answerFeedbackMap.value = {
      ...answerFeedbackMap.value,
      [message.id]: rating,
    };
    ElMessage.success('反馈已提交并回灌评测集');
  } catch (error) {
    const tip = error instanceof Error ? error.message : '反馈提交失败';
    ElMessage.error(tip);
  } finally {
    answerFeedbackLoading.value = {
      ...answerFeedbackLoading.value,
      [message.id]: false,
    };
  }
}

function stopGenerating(): void {
  currentAbortController.value?.abort();
}

function clearConversation(): void {
  messages.value = [createMessage('assistant', '会话已重置。你可以继续发问。')];
  traceSteps.value = [];
  answerFeedbackMap.value = {};
  answerFeedbackLoading.value = {};
  prompt.value = '';
  streamPhase.value = 'idle';
  streamStatusDetail.value = '';
  syncCurrentSessionBranch();
  persistState();
}

async function handleLogin(): Promise<void> {
  if (!apiKeyInput.value.trim()) {
    ElMessage.warning('请先输入 API Key');
    return;
  }

  authLoading.value = true;
  try {
    const auth = await exchangeApiKey(
      apiKeyInput.value.trim(),
      tenantInput.value.trim() || undefined,
    );
    token.value = auth.token ?? '';
    refreshToken.value = auth.refreshToken ?? '';
    role.value = auth.role ?? '';
    if (auth.tenantId) {
      tenantInput.value = auth.tenantId;
    }
    ElMessage.success('JWT 获取成功');
    persistState();
    await loadSessionsFromCloud();
  } catch (error) {
    const message = error instanceof Error ? error.message : 'token exchange failed';
    ElMessage.error(message);
  } finally {
    authLoading.value = false;
  }
}

async function handleRefresh(): Promise<void> {
  if (!refreshToken.value) {
    ElMessage.warning('当前没有 refresh token');
    return;
  }

  refreshing.value = true;
  try {
    const auth = await refreshJwt(refreshToken.value);
    token.value = auth.token ?? token.value;
    refreshToken.value = auth.refreshToken ?? refreshToken.value;
    role.value = auth.role ?? role.value;
    if (auth.tenantId) {
      tenantInput.value = auth.tenantId;
    }
    ElMessage.success('令牌已刷新');
    persistState();
    await refreshCostSummary();
  } catch (error) {
    const message = error instanceof Error ? error.message : 'refresh failed';
    ElMessage.error(message);
  } finally {
    refreshing.value = false;
  }
}

function clearAuth(): void {
  token.value = '';
  refreshToken.value = '';
  role.value = '';
  apiKeyInput.value = '';
  tenantInput.value = '';
  costSummary.value = null;
  // 退出时离开管理员视图，避免下一个普通用户登录后残留无权限的页面
  activeView.value = 'chat';
  persistState();
}

/** 退出登录：清干净全部凭据回到登录页（门闩由 canUseRemoteSync 驱动）。 */
function logout(): void {
  clearAuth();
  ElMessage.success('已退出登录');
}

/** 密码登录 / 注册共用：成功即拿到 JWT 并进入控制台。 */
async function handlePasswordAuth(): Promise<void> {
  if (!authUsername.value.trim() || !authPassword.value) {
    ElMessage.warning('请输入用户名和密码');
    return;
  }
  authLoading.value = true;
  try {
    const credentials = {
      username: authUsername.value.trim(),
      password: authPassword.value,
    };
    const auth =
      authMode.value === 'login'
        ? await loginWithPassword(credentials.username, credentials.password)
        : await registerUser(credentials.username, credentials.password);
    token.value = auth.token ?? '';
    refreshToken.value = auth.refreshToken ?? '';
    role.value = auth.role ?? 'USER';
    if (auth.tenantId) {
      tenantInput.value = auth.tenantId;
    }
    authPassword.value = '';
    ElMessage.success(authMode.value === 'login' ? '登录成功' : '注册成功，已自动登录');
    persistState();
    await loadSessionsFromCloud();
  } catch (error) {
    const message = error instanceof Error ? error.message : '登录失败';
    ElMessage.error(message);
  } finally {
    authLoading.value = false;
  }
}

function sanitizeMessageStates(): void {
  messages.value = messages.value.map((message) => ({
    ...message,
    state:
      message.state === 'pending' || message.state === 'streaming'
        ? 'done'
        : message.state || 'done',
  }));
}

async function ask(question: string, appendUser: boolean): Promise<void> {
  if (!question.trim() || sending.value) {
    return;
  }

  sanitizeMessageStates();

  const assistantMsg: ChatMessage = {
    ...createMessage('assistant', ''),
    citations: [],
    evidence: [],
    state: 'pending',
  };

  if (appendUser) {
    messages.value.push(createMessage('user', question));
  }
  messages.value.push(assistantMsg);
  const assistantIndex = messages.value.length - 1;

  traceSteps.value = [];
  sending.value = true;
  isStreamingResponse.value = streaming.value;
  prompt.value = '';
  streamPhase.value = 'thinking';
  streamStatusDetail.value = '模型正在准备响应';

  syncCurrentSessionBranch();
  persistState();
  await scrollToBottom(true);

  const controller = new AbortController();
  currentAbortController.value = controller;

  try {
    if (streaming.value) {
      let streamError = '';
      await streamReactChat(
        {
          prompt: question,
          chatId: chatId.value,
          modelProfile: modelProfile.value,
        },
        authContext(),
        (event, payload) => {
          if (event === 'trace') {
            const step = payload as ReactTraceStep;
            upsertTrace(step);
            streamPhase.value = 'tool';
            streamStatusDetail.value = `调用工具: ${step.action}`;
            return;
          }

          if (event === 'token') {
            const tokenEvent = payload as ReactTokenEvent;
            messages.value[assistantIndex].state = 'streaming';
            messages.value[assistantIndex].content += tokenEvent.token ?? '';
            streamPhase.value = 'streaming';
            streamStatusDetail.value = '正在生成文本';
            void scrollToBottom();
            return;
          }

          if (event === 'done') {
            const done = payload as ReactChatResponse;
            if (done.trace?.length) {
              traceSteps.value = done.trace;
            }
            if (done.answer?.trim()) {
              messages.value[assistantIndex].content = done.answer;
            }
            messages.value[assistantIndex].citations = Array.isArray(done.citations)
              ? done.citations.map((item) => String(item).trim()).filter(Boolean)
              : [];
            messages.value[assistantIndex].evidence = Array.isArray(done.evidence)
              ? done.evidence.map((item) => String(item).trim()).filter(Boolean)
              : [];
            messages.value[assistantIndex].state = 'done';
            streamPhase.value = 'done';
            streamStatusDetail.value = '响应已完成';
            return;
          }

          if (event === 'error') {
            const err = payload as ReactErrorEvent;
            streamError = err.message || 'stream error';
          }
        },
        controller.signal,
        agentEngine.value,
      );

      if (streamError) {
        throw new Error(streamError);
      }
    } else {
      const result = await reactChat(
        {
          prompt: question,
          chatId: chatId.value,
          modelProfile: modelProfile.value,
        },
        authContext(),
        controller.signal,
        agentEngine.value,
      );
      traceSteps.value = result.trace ?? [];
      messages.value[assistantIndex].content = result.answer || '模型没有返回内容';
      messages.value[assistantIndex].citations = Array.isArray(result.citations)
        ? result.citations.map((item) => String(item).trim()).filter(Boolean)
        : [];
      messages.value[assistantIndex].evidence = Array.isArray(result.evidence)
        ? result.evidence.map((item) => String(item).trim()).filter(Boolean)
        : [];
      messages.value[assistantIndex].state = 'done';
      streamPhase.value = 'done';
      streamStatusDetail.value = '响应已完成';
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      ElMessage.info('已停止输出');
      if (!messages.value[assistantIndex].content.trim()) {
        messages.value[assistantIndex].content = '输出已手动停止。';
      }
      messages.value[assistantIndex].state = 'stopped';
      streamPhase.value = 'stopped';
      streamStatusDetail.value = '你手动停止了本次输出';
    } else {
      const message = error instanceof Error ? error.message : 'request failed';
      messages.value[assistantIndex].content = `请求失败：${message}`;
      messages.value[assistantIndex].state = 'error';
      streamPhase.value = 'error';
      streamStatusDetail.value = message;
      ElMessage.error(message);
    }
  } finally {
    sending.value = false;
    isStreamingResponse.value = false;
    currentAbortController.value = null;

    syncCurrentSessionBranch();
    persistState();
    await scrollToBottom(true);

    if (
      streamPhase.value === 'done' ||
      streamPhase.value === 'error' ||
      streamPhase.value === 'stopped'
    ) {
      scheduleStreamReset();
    }
  }
}

async function send(): Promise<void> {
  const question = prompt.value.trim();
  // 深度研究开关开着时，这一条发送改走研究报告流程（ask 仍服务普通聊天和重新生成）
  if (researchMode.value) {
    await runResearchInChat(question);
    return;
  }
  await ask(question, true);
}

async function regenerateFrom(assistantIndex: number): Promise<void> {
  for (let i = assistantIndex - 1; i >= 0; i -= 1) {
    const candidate = messages.value[i];
    if (candidate.role === 'user' && candidate.content.trim()) {
      syncCurrentSessionBranch();
      const session = activeSession.value;
      const current = activeBranch.value;
      const baseMessages = messages.value.slice(0, i);
      const branch = forkBranch(
        `${deriveTitle(candidate.content)} · retry`,
        baseMessages,
        current.id,
        candidate.id,
      );
      session.branches.unshift(branch);
      session.activeBranchId = branch.id;
      loadSession(session.id);
      persistState();
      await ask(candidate.content, true);
      return;
    }
  }

  ElMessage.warning('没有找到可重试的用户问题');
}

watch(
  darkMode,
  () => {
    document.documentElement.classList.toggle('dark', darkMode.value);
    persistState();
  },
  { immediate: true },
);

watch(
  [
    modelProfile,
    streaming,
    workspaceFilter,
    showArchivedSessions,
    sessionSearch,
    agentEngine,
    sessionColCollapsed,
  ],
  () => {
    syncCurrentSessionBranch();
    persistState();
  },
);

watch(
  [messages, traceSteps],
  () => {
    syncCurrentSessionBranch();
    persistState();
  },
  { deep: true },
);

watch([apiKeyInput, tenantInput, token, refreshToken, role], () => {
  persistState();
  if (canUseRemoteSync.value) {
    void refreshCostSummary();
    if (activeView.value === 'usage') {
      void loadUsageTrend(true);
    }
  } else {
    costSummary.value = null;
    usageNeedsAuth.value = true;
    usagePoints.value = [];
  }
});

watch(
  messages,
  () => {
    const idSet = new Set(messages.value.map((message) => message.id));
    const filtered: Record<string, number> = {};
    Object.entries(messageHeights.value).forEach(([id, height]) => {
      if (idSet.has(id)) {
        filtered[id] = height;
      }
    });
    messageHeights.value = filtered;
    void scrollToBottom();
  },
  { deep: false },
);

onMounted(() => {
  loadSession(activeSessionId.value);
  updateViewport();

  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver((entries) => {
      entries.forEach((entry) => {
        const element = entry.target as HTMLElement;
        const messageId = element.dataset.msgId;
        if (!messageId) {
          return;
        }
        syncMessageHeight(messageId, Math.ceil(entry.contentRect.height));
      });
    });
  }

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

  if (activeView.value === 'knowledge') {
    void loadKnowledgeJobs();
  }

  if (activeView.value === 'usage') {
    void loadUsageTrend();
  }

  void scrollToBottom(true);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateViewport);
  stopKnowledgePolling();
  stopResearchPolling();
  stopComposerUploadPolling();
  dismissComposerUploadChip();

  if (resizeObserver) {
    messageRowElements.forEach((element) => {
      resizeObserver?.unobserve(element);
    });
    resizeObserver.disconnect();
    resizeObserver = null;
  }

  if (streamResetTimer) {
    window.clearTimeout(streamResetTimer);
    streamResetTimer = null;
  }
});
</script>

<style scoped>
.app-shell {
  /* 定高框架：页面本体永远等于一屏。此前用 min-height，侧栏内容一长
     就把整页撑高，聊天区滚到底再往下滚，整页跟着滚、输入框下方露出大片空白。 */
  height: 100vh;
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  overflow: hidden;
  color: var(--ui-text);
}

/* 聊天页且未折叠时，会话栏占中间一列；其它页签内容区占满整行 */
.app-shell.shell-with-sessions {
  grid-template-columns: 64px 260px minmax(0, 1fr);
}

/* ---------- 图标栏（64px，只管页面导航） ---------- */

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
  background: linear-gradient(150deg, rgba(14, 116, 144, 0.85), rgba(15, 118, 110, 0.7));
  color: #fff;
  font-size: 17px;
  font-weight: 800;
  flex-shrink: 0;
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
  width: 48px;
  border: 0;
  border-radius: 10px;
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
  color: #fff;
  background: linear-gradient(150deg, rgba(14, 116, 144, 0.9), rgba(15, 118, 110, 0.78));
}

/* ---------- 会话栏（260px，仅聊天页，可折叠） ---------- */

.session-col {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  min-height: 0;
  overflow: hidden;
  border-right: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-card) 88%, transparent);
  backdrop-filter: blur(12px);
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
  border-color: rgba(14, 116, 144, 0.4);
}

.new-chat-btn {
  border: 1px solid rgba(14, 116, 144, 0.35);
  background: linear-gradient(150deg, rgba(14, 116, 144, 0.2), rgba(15, 118, 110, 0.14));
  color: var(--ui-text);
  border-radius: 12px;
  padding: 10px 14px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition:
    transform 180ms ease,
    box-shadow 180ms ease;
}

.new-chat-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 24px rgba(14, 116, 144, 0.18);
}

.session-tools {
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 88%, transparent);
  display: flex;
  flex-direction: column;
  gap: 8px;
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

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.section-label {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ui-muted);
}

.section-meta {
  font-size: 12px;
  color: var(--ui-muted);
}

.session-panel,
.branch-panel {
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 84%, transparent);
}

/* 会话面板撑满会话栏剩余高度：列表内部滚动，会话再多也不挤压其它区域 */
.session-col .session-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.session-list,
.branch-list {
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.session-col .session-list {
  flex: 1;
}

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

.session-item {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 8px;
  background: color-mix(in oklab, var(--ui-card) 76%, transparent);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: border-color 160ms ease;
}

.session-item:hover,
.session-item.active {
  border-color: rgba(14, 116, 144, 0.45);
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
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ui-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.session-actions button,
.branch-head-actions button {
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-panel) 86%, transparent);
  color: var(--ui-text);
  border-radius: 999px;
  padding: 3px 8px;
  font-size: 11px;
  cursor: pointer;
}

.session-actions .danger {
  color: #dc2626;
}

.session-empty {
  font-size: 12px;
  color: var(--ui-muted);
  padding: 6px;
}

.branch-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
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

/* 鉴权与模型住在弹窗里，只保留表单体样式 */
.ops-body {
  padding: 4px 2px 2px;
}

.auth-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.full-width {
  width: 100%;
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

.message-actions button {
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

.evaluation-page {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  gap: 14px;
  padding: 14px;
}

.knowledge-page {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 380px) minmax(0, 1fr);
  gap: 14px;
  padding: 14px;
}

.admin-docs-page {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  /* 行高锁死，表格区内部滚动 */
  grid-template-rows: minmax(0, 1fr);
  gap: 14px;
  padding: 14px;
}

/* 双类选择器提高优先级：压过文件后部的 .eval-main-panel 行高覆盖 */
.eval-main-panel.admin-docs-main {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 10px;
  min-height: 0;
}

.admin-docs-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.admin-docs-toolbar .el-input {
  width: 220px;
}

.admin-docs-pagination {
  justify-self: end;
}

/* ---------- 用量统计页 ---------- */
.usage-page {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  gap: 14px;
  padding: 14px;
}

/* 头 / 卡片 / 趋势图 / 表格各占一行：头和卡片自适应，其余平分剩余高度，页面不出滚动条 */
.eval-main-panel.usage-main {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) minmax(0, 1fr);
  gap: 10px;
  min-height: 0;
}

.usage-range-select {
  width: 120px;
}

.usage-cards {
  display: grid;
  grid-template-columns: repeat(6, minmax(112px, 1fr));
  gap: 8px;
}

.usage-cards strong.bad {
  color: #dc2626;
}

.usage-chart-block {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.usage-chart-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.usage-legend {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ui-muted);
}

.usage-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  display: inline-block;
}

.usage-chart-svg {
  flex: 1;
  width: 100%;
  min-height: 0;
}

.usage-chart-svg .grid-line {
  stroke: var(--ui-border);
  stroke-width: 1;
}

.usage-chart-svg .axis-text {
  fill: var(--ui-muted);
  font-size: 10px;
}

/* 折线配色取自参考图：输入蓝 / 输出绿 / 成本红虚线（颜色+线型双通道编码，色弱也能分） */
.usage-chart-svg .line-input {
  fill: none;
  stroke: #3b82f6;
  stroke-width: 2;
}

.usage-chart-svg .line-output {
  fill: none;
  stroke: #22c55e;
  stroke-width: 2;
}

.usage-chart-svg .line-cost {
  fill: none;
  stroke: #ef4444;
  stroke-width: 2;
  stroke-dasharray: 6 4;
}

.usage-chart-svg .area-input {
  fill: color-mix(in oklab, #3b82f6 12%, transparent);
  stroke: none;
}

.usage-chart-svg .hover-line {
  stroke: var(--ui-border);
  stroke-width: 1;
}

.usage-chart-svg .tooltip-box {
  fill: var(--ui-panel);
  stroke: var(--ui-border);
  filter: drop-shadow(0 4px 10px rgb(0 0 0 / 18%));
}

.usage-chart-svg .tooltip-title {
  fill: var(--ui-text);
  font-size: 12px;
  font-weight: 700;
}

.usage-chart-svg .tooltip-text {
  fill: var(--ui-text);
  font-size: 11px;
}

.usage-chart-svg .tt-dot.input,
.usage-dot.blue {
  fill: #3b82f6;
  background: #3b82f6;
}

.usage-chart-svg .tt-dot.output,
.usage-dot.green {
  fill: #22c55e;
  background: #22c55e;
}

.usage-chart-svg .tt-dot.cost,
.usage-dot.red {
  fill: #ef4444;
  background: #ef4444;
}

.usage-chart-title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
}

.usage-chart-range {
  font-size: 12px;
  color: var(--ui-muted);
}

.usage-legend-bottom {
  display: flex;
  justify-content: center;
  gap: 18px;
}

.usage-chart-empty {
  margin: 0;
  font-size: 12px;
  color: var(--ui-muted);
}

.engine-hint {
  margin: -6px 0 12px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ui-muted);
}

.knowledge-uploader {
  width: 100%;
}

.knowledge-uploader :deep(.el-upload),
.knowledge-uploader :deep(.el-upload-dragger) {
  width: 100%;
}

.knowledge-uploader :deep(.el-upload-dragger) {
  padding: 22px 12px;
}

.knowledge-uploader .uploader-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ui-text);
}

.knowledge-uploader .uploader-sub {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--ui-muted);
}

.knowledge-tip {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ui-muted);
}

.knowledge-list-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.kb-auth-alert {
  margin: 8px 0;
}

/* ---------- 登录/注册门闩 ---------- */

.auth-gate {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
}

.auth-card {
  width: min(380px, 100%);
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 28px 26px 24px;
  border: 1px solid var(--ui-border);
  border-radius: 18px;
  background: color-mix(in oklab, var(--ui-card) 88%, transparent);
  backdrop-filter: blur(12px);
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.12);
}

.auth-card .eyebrow {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ui-accent);
}

.auth-card h1 {
  margin: 0;
  font-size: 22px;
  color: var(--ui-text);
}

.auth-submit {
  width: 100%;
}

.admin-key-login summary {
  font-size: 13px;
  color: var(--ui-muted);
  cursor: pointer;
}

.admin-key-login {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.admin-key-login[open] {
  padding-top: 10px;
  border-top: 1px dashed var(--ui-border);
}

.eval-side-panel,
.eval-main-panel {
  min-height: 0;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: color-mix(in oklab, var(--ui-card) 88%, transparent);
}

.eval-side-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
}

.eval-panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.eval-panel-head strong {
  display: block;
  margin-top: 4px;
  font-size: 22px;
}

.eval-dataset-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 260px;
  overflow-y: auto;
}

.eval-dataset-list button {
  width: 100%;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 9px 10px;
  text-align: left;
  color: var(--ui-text);
  background: color-mix(in oklab, var(--ui-panel) 82%, transparent);
  cursor: pointer;
}

.eval-dataset-list button.active {
  border-color: rgba(15, 118, 110, 0.55);
  box-shadow: inset 0 0 0 1px rgba(15, 118, 110, 0.28);
}

.eval-dataset-list span,
.eval-dataset-list small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.eval-dataset-list span {
  font-size: 13px;
  font-weight: 700;
}

.eval-dataset-list small {
  margin-top: 4px;
  color: var(--ui-muted);
}

.eval-create-panel {
  display: grid;
  gap: 8px;
}

.eval-json-input :deep(.el-textarea__inner) {
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.45;
}

.eval-main-panel {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 12px;
  padding: 12px;
}

.eval-score-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(112px, 1fr));
  gap: 8px;
}

.eval-metric-card {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 80%, transparent);
  min-width: 0;
}

.eval-metric-card span,
.eval-metric-card small {
  display: block;
  font-size: 11px;
  color: var(--ui-muted);
}

.eval-metric-card strong {
  display: block;
  margin: 7px 0 4px;
  font-size: 22px;
  line-height: 1;
}

.eval-metric-card .good {
  color: #047857;
}

.eval-metric-card .bad {
  color: #dc2626;
}

.eval-metric-card .neutral {
  color: var(--ui-muted);
}

.eval-run-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.eval-run-summary {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 76%, transparent);
}

.eval-run-summary.current {
  border-color: rgba(15, 118, 110, 0.45);
}

.eval-run-summary strong,
.eval-run-summary span {
  display: block;
  margin-top: 6px;
}

.eval-run-summary strong {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.eval-run-summary span {
  font-size: 22px;
  font-weight: 800;
}

.eval-result-table {
  min-height: 0;
  border-radius: 10px;
  overflow: hidden;
}

.eval-expand {
  display: grid;
  gap: 8px;
  padding: 6px 14px 14px 44px;
}

.eval-expand-scores {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: color-mix(in oklab, currentColor 70%, transparent);
}

.eval-expand-scores span {
  padding: 2px 10px;
  border: 1px solid var(--ui-border);
  border-radius: 999px;
  background: color-mix(in oklab, var(--ui-card) 70%, transparent);
}

.eval-expand-label {
  margin: 6px 0 0;
  font-size: 12px;
  font-weight: 700;
  opacity: 0.75;
}

.eval-expand-answer {
  max-height: 260px;
  overflow: auto;
  padding: 10px 12px;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: color-mix(in oklab, var(--ui-card) 60%, transparent);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.eval-expand-citations {
  margin: 0;
  padding-left: 20px;
  font-size: 12px;
  line-height: 1.8;
  opacity: 0.8;
  word-break: break-all;
}

.eval-expand-error {
  margin: 0;
  font-size: 12px;
  color: var(--el-color-danger, #f56c6c);
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

@media (max-width: 1160px) {
  .app-shell.shell-with-sessions {
    grid-template-columns: 64px 224px minmax(0, 1fr);
  }

  .eval-score-strip {
    grid-template-columns: repeat(3, minmax(112px, 1fr));
  }

  .usage-cards {
    grid-template-columns: repeat(3, minmax(112px, 1fr));
  }
}

@media (max-width: 980px) {
  .app-shell,
  .app-shell.shell-with-sessions {
    grid-template-columns: 56px minmax(0, 1fr);
  }

  .session-col {
    display: none;
  }

  .workspace-head {
    position: static;
  }

  .evaluation-page,
  .knowledge-page {
    grid-template-columns: 1fr;
  }

  .eval-dataset-list {
    max-height: 180px;
  }

  .composer-shell {
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

  .evaluation-page,
  .knowledge-page {
    padding: 12px;
  }

  .eval-score-strip,
  .eval-run-grid,
  .usage-cards {
    grid-template-columns: 1fr;
  }

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
