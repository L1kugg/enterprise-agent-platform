import type {
  AdminDocumentSummary,
  AgentEngine,
  AuthContext,
  AuthTokenResponse,
  BranchCompareRequest,
  BranchCompareResult,
  BranchMergeRequest,
  BranchMergeResult,
  DeepResearchResult,
  EvalComparison,
  EvalDataset,
  EvalDatasetCreate,
  EvalDatasetDeleteResult,
  EvalRun,
  EvalRunRequest,
  FeedbackRequest,
  IngestionDocumentSummary,
  IngestionJob,
  IngestionSubmitResponse,
  RetrievalPreviewResult,
  ReactChatRequest,
  ReactChatResponse,
  ReactStreamEvent,
  SessionState,
  TenantBudgetUpdate,
  TenantCostSummary,
  TenantCostTrendPoint,
  WorkflowTask,
} from '../types/react';

const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api';

function resolveApi(path: string): string {
  if (path.startsWith('http://') || path.startsWith('https://')) {
    return path;
  }
  return `${API_BASE}${path}`;
}

function buildAuthHeaders(auth?: AuthContext): HeadersInit {
  const headers: Record<string, string> = {};
  if (auth?.token) {
    headers.Authorization = `Bearer ${auth.token}`;
  } else if (auth?.apiKey) {
    headers['X-API-Key'] = auth.apiKey;
  }
  if (auth?.tenantId) {
    headers['X-Tenant-ID'] = auth.tenantId;
  }
  return headers;
}

async function parseJsonSafely<T>(response: Response): Promise<T | null> {
  const text = await response.text();
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}

function formatHttpError(status: number, message: string): Error {
  // 认证接口的业务失败走 HTTP 200 + ok=0，直接展示后端 msg；
  // 真正的 HTTP 错误才在文案里标注错误码，方便排查。
  if (status >= 200 && status < 300) {
    return new Error(message || '请求失败，请稍后重试');
  }
  return new Error(`请求失败（错误码 ${status}）：${message || '请稍后重试'}`);
}

function withQuery(
  path: string,
  params?: Record<string, string | number | boolean | undefined>,
): string {
  if (!params) {
    return path;
  }
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') {
      return;
    }
    search.set(key, String(value));
  });
  const query = search.toString();
  if (!query) {
    return path;
  }
  return `${path}?${query}`;
}

export async function exchangeApiKey(
  apiKey: string,
  tenantId?: string,
): Promise<AuthTokenResponse> {
  const response = await fetch(resolveApi('/auth/token'), {
    credentials: 'include',
    method: 'POST',
    headers: {
      'X-API-Key': apiKey,
      ...(tenantId ? { 'X-Tenant-ID': tenantId } : {}),
    },
  });
  const payload = await parseJsonSafely<AuthTokenResponse>(response);
  if (!response.ok || !payload || payload.ok !== 1) {
    throw formatHttpError(response.status, payload?.msg ?? 'API Key 登录失败');
  }
  return payload;
}

export async function refreshJwt(refreshToken: string): Promise<AuthTokenResponse> {
  const response = await fetch(resolveApi('/auth/refresh'), {
    credentials: 'include',
    method: 'POST',
    headers: {
      'X-Refresh-Token': refreshToken,
    },
  });
  const payload = await parseJsonSafely<AuthTokenResponse>(response);
  if (!response.ok || !payload || payload.ok !== 1) {
    throw formatHttpError(response.status, payload?.msg ?? '登录刷新失败');
  }
  return payload;
}

/** 注册 / 密码登录共用：POST JSON 凭据，成功即返回会话。 */
async function postAuthCredentials(
  path: string,
  username: string,
  password: string,
): Promise<AuthTokenResponse> {
  const response = await fetch(resolveApi(path), {
    credentials: 'include',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ username, password }),
  });
  const payload = await parseJsonSafely<AuthTokenResponse>(response);
  if (!response.ok || !payload || payload.ok !== 1) {
    throw formatHttpError(response.status, payload?.msg ?? '登录失败');
  }
  return payload;
}

export function registerUser(username: string, password: string): Promise<AuthTokenResponse> {
  return postAuthCredentials('/auth/register', username, password);
}

