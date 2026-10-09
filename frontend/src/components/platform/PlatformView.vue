<template>
  <section class="platform-page">
    <PlatformOverview v-if="platformSection === 'overview'" />

    <AgentStudio v-else-if="platformSection === 'agents'" />

    <section v-else v-loading="platformLoading" class="platform-panel">
      <div class="platform-head">
        <div>
          <p class="section-label">Platform Configuration</p>
          <h2>{{ activeResource.label }}</h2>
          <p class="platform-sub">{{ activeResource.description }}</p>
        </div>
        <div class="platform-toolbar">
          <el-input
            v-model="platformSearch"
            size="small"
            clearable
            placeholder="搜索名称"
            @keyup.enter="handlePlatformSearch"
            @clear="handlePlatformSearch"
          />
          <el-button size="small" @click="handlePlatformSearch">搜索</el-button>
          <el-button size="small" @click="loadPlatformAssets()">刷新</el-button>
          <el-button size="small" type="primary" @click="openCreateDialog">新增</el-button>
        </div>
      </div>

      <el-alert
        v-if="platformNeedsAuth"
        type="info"
        show-icon
        :closable="false"
        title="需要 ADMIN 身份"
        description="请使用管理员账号登录后管理平台配置。"
      />

      <el-table :data="platformAssets" height="100%" empty-text="暂无配置">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="config-view">
              <p class="config-title">配置 JSON</p>
              <pre>{{ formatConfig(row.configJson) }}</pre>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="description" label="简介" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.status || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="activePlatformType === 'knowledge-files'" label="所属知识库" min-width="150">
          <template #default="{ row }">{{ knowledgeBaseName(row.parentId) }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ formatTime(row.updatedAt || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openEditDialog(row)">编辑</el-button>
            <el-button
              v-if="activePlatformType === 'agents'"
              size="small"
              link
              type="primary"
              @click="copyAgent(row)"
            >复制</el-button>
            <el-button
              v-if="activePlatformType === 'agents'"
              size="small"
              link
              type="success"
              @click="publishAgent(row)"
            >发布</el-button>
            <el-button
              v-if="activePlatformType === 'model-services'"
              size="small"
              link
              type="success"
              @click="testModel(row)"
            >测试</el-button>
            <el-button size="small" link type="danger" @click="removePlatformAsset(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="platformPage"
        :page-size="platformPageSize"
        :total="platformTotal"
        layout="total, prev, pager, next"
        class="platform-pagination"
        @current-change="handlePlatformPageChange"
      />
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingAsset ? `编辑${activeResource.label}` : `新增${activeResource.label}`"
      width="760px"
      destroy-on-close
    >
      <el-form label-width="100px" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="assetName" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="assetDescription" type="textarea" :rows="2" maxlength="512" />
        </el-form-item>
        <el-form-item v-if="editingAsset" label="状态">
          <el-input v-model="assetStatus" placeholder="例如 DRAFT / ENABLED / PUBLISHED" />
        </el-form-item>
        <el-form-item v-if="activePlatformType === 'knowledge-files'" label="所属知识库" required>
          <el-select v-model="assetParentId" filterable placeholder="选择知识库" class="full-width">
            <el-option
              v-for="item in knowledgeBaseOptions"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="配置 JSON" required>
          <el-input v-model="assetConfigJson" type="textarea" :rows="14" spellcheck="false" class="mono" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialogSaving" @click="savePlatformAsset">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import AgentStudio from './AgentStudio.vue';
import PlatformOverview from './PlatformOverview.vue';
import {
  activePlatformType,
  activeResource,
  assetConfigJson,
  assetDescription,
  assetName,
  assetParentId,
  assetStatus,
  copyAgent,
  dialogSaving,
  dialogVisible,
  editingAsset,
  formatConfigPlaceholder,
  handlePlatformPageChange,
  handlePlatformSearch,
  knowledgeBaseName,
  knowledgeBaseOptions,
  loadPlatformAssets,
  openCreateDialog,
  openEditDialog,
  platformAssets,
  platformLoading,
  platformNeedsAuth,
  platformPage,
  platformPageSize,
  platformSection,
  platformSearch,
  platformTotal,
  publishAgent,
  removePlatformAsset,
  savePlatformAsset,
  statusTag,
  testModel,
} from '../../composables/usePlatformAssets';

const formatConfig = formatConfigPlaceholder;
const formatTime = (value?: string) => (value ? value.replace('T', ' ').slice(0, 19) : '-');
</script>

<style scoped>
.platform-page {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  padding: 14px;
}

.platform-panel {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: var(--ui-card);
}

.platform-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.platform-sub {
  margin: 4px 0 0;
  color: var(--ui-muted);
  font-size: 13px;
}

.platform-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}

.platform-toolbar .el-input {
  width: 220px;
}

.platform-tabs {
  --el-tabs-header-height: 38px;
}

.platform-panel :deep(.el-table) {
  flex: 1;
  min-height: 0;
}

.platform-pagination {
  justify-self: end;
}

.config-view {
  padding: 4px 18px 12px;
}

.config-title {
  margin: 0 0 6px;
  color: var(--ui-muted);
  font-size: 12px;
}

.config-view pre {
  max-height: 320px;
  overflow: auto;
  margin: 0;
  padding: 12px;
  border-radius: 7px;
  background: rgba(127, 127, 127, 0.1);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
}

.full-width {
  width: 100%;
}

.mono :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
}

@media (max-width: 900px) {
  .platform-head {
    flex-direction: column;
  }

  .platform-toolbar {
    width: 100%;
    flex-wrap: wrap;
  }

  .platform-toolbar .el-input {
    flex: 1;
    min-width: 160px;
  }
}
</style>
