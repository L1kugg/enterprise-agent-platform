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
  EvalRun,
  EvalRunRequest,
  FeedbackRequest,
  IngestionJob,
  IngestionSubmitResponse,
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
  return new Error(`HTTP ${status}: ${message || 'request failed'}`);
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
    throw formatHttpError(response.status, payload?.msg ?? 'token exchange failed');
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
    throw formatHttpError(response.status, payload?.msg ?? 'refresh token failed');
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
    throw formatHttpError(response.status, payload?.msg ?? 'auth failed');
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
    throw formatHttpError(response.status, payload?.msg ?? 'react chat failed');
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
    throw formatHttpError(response.status, errPayload?.msg ?? 'stream init failed');
  }
  if (!response.body) {
    throw new Error('SSE stream body is empty');
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
    throw formatHttpError(response.status, 'list sessions failed');
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
    throw formatHttpError(response.status, 'save session failed');
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
    throw formatHttpError(response.status, 'set session pin failed');
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
    throw formatHttpError(response.status, 'set session archive failed');
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
    throw formatHttpError(response.status, 'compare branches failed');
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
    throw formatHttpError(response.status, 'merge branches failed');
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
    throw formatHttpError(response.status, payload?.msg ?? 'submit feedback failed');
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
    throw formatHttpError(response.status, 'cost summary failed');
  }
  return payload;
}

/** 本租户近 N 天逐日用量趋势（含今日，后端返回裸数组，缺天补零）。 */
export async function getTenantCostTrend(days: number, auth?: AuthContext): Promise<TenantCostTrendPoint[]> {
  const response = await fetch(resolveApi(withQuery('/cost/trend', { days })), {
    credentials: 'include',
    method: 'GET',
    headers: buildAuthHeaders(auth),
  });
  const payload = await parseJsonSafely<TenantCostTrendPoint[]>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, 'cost trend failed');
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
    throw formatHttpError(response.status, 'update budget failed');
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
    throw formatHttpError(response.status, 'create evaluation dataset failed');
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
    throw formatHttpError(response.status, 'list evaluation datasets failed');
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
    throw formatHttpError(response.status, 'trigger evaluation run failed');
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
    throw formatHttpError(response.status, 'load evaluation comparison failed');
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
    throw formatHttpError(response.status, 'mark evaluation baseline failed');
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
    throw formatHttpError(response.status, text || 'export evaluation report failed');
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
    throw formatHttpError(response.status, payload?.msg ?? 'upload document failed');
  }
  return payload;
}

export async function listRecentIngestionJobs(auth?: AuthContext, limit = 20): Promise<IngestionJob[]> {
  const response = await fetch(resolveApi(withQuery('/ingestion/jobs/recent', { limit })), {
    credentials: 'include',
    method: 'GET',
    headers: buildAuthHeaders(auth),
  });
  const payload = await parseJsonSafely<IngestionJob[]>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, 'list ingestion jobs failed');
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
    throw formatHttpError(response.status, payload?.msg ?? 'delete document failed');
  }
  return payload.msg;
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
    throw formatHttpError(response.status, 'list admin documents failed');
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
    throw formatHttpError(response.status, payload?.msg ?? 'delete document failed');
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
    throw formatHttpError(response.status, 'list workflow tasks failed');
  }
  return payload;
}

/** 发起深度研究：后端同步执行（约 1-3 分钟），完成即返回报告；signal 用于「停止」中断等待。 */
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
  // 成功时返回裸 DeepResearchResult（无 ok/msg 包装），错误响应体里才有 msg
  const payload = await parseJsonSafely<DeepResearchResult & { msg?: string }>(response);
  if (!response.ok || !payload) {
    throw formatHttpError(response.status, payload?.msg ?? 'create research task failed');
  }
  return payload;
}