export function loginWithPassword(username: string, password: string): Promise<AuthTokenResponse> {
  return postAuthCredentials('/auth/login', username, password);
}

/** Agent 聊天接口前缀：workflow 引擎换工作流版前缀（同一对请求/响应结构）。 */
function reactChatEndpoint(engine: AgentEngine | undefined, stream: boolean): string {
  const base = engine === 'workflow' ? '/ai/workflow/react/chat' : '/ai/react/chat';
  return stream ? `${base}/stream` : base;
}

export async function reactChat(
  request: ReactChatRequest,
  auth?: AuthContext,
  signal?: AbortSignal,
  engine?: AgentEngine,
): Promise<ReactChatResponse> {
  const response = await fetch(resolveApi(reactChatEndpoint(engine, false)), {
    credentials: 'include',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...buildAuthHeaders(auth),
    },
    body: JSON.stringify(request),
    signal,
  });
  const payload = await parseJsonSafely<ReactChatResponse>(response);
  if (!response.ok || !payload || payload.ok !== 1) {
    throw formatHttpError(response.status, payload?.msg ?? '消息发送失败');
  }
  return payload;
}

type StreamHandler = (event: ReactStreamEvent, payload: unknown) => void;

interface ParsedEvent {
  event: ReactStreamEvent;
  payload: unknown;
}

function parseSseChunk(rawChunk: string): ParsedEvent | null {
  const normalized = rawChunk.replace(/\r/g, '');
  const lines = normalized.split('\n');
  let eventName: ReactStreamEvent = 'token';
  const dataLines: string[] = [];
  for (const line of lines) {
    if (!line.trim()) {
      continue;
    }
    if (line.startsWith('data:event:')) {
      const parsed = line.slice('data:event:'.length).trim();
      if (parsed === 'trace' || parsed === 'token' || parsed === 'done' || parsed === 'error') {
        eventName = parsed;
      }
      continue;
    }
    if (line.startsWith('data:data:')) {
      const payload = line.slice('data:data:'.length).trim();
      if (payload) {
        dataLines.push(payload);
      }
      continue;
    }
    if (line.startsWith('event:')) {
      const parsed = line.slice('event:'.length).trim();
      if (parsed === 'trace' || parsed === 'token' || parsed === 'done' || parsed === 'error') {
        eventName = parsed;
      }
      continue;
    }
    if (line.startsWith('data:')) {
      const payload = line.slice('data:'.length).trim();
      if (payload) {
        dataLines.push(payload);
      }
    }
  }
  if (dataLines.length === 0) {
    return null;
  }
  const joined = dataLines.join('\n');
  try {
    return {
      event: eventName,
      payload: JSON.parse(joined),
    };
  } catch {
    return {
      event: eventName,
      payload: joined,
    };
  }
}

function takeNextChunk(input: string): { chunk: string; rest: string } | null {
  const lf = input.indexOf('\n\n');
  const crlf = input.indexOf('\r\n\r\n');

  if (lf < 0 && crlf < 0) {
    return null;
  }

  let splitAt = lf;
  let separatorLength = 2;
  if (lf < 0 || (crlf >= 0 && crlf < lf)) {
    splitAt = crlf;
    separatorLength = 4;
  }

  return {
    chunk: input.slice(0, splitAt),
    rest: input.slice(splitAt + separatorLength),
  };
}

export async function streamReactChat(
  request: ReactChatRequest,
  auth: AuthContext | undefined,
  onEvent: StreamHandler,
  signal?: AbortSignal,
  engine?: AgentEngine,
): Promise<void> {
  const response = await fetch(resolveApi(reactChatEndpoint(engine, true)), {
    credentials: 'include',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...buildAuthHeaders(auth),
    },
    body: JSON.stringify(request),
    signal,
  });

  if (!response.ok) {
    const errPayload = await parseJsonSafely<{ msg?: string }>(response);
    throw formatHttpError(response.status, errPayload?.msg ?? '流式连接建立失败');
  }
  if (!response.body) {
    throw new Error('流式响应内容为空');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let reading = true;

  while (reading) {
    const { done, value } = await reader.read();
    if (done) {
      reading = false;
      continue;
    }
    buffer += decoder.decode(value, { stream: true });

    let next = takeNextChunk(buffer);
    while (next) {
      const chunk = next.chunk;
      buffer = next.rest;
      const parsed = parseSseChunk(chunk);
      if (parsed) {
        onEvent(parsed.event, parsed.payload);
      }
      next = takeNextChunk(buffer);
    }
  }

  buffer += decoder.decode();

  if (buffer.trim()) {
    const parsed = parseSseChunk(buffer);
    if (parsed) {
      onEvent(parsed.event, parsed.payload);
    }
  }
}

