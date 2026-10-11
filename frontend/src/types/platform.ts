// 平台基础功能资源模型：对应后端 PlatformAssetController 的通用 DTO。
export type PlatformAssetType =
  | 'agents'
  | 'workflows'
  | 'tools'
  | 'knowledge-bases'
  | 'knowledge-files'
  | 'safety-guards'
  | 'model-services'
  | 'databases';

export interface PlatformAsset {
  id: number;
  tenantId?: string;
  assetType: PlatformAssetType;
  /** TENANT=租户私有（默认）；SYSTEM=系统共享（仅 model-services/databases，全体租户运行时可见） */
  scope?: 'TENANT' | 'SYSTEM';
  parentId?: number | null;
  name: string;
  description?: string | null;
  status?: string;
  configJson?: string | null;
  createdBy?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface PlatformAssetPage {
  items: PlatformAsset[];
  total: number;
  page: number;
  pageSize: number;
}

export interface PlatformAssetUpsert {
  name?: string;
  description?: string;
  configJson?: string;
  parentId?: number | null;
  status?: string;
  /** 仅创建时生效：SYSTEM=系统共享（仅 model-services/databases 可用），更新时后端忽略 */
  scope?: 'TENANT' | 'SYSTEM';
}

// ---------- 工作流（R6） ----------

export interface WorkflowNodePosition {
  x: number;
  y: number;
}

export interface WorkflowNode {
  id: string;
  type: string;
  name: string;
  position: WorkflowNodePosition;
  parameters?: Record<string, unknown>;
  nextOnError?: string | null;
}

export interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
  condition?: string | null;
}

export interface WorkflowGraphConfig {
  version?: string;
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
  variables?: Record<string, unknown>;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  executionConfirm?: boolean;
  layout?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface WorkflowTraceNode {
  nodeId?: string;
  nodeName?: string;
  status?: string;
  startedAt?: string;
  endedAt?: string;
  durationMs?: number;
  outputSummary?: string;
  error?: string;
  [key: string]: unknown;
}

export interface WorkflowTestRunResult {
  status?: string;
  output?: unknown;
  trace?: WorkflowTraceNode[];
  error?: string;
  [key: string]: unknown;
}

// ---------- 工具（R7） ----------

export type ToolParamType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'OBJECT' | 'ARRAY';

export interface ToolParamSchema {
  name: string;
  type: ToolParamType;
  required: boolean;
  defaultValue?: string;
  description?: string;
}

export interface ToolTestResult {
  status?: string | number;
  latencyMs?: number;
  responseSummary?: string;
  error?: string;
  [key: string]: unknown;
}

// ---------- 安全防护（R4） ----------

export interface SafetyGuardTestResult {
  blocked?: boolean;
  hitLevel?: string;
  matchedFragments?: string[];
  blockMessage?: string;
  [key: string]: unknown;
}

// ---------- 智能体调试（R2.1） ----------

/** 调试面板单步（后端刻意不含 thought/actionInput/observation 原文）。 */
export interface AgentDebugStep {
  step: number;
  action: string;
  elapsedMs?: number | null;
  status?: string;
}

/** 智能体调试响应（POST /platform/agents/{id}/debug）。 */
export interface AgentDebugResult {
  status: 'SUCCESS' | 'BLOCKED' | 'ERROR';
  answer?: string;
  error?: string;
  latencyMs?: number;
  modelSummary?: {
    modelName?: string | null;
    source?: 'bound' | 'router' | null;
    fallbackApplied?: boolean;
    routeProfile?: string;
    routeReason?: string;
    routeCostTier?: string;
  };
  safetyResult?: {
    checked?: boolean;
    blocked?: boolean;
    blockMessage?: string;
  };
  memorySummary?: {
    memoryTurns?: number;
    injectedLabels?: string[];
  };
  steps?: AgentDebugStep[];
}

// ---------- 系统管理（R10） ----------

export interface PlatformUser {
  id: number;
  username: string;
  displayName?: string | null;
  roles?: string[];
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface PlatformUserUpdate {
  displayName?: string;
  roles?: string[];
  status?: string;
}

export interface PlatformRole {
  id: number;
  code?: string;
  name: string;
  description?: string | null;
  permissions?: string[];
  builtin?: boolean;
}

export interface PlatformRoleUpsert {
  code?: string;
  name: string;
  description?: string;
  permissions?: string[];
}
