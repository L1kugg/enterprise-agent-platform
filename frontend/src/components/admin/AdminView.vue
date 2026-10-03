<template>
  <section class="admin-docs-page">
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
        <el-table-column
          prop="sourceName"
          label="文件名"
          min-width="180"
          show-overflow-tooltip
        />
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
        <el-table-column
          prop="errorMessage"
          label="错误"
          min-width="140"
          show-overflow-tooltip
        />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="danger" link @click="removeAdminDocument(row)"
              >删除</el-button
            >
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
</template>

<script setup lang="ts">
// 管理员跨租户文档总览页：模板与样式从 App.vue 原文搬入，行为零变化。
// v-else-if 的显隐条件（isAdmin && activeView === 'admin'）留在 App.vue 的
// 组件标签上，与页签链保持原样；数据加载由 useViewActivation 触发，本组件纯展示。
import {
  adminDocs,
  adminDocsLoading,
  adminDocsPage,
  adminDocsPageSize,
  adminDocsSearch,
  adminDocsTotal,
  adminNeedsAuth,
  handleAdminPageChange,
  handleAdminSearch,
  loadAdminDocuments,
  removeAdminDocument,
} from '../../composables/useAdmin';
import { formatFileSize, formatJobTime, statusTagType } from '../../utils/format';
</script>

<style scoped>
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

.admin-docs-pagination {
  justify-self: end;
}
</style>