export interface PagedResult<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export async function listSessionStates(
  auth?: AuthContext,
  params?: {
    page?: number;
    pageSize?: number;
    search?: string;
    workspace?: string;
    includeArchived?: boolean;
  },
): Promise<PagedResult<SessionState>> {
  const response = await fetch(
    resolveApi(
      withQuery('/ai/sessions', {
        page: params?.page ?? 1,
        pageSize: params?.pageSize ?? 50,
        search: params?.search ?? '',
        workspace: params?.workspace ?? 'all',
        includeArchived: params?.includeArchived ?? true,
      }),
    ),
    {
      credentials: 'include',
      method: 'GET',
      headers: buildAuthHeaders(auth),
    },
  );
  const payload = await parseJsonSafely<PagedResult<SessionState>>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '会话列表加载失败');
  }
  return payload;
}

export async function saveSessionState(
  session: SessionState,
  auth?: AuthContext,
): Promise<SessionState> {
  const response = await fetch(resolveApi(`/ai/sessions/${encodeURIComponent(session.id)}`), {
    credentials: 'include',
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      ...buildAuthHeaders(auth),
    },
    body: JSON.stringify(session),
  });
  const payload = await parseJsonSafely<SessionState>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '会话保存失败');
  }
  return payload;
}

export async function setSessionPinned(
  sessionId: string,
  value: boolean,
  auth?: AuthContext,
): Promise<SessionState> {
  const response = await fetch(
    resolveApi(withQuery(`/ai/sessions/${encodeURIComponent(sessionId)}/pin`, { value })),
    {
      credentials: 'include',
      method: 'POST',
      headers: buildAuthHeaders(auth),
    },
  );
  const payload = await parseJsonSafely<SessionState>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '会话置顶设置失败');
  }
  return payload;
}

export async function setSessionArchived(
  sessionId: string,
  value: boolean,
  auth?: AuthContext,
): Promise<SessionState> {
  const response = await fetch(
    resolveApi(withQuery(`/ai/sessions/${encodeURIComponent(sessionId)}/archive`, { value })),
    {
      credentials: 'include',
      method: 'POST',
      headers: buildAuthHeaders(auth),
    },
  );
  const payload = await parseJsonSafely<SessionState>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '会话归档设置失败');
  }
  return payload;
}

export async function compareSessionBranches(
  sessionId: string,
  request: BranchCompareRequest,
  auth?: AuthContext,
): Promise<BranchCompareResult> {
  const response = await fetch(
    resolveApi(`/ai/sessions/${encodeURIComponent(sessionId)}/branches/compare`),
    {
      credentials: 'include',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...buildAuthHeaders(auth),
      },
      body: JSON.stringify(request),
    },
  );
  const payload = await parseJsonSafely<BranchCompareResult>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '分支对比失败');
  }
  return payload;
}

export async function mergeSessionBranches(
  sessionId: string,
  request: BranchMergeRequest,
  auth?: AuthContext,
): Promise<BranchMergeResult> {
  const response = await fetch(
    resolveApi(`/ai/sessions/${encodeURIComponent(sessionId)}/branches/merge`),
    {
      credentials: 'include',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...buildAuthHeaders(auth),
      },
      body: JSON.stringify(request),
    },
  );
  const payload = await parseJsonSafely<BranchMergeResult>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '分支合并失败');
  }
  return payload;
}

interface BasicResult {
  ok: number;
  msg: string;
}

