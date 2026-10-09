<template>
  <section v-loading="platformOverviewLoading" class="overview-page">
    <section class="overview-hero">
      <div>
        <p class="section-label">Workspace Overview</p>
        <h2>平台总览</h2>
        <p>基于当前租户真实配置资源统计，未返回的数据不会展示虚构指标。</p>
      </div>
      <el-button type="primary" @click="setPlatformSection('agents')">创建智能体</el-button>
    </section>

    <section class="overview-grid">
      <button v-for="card in cards" :key="card.type" class="overview-card" type="button" @click="setPlatformSection(card.section)">
        <span>{{ card.label }}</span>
        <strong>{{ platformOverview[card.type] ?? 0 }}</strong>
        <small>{{ card.description }}</small>
      </button>
    </section>

    <section class="overview-panel">
      <div class="panel-head">
        <div>
          <h3>最近更新</h3>
          <p>展示各资源最新一条真实配置记录；没有数据时明确为空。</p>
        </div>
        <el-button size="small" @click="setPlatformSection('agents')">查看智能体</el-button>
      </div>
      <el-empty v-if="recent.length === 0" description="暂无资源更新记录" />
      <ul v-else class="recent-list">
        <li v-for="item in recent" :key="item.id">
          <div>
            <strong>{{ item.name }}</strong>
            <span>{{ typeLabel(item.assetType) }}</span>
          </div>
          <small>{{ formatTime(item.updatedAt || item.createdAt) }}</small>
        </li>
      </ul>
    </section>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { listPlatformAssets } from '../../api/platform';
import type { PlatformAsset, PlatformAssetType } from '../../types/platform';
import {
  loadPlatformOverview,
  platformOverview,
  platformOverviewLoading,
  setPlatformSection,
} from '../../composables/usePlatformAssets';
import { authContext } from '../../composables/useAuthState';

const recent = ref<PlatformAsset[]>([]);
const cards = [
  { section: 'agents', type: 'agents', label: '智能体', description: '已配置的智能体数量' },
  { section: 'workflows', type: 'workflows', label: '工作流', description: '已保存的工作流' },
  { section: 'tools', type: 'tools', label: '工具', description: 'API 与 SQL 工具' },
  { section: 'knowledge', type: 'knowledge-bases', label: '知识库', description: '配置态知识库' },
  { section: 'safety-guards', type: 'safety-guards', label: '安全防护', description: '主题过滤策略' },
  { section: 'model-services', type: 'model-services', label: '模型服务', description: '可用模型配置' },
] as const;

const labels: Record<PlatformAssetType, string> = {
  agents: '智能体',
  workflows: '工作流',
  tools: '工具',
  'knowledge-bases': '知识库',
  'knowledge-files': '知识文件',
  'safety-guards': '安全防护',
  'model-services': '模型服务',
  databases: '数据库',
};

const formatTime = (value?: string) => (value ? value.replace('T', ' ').slice(0, 19) : '-');
const typeLabel = (type: PlatformAssetType) => labels[type] ?? type;

onMounted(() => {
  void loadPlatformOverview();
  void listPlatformAssets('agents', authContext(), 1, 4).then(page => {
    recent.value = page.items;
  }).catch(() => {
    recent.value = [];
  });
});
</script>

<style scoped>
.overview-page {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.overview-hero {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  padding: 20px;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: var(--ui-card);
}

.overview-hero p {
  margin: 4px 0 0;
  color: var(--ui-muted);
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.overview-card {
  text-align: left;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: var(--ui-card);
  padding: 15px;
  cursor: pointer;
  color: var(--ui-text);
  transition: border-color 160ms ease, transform 160ms ease;
}

.overview-card:hover,
.overview-card:focus-visible {
  border-color: var(--ui-accent);
  transform: translateY(-1px);
}

.overview-card span,
.overview-card small {
  display: block;
  color: var(--ui-muted);
}

.overview-card strong {
  display: block;
  margin: 10px 0 6px;
  font-size: 28px;
  line-height: 1;
}

.overview-panel,
.panel-head {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  background: var(--ui-card);
}

.overview-panel {
  padding: 16px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border: 0;
  background: transparent;
}

.panel-head p,
.overview-panel p {
  margin: 3px 0 0;
  color: var(--ui-muted);
  font-size: 12px;
}

.recent-list {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.recent-list li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--ui-border);
}

.recent-list span {
  display: block;
  margin-top: 3px;
  color: var(--ui-muted);
  font-size: 12px;
}
</style>
