// 业务效果看板（管理视角）：聚合 answer_feedback 的好评率/点踩原因，
// 供用量页管理员区块消费。加载只随页面进入与范围切换触发（同 loadUserUsage 模式）。
import { ref } from 'vue';

import { getFeedbackInsights, type FeedbackInsights } from '../api/client';
import { authContext, canUseRemoteSync } from './useAuthState';

export const feedbackInsights = ref<FeedbackInsights | null>(null);
export const feedbackInsightsLoading = ref(false);
export const feedbackInsightsError = ref('');

export async function loadFeedbackInsights(days: number): Promise<void> {
  if (!canUseRemoteSync.value) {
    return;
  }
  feedbackInsightsLoading.value = true;
  feedbackInsightsError.value = '';
  try {
    feedbackInsights.value = await getFeedbackInsights(days, authContext());
  } catch (error) {
    feedbackInsightsError.value = error instanceof Error ? error.message : '加载失败';
  } finally {
    feedbackInsightsLoading.value = false;
  }
}
