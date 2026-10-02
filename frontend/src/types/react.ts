export interface AuthTokenResponse {
  ok: number;
  msg: string;
  token?: string;
  refreshToken?: string;
  tenantId?: string;
  /** 首个角色（ADMIN / USER），用于显隐管理员 UI（如删除文档按钮）。 */
  role?: string;
  expiresInSeconds?: number;
  refreshWillExpireSoon?: boolean;
}

export interface ReactChatRequest {
  prompt: string;
  chatId: string;
  modelProfile?: string;
}

export interface ReactTraceStep {
  step: number;
  thought: string;
  action: string;
  actionInput?: Record<string, unknown>;
  observation?: unknown;
}

export interface ReactChatResponse {
  ok: number;
  msg: string;
  chatId: string;
  answer: string;
  citations?: string[];
  evidence?: string[];
  routeProfile?: string;
  routeReason?: string;
  routeCostTier?: string;
  experimentKey?: string;
  experimentVariant?: string;
  experimentBucket?: number;
  trace: ReactTraceStep[];
}

export interface ReactTokenEvent {
  token: string;
}

export interface ReactErrorEvent {
  message: string;
}

export type ReactStreamEvent = 'trace' | 'token' | 'done' | 'error';

export interface AuthContext {
  token?: string;
  apiKey?: string;
  tenantId?: string;
}

export interface SessionMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: number;
  state?: 'pending' | 'streaming' | 'done' | 'error' | 'stopped';
  citations?: string[];
  evidence?: string[];
}

export interface SessionBranch {
  id: string;
  title: string;
  parentBranchId: string | null;
  parentMessageId: string | null;
  updatedAt: number;
  messages: SessionMessage[];
  traceSteps: ReactTraceStep[];
}

export interface SessionState {
  id: string;
  title: string;
  updatedAt: number;
  modelProfile: string;
  streaming: boolean;
  pinned: boolean;
  archived: boolean;
  workspaceId: string;
  activeBranchId: string;
  branches: SessionBranch[];
}

export interface BranchCompareRequest {
  sourceBranchId: string;
  targetBranchId: string;
}

export interface BranchCompareResult {
  sourceBranchId: string;
  targetBranchId: string;
  sourceMessageCount: number;
  targetMessageCount: number;
  commonMessageCount: number;
  sourceOnlyCount: number;
  targetOnlyCount: number;
  sourceOnlyPreview: string[];
  targetOnlyPreview: string[];
}

export interface BranchMergeRequest {
  sourceBranchId: string;
  targetBranchId: string;
  title?: string;
}

export interface BranchMergeResult {
  session: SessionState;
  mergedBranch: SessionBranch;
  mergedMessageCount: number;
}

export interface FeedbackRequest {
  chatId: string;
  sessionId?: string;
  branchId?: string;
  messageId?: string;
  rating: number;
  comment?: string;
  question?: string;
  answer: string;
}

export interface TenantCostSummary {
  tenantId: string;
  month: string;
  monthlyBudgetUsd: number;
  hardLimitEnabled: boolean;
  monthCostUsd: number;
  monthRequestCount: number;
  monthInputTokens: number;
  monthOutputTokens: number;
  todayCostUsd: number;
  todayRequestCount: number;
  budgetRemainingUsd: number;
  budgetExceeded: boolean;
}

export interface TenantBudgetUpdate {
  tenantId?: string;
  monthlyBudgetUsd?: number;
  hardLimitEnabled?: boolean;
}

/** 用量趋势单日数据点（GET /cost/trend，裸数组元素，缺天由后端补零）。 */
export interface TenantCostTrendPoint {
  date: string;
  requestCount: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
}

export interface EvalCaseCreate {
  caseId?: string;
  category?: string;
  chatId?: string;
  question: string;
  expectedCitations?: string[];
  expectedKeywords?: string[];
  forbiddenKeywords?: string[];
}

export interface EvalDatasetCreate {
  name: string;
  description?: string;
  cases: EvalCaseCreate[];
}