export async function submitAnswerFeedback(
  request: FeedbackRequest,
  auth?: AuthContext,
): Promise<void> {
  const response = await fetch(resolveApi('/ai/feedback'), {
    credentials: 'include',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...buildAuthHeaders(auth),
    },
    body: JSON.stringify(request),
  });
  const payload = await parseJsonSafely<BasicResult>(response);
  if (!response.ok || !payload || payload.ok !== 1) {
    throw formatHttpError(response.status, payload?.msg ?? '评价提交失败');
  }
}

export async function getTenantCostSummary(auth?: AuthContext): Promise<TenantCostSummary> {
  const response = await fetch(resolveApi('/cost/summary'), {
    credentials: 'include',
    method: 'GET',
    headers: buildAuthHeaders(auth),
  });
  const payload = await parseJsonSafely<TenantCostSummary>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '用量汇总加载失败');
  }
  return payload;
}

/** 本租户近 N 天逐日用量趋势（含今日，后端返回裸数组，缺天补零）。 */
export async function getTenantCostTrend(
  days: number,
  auth?: AuthContext,
): Promise<TenantCostTrendPoint[]> {
  const response = await fetch(resolveApi(withQuery('/cost/trend', { days })), {
    credentials: 'include',
    method: 'GET',
    headers: buildAuthHeaders(auth),
  });
  const payload = await parseJsonSafely<TenantCostTrendPoint[]>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '用量趋势加载失败');
  }
  return payload;
}

export async function updateTenantBudget(
  request: TenantBudgetUpdate,
  auth?: AuthContext,
): Promise<TenantCostSummary> {
  const response = await fetch(resolveApi('/cost/budget'), {
    credentials: 'include',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...buildAuthHeaders(auth),
    },
    body: JSON.stringify(request),
  });
  const payload = await parseJsonSafely<TenantCostSummary>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '预算更新失败');
  }
  return payload;
}

export async function createEvalDataset(
  request: EvalDatasetCreate,
  auth?: AuthContext,
): Promise<EvalDataset> {
  const response = await fetch(resolveApi('/ai/evaluation/datasets'), {
    credentials: 'include',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...buildAuthHeaders(auth),
    },
    body: JSON.stringify(request),
  });
  const payload = await parseJsonSafely<EvalDataset>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '评测集创建失败');
  }
  return payload;
}

export async function listEvalDatasets(auth?: AuthContext): Promise<EvalDataset[]> {
  const response = await fetch(resolveApi('/ai/evaluation/datasets'), {
    credentials: 'include',
    method: 'GET',
    headers: buildAuthHeaders(auth),
  });
  const payload = await parseJsonSafely<EvalDataset[]>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '评测集列表加载失败');
  }
  return payload;
}

/** 删除评测集：后端联动清理其题目、运行与结果明细，返回清理回执供提示展示。 */
export async function deleteEvalDataset(
  datasetId: string,
  auth?: AuthContext,
): Promise<EvalDatasetDeleteResult> {
  const response = await fetch(
    resolveApi(`/ai/evaluation/datasets/${encodeURIComponent(datasetId)}`),
    {
      credentials: 'include',
      method: 'DELETE',
      headers: buildAuthHeaders(auth),
    },
  );
  const payload = await parseJsonSafely<EvalDatasetDeleteResult>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '评测集删除失败');
  }
  return payload;
}

export async function triggerEvalRun(
  datasetId: string,
  request: EvalRunRequest,
  auth?: AuthContext,
): Promise<EvalRun> {
  const response = await fetch(
    resolveApi(`/ai/evaluation/datasets/${encodeURIComponent(datasetId)}/runs`),
    {
      credentials: 'include',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...buildAuthHeaders(auth),
      },
      body: JSON.stringify(request),
    },
  );
  const payload = await parseJsonSafely<EvalRun>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '评测运行失败');
  }
  return payload;
}

export async function getEvalComparison(
  datasetId: string,
  auth?: AuthContext,
): Promise<EvalComparison> {
  const response = await fetch(
    resolveApi(`/ai/evaluation/datasets/${encodeURIComponent(datasetId)}/comparison`),
    {
      credentials: 'include',
      method: 'GET',
      headers: buildAuthHeaders(auth),
    },
  );
  const payload = await parseJsonSafely<EvalComparison>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '评测对比加载失败');
  }
  return payload;
}

