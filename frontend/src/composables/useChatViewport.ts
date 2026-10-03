// 聊天视口（虚拟滚动）：容器 ref、行高表、上下占位、滚动位置与 ResizeObserver。
// 从 App.vue 单体拆出，字段与原定义逐字一致。依赖 useChatState 的 messages/sending。
// ⚠ watch(messages 清理行高表) 与 onMounted 里的 ResizeObserver 初始化不放在这里，
// 由 App/useAppBootstrap 的生命周期按原顺序注册（registerViewportEffects，3.15 步）。
import { computed, nextTick, ref, watch } from 'vue';

import { ESTIMATED_ROW_HEIGHT, OVERSCAN_COUNT } from '../utils/constants';
import type { MessageMetric } from '../types/console';
import { messages, sending } from './useChatState';

// 首屏骨架：加载会话后等 220ms 再撤掉，避免布局抖动
export const hydrating = ref(true);
export const messageContainer = ref<HTMLElement | null>(null);
export const messageHeights = ref<Record<string, number>>({});
export const viewportHeight = ref(0);
export const scrollTop = ref(0);
const messageRowElements = new Map<string, HTMLElement>();
let resizeObserver: ResizeObserver | null = null;

export const messageMetrics = computed<MessageMetric[]>(() => {
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

export const totalVirtualHeight = computed(() => {
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

export const virtualRange = computed(() => {
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

export const virtualMessages = computed(() => {
  const metrics = messageMetrics.value;
  const { start, end } = virtualRange.value;
  if (end < start) {
    return [];
  }
  return metrics.slice(start, end + 1);
});

export const virtualTopSpacer = computed(() => virtualMessages.value[0]?.offset ?? 0);

export const virtualBottomSpacer = computed(() => {
  if (virtualMessages.value.length === 0) {
    return 0;
  }

  const last = virtualMessages.value[virtualMessages.value.length - 1];
  return Math.max(0, totalVirtualHeight.value - (last.offset + last.height));
});

export function updateViewport(): void {
  viewportHeight.value = messageContainer.value?.clientHeight ?? 0;
}

export function onMessageScroll(): void {
  const element = messageContainer.value;
  if (!element) {
    return;
  }
  scrollTop.value = element.scrollTop;
}

export function syncMessageHeight(messageId: string, height: number): void {
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

/** 模板里的函数式 ref：行元素挂载/卸载时维护观测表与行高。 */
export function setMessageRowRef(messageId: string, element: HTMLElement | null): void {
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

export async function scrollToBottom(force = false): Promise<void> {
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

/**
 * 视口副作用，只允许被调用一次（当前 App.vue setup 原位调用；3.15 步起由
 * useAppBootstrap 统一注册）：消息列表变化时清掉已不存在消息的行高记录。
 */
export function registerViewportEffects(): void {
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
}

/** 创建行高 ResizeObserver（原 onMounted 里的一段，逐字搬入）。 */
export function createMessageResizeObserver(): void {
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
}

/** 卸载清理：移除 resize 监听 + 断开行高观测（原 onBeforeUnmount 里的一段，逐字搬入）。 */
export function teardownViewport(): void {
  window.removeEventListener('resize', updateViewport);

  if (resizeObserver) {
    messageRowElements.forEach((element) => {
      resizeObserver?.unobserve(element);
    });
    resizeObserver.disconnect();
    resizeObserver = null;
  }
}
