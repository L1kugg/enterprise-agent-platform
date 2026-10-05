<template>
  <!-- 文档内容预览：点「文档清单」文件名打开；开关与数据都在 useKnowledge 单例，本组件零逻辑 -->
  <el-drawer v-model="docViewerVisible" size="560px" destroy-on-close>
    <template #header>
      <div class="kb-doc-head">
        <p class="kb-doc-title">{{ docViewerDoc?.sourceName ?? '文档预览' }}</p>
        <p class="kb-doc-meta">
          <el-tag v-if="docViewerDoc?.sourceType" size="small" effect="plain">{{
            docViewerDoc.sourceType
          }}</el-tag>
          <span v-if="docViewerDoc">{{ docViewerDoc.chunkCount }} 个内容块</span>
          <el-button
            v-if="docViewerDoc"
            size="small"
            @click="downloadKnowledgeOriginal"
            >下载原文件</el-button
          >
        </p>
      </div>
    </template>
    <div v-loading="docViewerLoading" class="kb-doc-body">
      <el-alert
        v-if="docViewerDoc?.truncated"
        type="warning"
        show-icon
        :closable="false"
        title="文档过长，仅展示部分内容"
        description="正文超过单次预览上限已截断，需要完整内容请点「下载原文件」。"
      />
      <template v-if="docViewerDoc">
        <div v-for="block in docViewerDoc.blocks" :key="block.index" class="kb-doc-block">
          <p class="kb-doc-block-meta">
            <span>#{{ block.index + 1 }}</span>
            <el-tag v-if="block.page != null" size="small" effect="plain"
              >第 {{ block.page }} 页</el-tag
            >
          </p>
          <p class="kb-doc-block-text">{{ block.text }}</p>
        </div>
        <p v-if="docViewerDoc.blocks.length === 0" class="kb-doc-empty">没有可展示的文本内容</p>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
// 注意：el-drawer 会把内容传送到 body，但 scoped 属性长在元素上，样式照样命中（同 BranchDrawer）。
import {
  docViewerDoc,
  docViewerLoading,
  docViewerVisible,
  downloadKnowledgeOriginal,
} from '../../composables/useKnowledge';
</script>

<style scoped>
.kb-doc-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.kb-doc-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ui-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-doc-meta {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ui-muted);
}

.kb-doc-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 200px;
}

.kb-doc-block {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 10px 12px;
  background: color-mix(in oklab, var(--ui-panel) 82%, transparent);
}

.kb-doc-block-meta {
  margin: 0 0 6px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ui-muted);
}

.kb-doc-block-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ui-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.kb-doc-empty {
  margin: 0;
  font-size: 12px;
  color: var(--ui-muted);
}
</style>
