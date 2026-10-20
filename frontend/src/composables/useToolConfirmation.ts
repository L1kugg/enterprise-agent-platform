// 平台工具人工确认（R7.3）：轨迹观测里 status=pending_confirmation 的卡片状态与审批动作。
// 模块级单例状态（与 useKnowledge 等同一套迷你 store 模式）：切页签回来，卡片已审/已取消的状态不丢。
// 审批即执行（后端 CAS 抢占 PENDING 后重验绑定再调用工具），结果就地展示；模型不参与审批后补答。
import { ElMessage } from 'element-plus';
import { reactive } from 'vue';

import { approveToolConfirmation, cancelToolConfirmation } from '../api/client';
import type { ReactTraceStep } from '../types/react';
import { authContext } from './useAuthState';

export interface PendingToolConfirmation {
  confirmationId: number;
  tool: string;
  toolId?: number;
  arguments: Record<string, unknown>;
  expiresAt: string;
  message: string;
}

export interface ConfirmationState {
  state: 'pending' | 'executed' | 'failed' | 'cancelled';
  loading: boolean;
  result?: unknown;
  message?: string;
}

const confirmStates = reactive<Record<number, ConfirmationState>>({});

/** 从轨迹步骤的观测里解析待确认任务；非 pending_confirmation 观测返回 null。 */
export function pendingConfirmationOf(step: ReactTraceStep): PendingToolConfirmation | null {
  const obs = step.observation;
  if (!obs || typeof obs !== 'object' || Array.isArray(obs)) {
    return null;
  }
  const record = obs as Record<string, unknown>;
  if (record.status !== 'pending_confirmation') {
    return null;
  }
  const id = Number(record.confirmationId);
  if (!Number.isFinite(id)) {
    return null;
  }
  confirmStateOf(id); // 惰性初始化状态槽
  return {
    confirmationId: id,
    tool: String(record.tool ?? ''),
    toolId: typeof record.toolId === 'number' ? record.toolId : undefined,
    arguments: (record.arguments ?? {}) as Record<string, unknown>,
    expiresAt: String(record.expiresAt ?? ''),
    message: String(record.message ?? ''),
  };
}

export function confirmStateOf(id: number): ConfirmationState {
  if (!confirmStates[id]) {
    confirmStates[id] = { state: 'pending', loading: false };
  }
  return confirmStates[id];
}

/** 审批并执行：EXECUTED 展示结果并触发回流回调，FAILED 展示失败文案。 */
export async function approveToolConfirm(
  id: number,
  opts?: { onExecuted?: (resultJson: string) => void },
): Promise<void> {
  const state = confirmStateOf(id);
  state.loading = true;
  try {
    const res = await approveToolConfirmation(id, authContext());
    if (res.status === 'EXECUTED') {
      state.state = 'executed';
      state.result = res.result;
      ElMessage.success('工具已执行');
      // 结果回流：让模型基于真实执行数据生成最终回答（回调由聊天视图提供续跑逻辑）
      if (opts?.onExecuted) {
        opts.onExecuted(JSON.stringify(res.result ?? null, null, 2));
      }
    } else {
      state.state = 'failed';
      state.message = res.message ?? '执行失败';
      ElMessage.error(state.message);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '确认审批失败');
  } finally {
    state.loading = false;
  }
}

/** 取消挂起任务。 */
export async function cancelToolConfirm(id: number): Promise<void> {
  const state = confirmStateOf(id);
  state.loading = true;
  try {
    await cancelToolConfirmation(id, authContext());
    state.state = 'cancelled';
    ElMessage.info('已取消该工具调用');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '取消失败');
  } finally {
    state.loading = false;
  }
}
