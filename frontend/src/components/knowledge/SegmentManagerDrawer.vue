<template>
  <!-- 分段治理抽屉（R8，FR-R8-04）：查看/编辑/删除/合并/拆分/整篇重建，全部走真实 document_segment -->
  <el-drawer v-model="segmentManagerVisible" size="720px" destroy-on-close>
    <template #header>
      <div class="seg-head">
        <p class="seg-title">分段治理 · {{ segmentManagerSourceName }}</p>
        <el-button size="small" type="primary" plain :loading="rebuilding" @click="onRebuild"
          >重建向量</el-button
        >
      </div>
    </template>

    <div v-loading="segmentsLoading" class="seg-body">
      <el-alert
        v-if="segments.length === 0 && !segmentsLoading"
        type="info"
        show-icon
        :closable="false"
        title="没有分段数据"
        description="该文档可能是历史文档（分段快照功能上线前入库）。请重新上传，或对入库任务执行一次重试。"
      />
      <template v-else>
        <p class="seg-hint">
          编辑/删除/拆分/合并会同步更新向量与关键词索引；「未同步」标记表示该段需重建向量。
        </p>
        <el-table :data="segments" size="small" empty-text="暂无分段">
          <el-table-column label="#" width="56">
            <template #default="{ row }">{{ row.segIndex + 1 }}</template>
          </el-table-column>
          <el-table-column label="内容" min-width="260">
            <template #default="{ row }">
              <p class="seg-content">{{ row.content }}</p>
            </template>
          </el-table-column>
          <el-table-column label="向量" width="88">
            <template #default="{ row }">
              <el-tag size="small" :type="row.vectorSynced ? 'success' : 'warning'">
                {{ row.vectorSynced ? '已同步' : '未同步' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row, $index }">
              <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" link type="primary" @click="openSplit(row)">拆分</el-button>
              <el-button
                v-if="$index > 0"
                size="small"
                link
                type="primary"
                @click="onMerge(row, segments[$index - 1])"
                >并入上段</el-button
              >
              <el-button size="small" link type="danger" @click="onDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="seg-toolbar">
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            layout="total, prev, pager, next"
            small
            @current-change="loadSegments"
          />
        </div>
      </template>
    </div>

    <!-- 编辑对话框 -->
    <el-dialog v-model="editVisible" title="编辑分段" width="640px">
      <el-input v-model="editContent" type="textarea" :rows="10" spellcheck="false" />
      <p class="seg-hint">保存后该段会自动删除旧向量并按新内容重新嵌入。</p>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveEdit">保存并重嵌入</el-button>
      </template>
    </el-dialog>

    <!-- 拆分对话框 -->
    <el-dialog v-model="splitVisible" title="拆分分段" width="520px">
      <el-form label-position="top">
        <el-form-item label="分隔符（按字面量切分，与分段长度二选一）">
          <el-input v-model="splitSeparator" placeholder="例如：。" />
        </el-form-item>
        <el-form-item label="分段长度（字符，未填分隔符时使用）">
          <el-input-number v-model="splitLength" :min="1" :max="20000" />
        </el-form-item>
        <p class="seg-hint">
          拆分产出 2~20 个非空分段；原段保留首段，其余按新段序追加到文档末尾并逐段重嵌入。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="splitVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveSplit">拆分</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, ref, watch } from 'vue';

import {
  deleteDocumentSegment,
  listDocumentSegments,
  mergeDocumentSegments,
  rebuildDocumentVectors,
  splitDocumentSegment,
  updateDocumentSegment,
} from '../../api/client';
import type { DocumentSegment } from '../../types/react';
import { authContext } from '../../composables/useAuthState';
import {
  segmentManagerChatId,
  segmentManagerSourceName,
  segmentManagerVisible,
} from '../../composables/useKnowledge';

const segments = ref<DocumentSegment[]>([]);
const segmentsLoading = ref(false);
const page = ref(1);
const pageSize = 50;
const total = ref(0);
const saving = ref(false);
const rebuilding = ref(false);

const editVisible = ref(false);
const editId = ref<number | null>(null);
const editContent = ref('');

const splitVisible = ref(false);
const splitId = ref<number | null>(null);
const splitSeparator = ref('');
const splitLength = ref<number>(200);

const chatId = computed(() => segmentManagerChatId.value);

watch(segmentManagerVisible, (visible) => {
  if (visible) {
    page.value = 1;
    void loadSegments();
  }
});

async function loadSegments(): Promise<void> {
  if (!chatId.value) {
    return;
  }
  segmentsLoading.value = true;
  try {
    const result = await listDocumentSegments(chatId.value, authContext(), {
      page: page.value,
      pageSize,
    });
    segments.value = result.items;
    total.value = result.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '分段列表加载失败');
  } finally {
    segmentsLoading.value = false;
  }
}

function openEdit(row: DocumentSegment): void {
  editId.value = row.id;
  editContent.value = row.content;
  editVisible.value = true;
}

async function onSaveEdit(): Promise<void> {
  if (editId.value == null) return;
  saving.value = true;
  try {
    await updateDocumentSegment(editId.value, editContent.value, authContext());
    ElMessage.success('分段已更新并重嵌入');
    editVisible.value = false;
    await loadSegments();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '分段更新失败');
  } finally {
    saving.value = false;
  }
}

function openSplit(row: DocumentSegment): void {
  splitId.value = row.id;
  splitSeparator.value = '';
  splitLength.value = 200;
  splitVisible.value = true;
}

async function onSaveSplit(): Promise<void> {
  if (splitId.value == null) return;
  saving.value = true;
  try {
    const created = await splitDocumentSegment(
      splitId.value,
      {
        separator: splitSeparator.value.trim() || undefined,
        length: splitSeparator.value.trim() ? undefined : splitLength.value,
      },
      authContext(),
    );
    ElMessage.success(`已拆分为 ${created.length} 段并重嵌入`);
    splitVisible.value = false;
    await loadSegments();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '分段拆分失败');
  } finally {
    saving.value = false;
  }
}

async function onMerge(row: DocumentSegment, previous: DocumentSegment): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `把第 ${row.segIndex + 1} 段并入第 ${previous.segIndex + 1} 段？两段内容将拼接为一个分段并重嵌入。`,
      '合并分段',
      { type: 'warning', confirmButtonText: '合并', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    await mergeDocumentSegments(row.id, previous.id, authContext());
    ElMessage.success('已合并');
    await loadSegments();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '分段合并失败');
  }
}

async function onDelete(row: DocumentSegment): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除第 ${row.segIndex + 1} 段？该段向量与关键词索引会一并删除，不可恢复。`,
      '删除分段',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    await deleteDocumentSegment(row.id, authContext());
    ElMessage.success('已删除');
    await loadSegments();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '分段删除失败');
  }
}

async function onRebuild(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '按当前分段快照删除全部旧向量后重嵌（上限 200 段）？大文档耗时稍长。',
      '重建向量',
      { type: 'warning', confirmButtonText: '重建', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  rebuilding.value = true;
  try {
    const result = await rebuildDocumentVectors(chatId.value, authContext());
    ElMessage.success(`已重建 ${result.segments} 段向量`);
    await loadSegments();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '向量重建失败');
  } finally {
    rebuilding.value = false;
  }
}
</script>

<style scoped>
.seg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.seg-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ui-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.seg-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 200px;
}

.seg-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ui-muted);
}

.seg-content {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ui-text);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

.seg-toolbar {
  display: flex;
  justify-content: flex-end;
}
</style>