export interface EvalDataset {
  datasetId: string;
  tenantId: string;
  name: string;
  description?: string;
  baselineRunId?: string;
  caseCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface EvalDatasetDeleteResult {
  datasetName: string;
  cases: number;
  runs: number;
  results: number;
}

export interface EvalRunRequest {
  modelProfile?: string;
  chatIdPrefix?: string;
}

export interface EvalMetricSummary {
  totalCases: number;
  passedCases: number;
  runScore: number;
  retrievalHitRate: number;
  citationCoverageRate: number;
  answerFaithfulnessScore: number;
  avgLatencyMs: number;
  failureRate: number;
}

export interface EvalResult {
  resultId: string;
  caseId: string;
  status: string;
  question: string;
  answer: string;
  citations: string[];
  evidence: string[];
  retrievalHit: number;
  citationCoverage: number;
  keywordScore: number;
  answerFaithfulness: number;
  score: number;
  latencyMs: number;
  errorMessage?: string;
}

export interface EvalRun {
  runId: string;
  datasetId: string;
  tenantId: string;
  status: string;
  modelProfile: string;
  metrics: EvalMetricSummary;
  results: EvalResult[];
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  createdAt: string;
}

export interface EvalComparison {
  dataset: EvalDataset;
  baseline?: EvalRun | null;
  current?: EvalRun | null;
}

export type IngestionJobStatus = 'PENDING' | 'RUNNING' | 'RETRY' | 'SUCCEEDED' | 'FAILED';

export interface IngestionJob {
  jobId: string;
  chatId: string;
  sourceName: string;
  status: IngestionJobStatus;
  attemptCount?: number;
  maxRetries?: number;
  errorMessage?: string;
  traceId?: string;
  queueBackend?: string;
  createdAt?: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface IngestionSubmitResponse {
  ok: number;
  msg: string;
  job?: IngestionJob;
}

/** 管理员跨租户文档总览的单条文档（= 同一 chatId 的最新入库任务）。 */
export interface AdminDocumentSummary {
  tenantId: string;
  chatId: string;
  sourceName: string;
  status: IngestionJobStatus;
  attemptCount?: number;
  errorMessage?: string;
  fileSize?: number | null;
  createdAt?: string;
  finishedAt?: string;
}

/** 知识库「文档清单」的单条文档（本租户内同一 chatId 的最新入库任务）。 */
export interface IngestionDocumentSummary {
  chatId: string;
  sourceName: string;
  sourceType?: string;
  status: IngestionJobStatus;
  chunkCount?: number | null;
  fileSize?: number | null;
  createdAt?: string;
  finishedAt?: string;
}

/** 知识库「试搜」单条命中（对应后端 RetrievalPreviewItem）。 */
export interface RetrievalPreviewItem {
  source: string;
  fileName: string;
  chunkId: string;
  score: number;
  snippet: string;
}

/** 知识库「试搜」结果（对应后端 RetrievalPreviewResult）。 */
export interface RetrievalPreviewResult {
  items: RetrievalPreviewItem[];
  degradedSources: string[];
}

/** Agent 引擎：standard = 主聊天 ReAct；workflow = 工作流版 ReAct（换接口前缀）。 */
export type AgentEngine = 'standard' | 'workflow';

/** 工作流 agent 单步留痕（对应后端 WorkflowStepVO）。 */
export interface WorkflowStep {
  stepOrder: number;
  agentName?: string;
  status?: string;
  thought?: string;
  action?: string;
  actionInput?: Record<string, unknown>;
  observation?: unknown;
  latencyMs?: number;
  inputTokens?: number;
  outputTokens?: number;
  errorMessage?: string;
}

/** 工作流任务（对应后端 WorkflowTaskVO；列表接口不带 events）。 */
export interface WorkflowTask {
  taskId: string;
  type: string;
  status: string;
  userInput?: string;
  finalOutput?: string;
  modelProfile?: string;
  createdAt?: string;
  updatedAt?: string;
  steps?: WorkflowStep[];
}

/** 深度研究任务创建/查询结果（报告为四节结构中文 Markdown）。 */
export interface DeepResearchResult {
  taskId: string;
  topic: string;
  report?: string;
  status: string;
}
