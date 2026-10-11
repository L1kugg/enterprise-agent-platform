// 智能体目录（FR-R1-04）：工作台「智能体选择器」的数据源与选中态。
// 加载失败静默降级为空目录（= 默认助手），不阻塞聊天主链路；
// 选中项持久化到 localStorage（新切片追加在 blob 尾部，老数据无损）。
import { computed, ref } from 'vue';

import type { AgentSummary } from '../types/react';
import { listUsableAgents } from '../api/client';
import { authContext } from './useAuthState';
import { readBootstrap, registerPersistSlice } from './persistence';

const bootstrap = readBootstrap();

export const agents = ref<AgentSummary[]>([]);
export const agentsLoaded = ref(false);
export const agentsLoading = ref(false);

export const selectedAgentId = ref<number | null>(
  typeof bootstrap.selectedAgentId === 'number' ? (bootstrap.selectedAgentId as number) : null,
);

registerPersistSlice(() => ({
  selectedAgentId: selectedAgentId.value,
}));

export const selectedAgent = computed<AgentSummary | null>(
  () => agents.value.find((item) => item.id === selectedAgentId.value) ?? null,
);

/** 拉取已发布智能体目录（只拉一次；失败静默降级，下次进入重试）。 */
export async function loadAgents(): Promise<void> {
  if (agentsLoaded.value || agentsLoading.value) {
    return;
  }
  agentsLoading.value = true;
  try {
    agents.value = await listUsableAgents(authContext());
    agentsLoaded.value = true;
    // 目录里已不存在（下架/删除）的选择回退默认助手
    if (selectedAgentId.value && !agents.value.some((item) => item.id === selectedAgentId.value)) {
      selectedAgentId.value = null;
    }
  } catch {
    agents.value = [];
  } finally {
    agentsLoading.value = false;
  }
}

/** 切换智能体（null = 默认助手）。 */
export function selectAgent(id: number | null): void {
  selectedAgentId.value = id;
}
