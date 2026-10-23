// 对话栏模型选择目录：可用模型服务（本租户私有 ∪ 系统共享的 LLM）的数据源与选中态。
// 加载失败静默降级为空目录（= 自动档位路由），不阻塞聊天主链路；
// 选中项持久化到 localStorage（新切片追加在 blob 尾部，老数据无损）。
// 选中语义（后端 AgentChatRuntime.prepare）：智能体绑定模型 > 手选模型 > 档位路由。
import { ref } from 'vue';

import type { ChatModelOption } from '../types/react';
import { listChatModels } from '../api/client';
import { authContext } from './useAuthState';
import { readBootstrap, registerPersistSlice } from './persistence';

const bootstrap = readBootstrap();

export const chatModels = ref<ChatModelOption[]>([]);
export const chatModelsLoaded = ref(false);
export const chatModelsLoading = ref(false);

export const selectedModelServiceId = ref<number | null>(
  typeof bootstrap.selectedModelServiceId === 'number'
    ? (bootstrap.selectedModelServiceId as number)
    : null,
);

registerPersistSlice(() => ({
  selectedModelServiceId: selectedModelServiceId.value,
}));

/** 拉取可用模型目录（只拉一次；失败静默降级，下次进入重试）。 */
export async function loadChatModels(): Promise<void> {
  if (chatModelsLoaded.value || chatModelsLoading.value) {
    return;
  }
  chatModelsLoading.value = true;
  try {
    chatModels.value = await listChatModels(authContext());
    chatModelsLoaded.value = true;
    // 目录里已不存在（删除/停用）的选择回退自动路由
    if (
      selectedModelServiceId.value &&
      !chatModels.value.some((item) => item.id === selectedModelServiceId.value)
    ) {
      selectedModelServiceId.value = null;
    }
  } catch {
    chatModels.value = [];
  } finally {
    chatModelsLoading.value = false;
  }
}

/** 切换对话模型（null = 自动档位路由）。 */
export function selectModelService(id: number | null): void {
  selectedModelServiceId.value = id;
}