export async function markEvalRunBaseline(runId: string, auth?: AuthContext): Promise<EvalRun> {
  const response = await fetch(
    resolveApi(`/ai/evaluation/runs/${encodeURIComponent(runId)}/baseline`),
    {
      credentials: 'include',
      method: 'POST',
      headers: buildAuthHeaders(auth),
    },
  );
  const payload = await parseJsonSafely<EvalRun>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '基线设置失败');
  }
  return payload;
}

export async function exportEvalRunReport(runId: string, auth?: AuthContext): Promise<string> {
  const response = await fetch(
    resolveApi(`/ai/evaluation/runs/${encodeURIComponent(runId)}/report`),
    {
      credentials: 'include',
      method: 'GET',
      headers: buildAuthHeaders(auth),
    },
  );
  const text = await response.text();
  if (!response.ok) {
    throw formatHttpError(response.status, text || '评测报告导出失败');
  }
  return text;
}

export async function uploadIngestionDocument(
  chatId: string,
  file: File,
  auth?: AuthContext,
): Promise<IngestionSubmitResponse> {
  const form = new FormData();
  form.append('file', file);
  const response = await fetch(resolveApi(`/ingestion/upload/${encodeURIComponent(chatId)}`), {
    credentials: 'include',
    method: 'POST',
    // 不手动设 Content-Type：multipart 的边界串由浏览器自动生成
    headers: buildAuthHeaders(auth),
    body: form,
  });
  const payload = await parseJsonSafely<IngestionSubmitResponse>(response);
  if (!response.ok || !payload || payload.ok !== 1) {
    throw formatHttpError(response.status, payload?.msg ?? '文档上传失败');
  }
  return payload;
}

export async function listRecentIngestionJobs(
  auth?: AuthContext,
  limit = 20,
): Promise<IngestionJob[]> {
  const response = await fetch(resolveApi(withQuery('/ingestion/jobs/recent', { limit })), {
    credentials: 'include',
    method: 'GET',
    headers: buildAuthHeaders(auth),
  });
  const payload = await parseJsonSafely<IngestionJob[]>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '解析任务列表加载失败');
  }
  return payload;
}

export async function deleteIngestionDocument(chatId: string, auth?: AuthContext): Promise<string> {
  const response = await fetch(resolveApi(`/ingestion/documents/${encodeURIComponent(chatId)}`), {
    credentials: 'include',
    method: 'DELETE',
    headers: buildAuthHeaders(auth),
  });
  const payload = await parseJsonSafely<BasicResult>(response);
  if (!response.ok || !payload || payload.ok !== 1) {
    throw formatHttpError(response.status, payload?.msg ?? '文档删除失败');
  }
  return payload.msg;
}

/** 知识库文档清单：本租户内按 chat 分组的文档，分页 + 按文件名/批次搜索。 */
export async function listIngestionDocuments(
  auth?: AuthContext,
  params?: { page?: number; pageSize?: number; search?: string },
): Promise<PagedResult<IngestionDocumentSummary>> {
  const response = await fetch(
    resolveApi(
      withQuery('/ingestion/documents', {
        page: params?.page ?? 1,
        pageSize: params?.pageSize ?? 20,
        search: params?.search ?? '',
      }),
    ),
    {
      credentials: 'include',
      method: 'GET',
      headers: buildAuthHeaders(auth),
    },
  );
  const payload = await parseJsonSafely<PagedResult<IngestionDocumentSummary>>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '文档列表加载失败');
  }
  return payload;
}

/** 知识库试搜：只召回不出答案（不调 LLM），用于验证内容能否被检索到。 */
export async function searchIngestionPreview(
  query: string,
  auth?: AuthContext,
  topK = 6,
): Promise<RetrievalPreviewResult> {
  const response = await fetch(resolveApi(withQuery('/ingestion/search', { q: query, topK })), {
    credentials: 'include',
    method: 'GET',
    headers: buildAuthHeaders(auth),
  });
  const payload = await parseJsonSafely<RetrievalPreviewResult>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '试搜失败');
  }
  return payload;
}

