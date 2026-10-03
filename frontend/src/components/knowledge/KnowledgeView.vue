<template>
  <section class="knowledge-page">
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
        <p class="uploader-sub">
          支持 PDF / Word（doc、docx）/ Markdown，上传后自动切分、向量化并入知识库
        </p>
      </el-upload>

      <el-button
        type="primary"
        :loading="knowledgeUploading"
        :disabled="knowledgeUploadFiles.length === 0"
        @click="submitKnowledgeUpload"
        >提交入库</el-button
      >

      <p class="knowledge-tip">
        入库是异步的：提交后等状态变成 SUCCEEDED 才能被检索到；失败会自动重试。
      </p>
    </aside>

    <section v-loading="knowledgeLoading" class="eval-main-panel kb-main-panel">
      <el-alert
        v-if="knowledgeNeedsAuth"
        class="kb-auth-alert"
        type="info"
        show-icon
        :closable="false"
        title="登录后才能看到知识库内容"
        description="请先登录（右上角退出后可重新登录）；如果登录已过期，重新登录后回来点「刷新」即可。"
      />
      <el-tabs v-model="knowledgeTab" class="kb-tabs">
        <el-tab-pane label="文档清单" name="documents" class="kb-pane">
          <div class="kb-toolbar">
            <el-input
              v-model="knowledgeDocsSearch"
              class="kb-search-input"
              size="small"
              placeholder="按文件名或批次搜索"
              clearable
              @keyup.enter="searchKnowledgeDocuments"
              @clear="searchKnowledgeDocuments"
            />
            <el-button size="small" @click="searchKnowledgeDocuments">搜索</el-button>
            <el-button size="small" @click="loadKnowledgeDocuments()">刷新</el-button>
          </div>
          <div class="kb-table-wrap">
            <el-table
              :data="knowledgeDocuments"
              height="100%"
              empty-text="还没有入库文档，先上传一个"
            >
              <el-table-column
                prop="sourceName"
                label="文件"
                min-width="180"
                show-overflow-tooltip
              />
              <el-table-column label="类型" width="80">
                <template #default="{ row }">{{ row.sourceType || '—' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="statusTagType(row.status)" size="small">{{
                    row.status
                  }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="切片数" width="80">
                <template #default="{ row }">{{ row.chunkCount ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="大小" width="100">
                <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
              </el-table-column>
              <el-table-column label="入库时间" width="130">
                <template #default="{ row }">{{
                  formatJobTime(row.finishedAt ?? row.createdAt)
                }}</template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <el-button
                    size="small"
                    type="danger"
                    link
                    @click="removeKnowledgeDocument(row)"
                    >删除</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-pagination
            v-model:current-page="knowledgeDocsPage"
            :page-size="knowledgeDocsPageSize"
            :total="knowledgeDocsTotal"
            layout="total, prev, pager, next"
            small
            background
            @current-change="loadKnowledgeDocuments(true)"
          />
        </el-tab-pane>
        <el-tab-pane label="入库任务" name="jobs" class="kb-pane">
          <div class="knowledge-list-head">
            <p class="section-label">最近 20 条任务</p>
            <el-button size="small" @click="loadKnowledgeJobs()">刷新</el-button>
          </div>
          <div class="kb-table-wrap">
            <el-table
              :data="knowledgeJobs"
              height="100%"
              empty-text="还没有入库记录，先上传一个文档"
            >
              <el-table-column
                prop="sourceName"
                label="文件"
                min-width="180"
                show-overflow-tooltip
              />
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="statusTagType(row.status)" size="small">{{
                    row.status
                  }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="重试" width="80">
                <template #default="{ row }"
                  >{{ row.attemptCount ?? 0 }}/{{ row.maxRetries ?? 0 }}</template
                >
              </el-table-column>
              <el-table-column label="上传时间" width="120">
                <template #default="{ row }">{{ formatJobTime(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column
                prop="chatId"
                label="批次"
                min-width="140"
                show-overflow-tooltip
              />
              <el-table-column
                prop="errorMessage"
                label="错误"
                min-width="160"
                show-overflow-tooltip
              />
              <el-table-column v-if="isAdmin" label="操作" width="90">
                <template #default="{ row }">
                  <el-button size="small" type="danger" link @click="removeKnowledgeJob(row)"
                    >删除</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>
        <el-tab-pane label="试搜" name="search" class="kb-pane">
          <div class="kb-toolbar">
            <el-input
              v-model="previewQuery"
              class="kb-search-input"
              placeholder="输入一句话，看看知识库能搜到什么"
              clearable
              @keyup.enter="runPreviewSearch"
            />
            <el-button type="primary" :loading="previewLoading" @click="runPreviewSearch"
              >试搜</el-button
            >
          </div>
          <el-alert
            v-if="previewResult?.degradedSources.length"
            type="warning"
            show-icon
            :closable="false"
            :title="`部分检索通道暂不可用：${previewResult.degradedSources.join('、')}，结果可能不全`"
          />
          <div class="kb-preview-results">
            <p v-if="!previewResult" class="kb-preview-hint">
              用来确认文档内容是否已经能被检索到：只查本租户知识库，不联网也不调用大模型。
            </p>
            <p v-else-if="previewResult.items.length === 0" class="kb-preview-hint">
              没搜到相关内容：换个说法试试，或到「文档清单」确认文档已入库完成。
            </p>
            <div
              v-for="item in previewResult?.items ?? []"
              :key="item.chunkId"
              class="kb-preview-item"
            >
              <div class="kb-preview-meta">
                <el-tag size="small" effect="plain">{{
                  previewSourceLabel(item.source)
                }}</el-tag>
                <span class="kb-preview-file">{{ item.fileName }}</span>
                <span class="kb-preview-score">相关度 {{ item.score }}</span>
              </div>
              <p class="kb-preview-snippet">{{ item.snippet }}</p>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>
  </section>
</template>

<script setup lang="ts">
// 知识库页：上传入库 + 文档清单 / 入库任务 / 试搜三标签。模板与样式从
// App.vue 原文搬入，行为零变化。列表数据与轮询在 useKnowledge 单例
// （轮询读 useGlobalUi 的 activeView，与本组件卸载无关）；数据加载由
// useViewActivation / App.vue onMounted 触发。
import { ElMessage } from 'element-plus';
import {
  knowledgeDocuments,
  knowledgeDocsPage,
  knowledgeDocsPageSize,
  knowledgeDocsSearch,
  knowledgeDocsTotal,
  knowledgeJobs,
  knowledgeLoading,
  knowledgeNeedsAuth,
  knowledgeTab,
  knowledgeUploadFiles,
  knowledgeUploading,
  loadKnowledgeDocuments,
  loadKnowledgeJobs,
  previewLoading,
  previewQuery,
  previewResult,
  removeKnowledgeDocument,
  removeKnowledgeJob,
  previewSourceLabel,
  runPreviewSearch,
  searchKnowledgeDocuments,
  submitKnowledgeUpload,
} from '../../composables/useKnowledge';
import { isAdmin } from '../../composables/useAuthState';
import { formatFileSize, formatJobTime, statusTagType } from '../../utils/format';
</script>

<style scoped>
.knowledge-page {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 380px) minmax(0, 1fr);
  gap: 14px;
  padding: 14px;
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

/* ---------- 知识库页三标签（文档清单 / 入库任务 / 试搜） ---------- */

.kb-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.kb-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.kb-pane {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.kb-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}

.kb-search-input {
  max-width: 320px;
}

.kb-table-wrap {
  flex: 1;
  min-height: 0;
}

.kb-pane .el-pagination {
  justify-content: flex-end;
}

.kb-preview-results {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.kb-preview-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ui-muted);
}

.kb-preview-item {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 10px 12px;
  background: color-mix(in oklab, var(--ui-panel) 82%, transparent);
}

.kb-preview-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.kb-preview-file {
  font-size: 13px;
  font-weight: 700;
  color: var(--ui-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-preview-score {
  margin-left: auto;
  font-size: 12px;
  color: var(--ui-muted);
}

.kb-preview-snippet {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ui-text);
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 980px) {
  .knowledge-page {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 680px) {
  .knowledge-page {
    padding: 12px;
  }
}
</style>
