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