/** 管理员跨租户文档总览：分页 + 搜索（租户/批次/文件名）。 */
export async function listAdminDocuments(
  auth: AuthContext | undefined,
  params?: { page?: number; pageSize?: number; search?: string },
): Promise<PagedResult<AdminDocumentSummary>> {
  const response = await fetch(
    resolveApi(
      withQuery('/admin/documents', {
        page: params?.page ?? 1,
        pageSize: params?.pageSize ?? 20,
        search: params?.search ?? '',
      }),
    ),
    {
      credentials: 'include',
      method: 'GET',
      headers: buildAuthHeaders(auth),
    },
  );
  const payload = await parseJsonSafely<PagedResult<AdminDocumentSummary>>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '文档总览加载失败');
  }
  return payload;
}

/** 管理员跨租户删除文档：tenantId 来自总览列表数据回传。 */
export async function deleteAdminDocument(
  tenantId: string,
  chatId: string,
  auth?: AuthContext,
): Promise<string> {
  const response = await fetch(
    resolveApi(`/admin/documents/${encodeURIComponent(tenantId)}/${encodeURIComponent(chatId)}`),
    {
      credentials: 'include',
      method: 'DELETE',
      headers: buildAuthHeaders(auth),
    },
  );
  const payload = await parseJsonSafely<BasicResult>(response);
  if (!response.ok || !payload || payload.ok !== 1) {
    throw formatHttpError(response.status, payload?.msg ?? '文档删除失败');
  }
  return payload.msg;
}

/** 工作流任务列表（含深度研究任务，按 type 过滤；后端返回裸数组，元素带 steps）。 */
export async function listWorkflowTasks(
  auth?: AuthContext,
  page = 1,
  pageSize = 20,
): Promise<WorkflowTask[]> {
  const response = await fetch(resolveApi(withQuery('/ai/workflow/tasks', { page, pageSize })), {
    credentials: 'include',
    method: 'GET',
    headers: buildAuthHeaders(auth),
  });
  const payload = await parseJsonSafely<WorkflowTask[]>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, '任务列表加载失败');
  }
  return payload;
}

/** 发起深度研究：后端异步受理即返回 202（report 为空，状态 PLANNING），报告经 getResearchReport 轮询获取。 */
export async function createResearchTask(
  topic: string,
  modelProfile: string | undefined,
  auth?: AuthContext,
  signal?: AbortSignal,
): Promise<DeepResearchResult> {
  const response = await fetch(resolveApi('/ai/research/tasks'), {
    credentials: 'include',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...buildAuthHeaders(auth),
    },
    body: JSON.stringify({ topic, modelProfile }),
    signal,
  });
  // 成功时返回裸 DeepResearchResult（无 ok/msg 包装），错误响应体里才有 msg（429 队列满等）
  const payload = await parseJsonSafely<DeepResearchResult & { msg?: string }>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, payload?.msg ?? '深度研究创建失败');
  }
  return payload;
}

/** 查询单个深度研究任务详情（按 taskId 精确轮询；任务不存在或跨租户不可见返回 404）。 */
export async function getResearchTask(taskId: string, auth?: AuthContext): Promise<WorkflowTask> {
  const response = await fetch(resolveApi(`/ai/research/tasks/${encodeURIComponent(taskId)}`), {
    credentials: 'include',
    method: 'GET',
    headers: buildAuthHeaders(auth),
  });
  const payload = await parseJsonSafely<WorkflowTask & { msg?: string }>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, payload?.msg ?? '研究任务查询失败');
  }
  return payload;
}

/** 拉取深度研究任务报告（任务不存在 404；任务未完成时 report 为空）。 */
export async function getResearchReport(
  taskId: string,
  auth?: AuthContext,
): Promise<{ taskId: string; report?: string }> {
  const response = await fetch(
    resolveApi(`/ai/research/tasks/${encodeURIComponent(taskId)}/report`),
    {
      credentials: 'include',
      method: 'GET',
      headers: buildAuthHeaders(auth),
    },
  );
  const payload = await parseJsonSafely<{ taskId: string; report?: string; msg?: string }>(
    response,
  );
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, payload?.msg ?? '研究报告获取失败');
  }
  return payload;
}
