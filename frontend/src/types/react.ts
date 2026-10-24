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
  /** 绑定平台智能体（platform_asset agents id）；不传走默认助手路径。 */
  agentId?: number;
  /** 对话栏手选的模型服务 id；不传走默认档位路由，智能体已绑定模型时以绑定为准。 */
  modelServiceId?: number;
  /** 聊天内临时附件（POST /ai/chat-attachments 返回的 id）；文本注入本次问答上下文，不入知识库。 */
  attachmentId?: string;
}

/** 对话栏模型选择目录项（GET /ai/models）。 */
export interface ChatModelOption {
  id: number;
  name: string;
  modelName: string;
  /** TENANT = 本租户私有，SYSTEM = 系统共享。 */
  scope?: string;
}

export interface ReactTraceStep {
  step: number;
  thought: string;
  action: string;
  actionInput?: Record<string, unknown>;
  observation?: unknown;
  /** 本步真实耗时（毫秒）；工作流引擎轨迹与老会话历史没有此值。 */
  elapsedMs?: number | null;
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
  /** 生效的智能体 id（未绑定为空） */
  agentId?: number | null;
  /** 会话前安全拦截命中（命中时 answer=拦截文案，未发生模型调用） */
  safetyBlocked?: boolean;
  /** 拦截文案 */
  blockMessage?: string;
  /** 实际使用的模型名（绑定模型或路由模型） */
  modelName?: string | null;
  /** 模型来源：bound=智能体绑定模型服务，router=档位路由 */
  modelSource?: 'bound' | 'router' | null;
  /** 绑定模型调用失败后是否回退到了档位路由 */
  modelFallback?: boolean;
}

/** 普通用户可见的智能体目录项（GET /ai/agents，只含展示字段）。 */
export interface AgentSummary {
  id: number;
  name: string;
  description?: string | null;
  openingMessage?: string | null;
  openingQuestions?: string[];
}

export interface ReactTokenEvent {
  token: string;
}

export interface ReactErrorEvent {
  message: string;
}

export type ReactStreamEvent = 'trace' | 'token' | 'done' | 'error';

/** 前端认证上下文：只有 JWT 一条路；API Key 是程序化接入通道（X-API-Key 直调后端），不经前端。 */
export interface AuthContext {
  token?: string;
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
  handoffSummary?: string;
  handoffGeneratedAt?: number;
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

/** 用量趋势单日数据点（GET /cost/trend，裸数组元素，缺天由后端补零）。 */
export interface TenantCostTrendPoint {
  date: string;
  requestCount: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
}

/** 按用户聚合的用量（GET /cost/users，管理员/运营视角）。 */
export interface TenantUserUsage {
  /** 认证主体 user 键（JWT 用户名 / API Key 主体） */
  userId: string;
  /** 用户显示名（未匹配为 null） */
  displayName?: string | null;
  requestCount: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
}

export interface RetrievalResultItem {
  sourceType: string;
  title: string;
  chunkId: string;
  score: number;
}

export interface EvalCaseCreate {
  caseId?: string;
  category?: string;
  chatId?: string;
  question: string;
  expectedCitations?: string[];
  expectedDocumentIds?: string[];
  expectedChunkIds?: string[];
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
  retrievalMetricsCases: number;
  retrievalMetricLevel: string;
  recallAtKRate: number;
  mrrAtK: number;
  precisionAtKRate: number;
  citationCoverageRate: number;
  citationMarkerCoverageRate: number;
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
  retrievedResults: RetrievalResultItem[];
  retrievalHit: number;
  retrievalMetricsApplicable: boolean;
  retrievalMetricLevel: string;
  recallAtK: number;
  mrrAtK: number;
  precisionAtK: number;
  citationCoverage: number;
  keywordScore: number;
  citationMarkerCoverage: number;
  score: number;
  keywordScoreApplicable?: boolean;
  citationCoverageApplicable?: boolean;
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
  /** 归属知识库资产 ID（FR-R5-01）；null = 未归属，仅租户级检索可见 */
  knowledgeBaseId?: number | null;
  status: IngestionJobStatus;
  chunkCount?: number | null;
  fileSize?: number | null;
  createdAt?: string;
  finishedAt?: string;
}

/** 用户侧知识库目录项（GET /ai/knowledge-bases，FR-R5-01）：上传/试搜选择器数据源。 */
export interface KnowledgeBaseCatalogItem {
  id: number;
  name: string;
  description?: string | null;
  status?: string;
  /** 归属文档数（后端真实聚合） */
  fileCount?: number;
  /** 可用切片总数（后端真实聚合） */
  chunkCount?: number;
}

/** 文档分段（R8 分段治理）：document_segment 真实数据面。 */
export interface DocumentSegment {
  id: number;
  chatId: string;
  jobId: string;
  segIndex: number;
  content: string;
  vectorSynced: boolean;
  updatedAt: string;
}

/** 文档名 → chatId 定位（聊天引用点击跳转用）。 */
export interface DocumentLookupResult {
  chatId: string;
  sourceName: string;
}

/** 知识库「试搜」单条命中（对应后端 RetrievalPreviewItem）。 */
export interface RetrievalPreviewItem {
  source: string;
  fileName: string;
  chunkId: string;
  score: number;
  snippet: string;
}

/** 文档内容预览的单个内容块（对应后端 DocumentContentVO.Block）；page 为 PDF 页码，非 PDF 为 null。 */
export interface DocumentContentBlock {
  index: number;
  page?: number | null;
  text: string;
}

/** 文档内容预览响应（对应后端 DocumentContentVO）：按入库顺序排列的切片文本块，超长已截断。 */
export interface DocumentContent {
  chatId: string;
  sourceName: string;
  sourceType?: string;
  chunkCount: number;
  truncated: boolean;
  blocks: DocumentContentBlock[];
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
  stepId?: string;
  stepOrder: number;
  agentName?: string;
  status?: string;
  thought?: string;
  action?: string;
  actionInput?: Record<string, unknown>;
  /** 步骤产出（结构化）：人工审批步骤的 confirmationId 在此 */
  output?: unknown;
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
