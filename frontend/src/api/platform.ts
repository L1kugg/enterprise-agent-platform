import type { AuthContext } from '../types/react';
import type {
  PlatformAsset,
  PlatformAssetPage,
  PlatformAssetType,
  PlatformAssetUpsert,
} from '../types/platform';

const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api';

function resolveApi(path: string): string {
  return path.startsWith('http://') || path.startsWith('https://') ? path : `${API_BASE}${path}`;
}

function buildAuthHeaders(auth?: AuthContext): HeadersInit {
  const headers: Record<string, string> = {};
  if (auth?.token) headers.Authorization = `Bearer ${auth.token}`;
  else if (auth?.apiKey) headers['X-API-Key'] = auth.apiKey;
  if (auth?.tenantId) headers['X-Tenant-ID'] = auth.tenantId;
  return headers;
}

function withQuery(path: string, params?: Record<string, string | number | undefined>): string {
  if (!params) return path;
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== '') query.set(key, String(value));
  });
  const encoded = query.toString();
  return encoded ? `${path}?${encoded}` : path;
}

async function parseJsonSafely<T>(response: Response): Promise<T | null> {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}

async function requestPlatform<T>(
  path: string,
  auth: AuthContext | undefined,
  init?: RequestInit & { action?: string },
): Promise<T> {
  const action = init?.action ?? '操作';
  const response = await fetch(resolveApi(path), {
    credentials: 'include',
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...buildAuthHeaders(auth),
      ...(init?.headers ?? {}),
    },
  });
  const payload = await parseJsonSafely<T & { error?: string; message?: string; msg?: string }>(
    response,
  );
  if (!response.ok) {
    throw new Error(payload?.message || payload?.msg || payload?.error || `${action}失败（${response.status}）`);
  }
  return payload as T;
}

/** 平台资源分页查询（第 5 章所有配置资源的统一列表）。 */
export async function listPlatformAssets(
  assetType: PlatformAssetType,
  auth: AuthContext | undefined,
  page = 1,
  pageSize = 20,
  search?: string,
): Promise<PlatformAssetPage> {
  return requestPlatform<PlatformAssetPage>(
    withQuery(`/platform/${assetType}`, { page, pageSize, search }),
    auth,
    { method: 'GET', action: '资源列表加载' },
  );
}

export function createPlatformAsset(
  assetType: PlatformAssetType,
  body: PlatformAssetUpsert,
  auth: AuthContext | undefined,
): Promise<PlatformAsset> {
  return requestPlatform<PlatformAsset>(`/platform/${assetType}`, auth, {
    method: 'POST',
    body: JSON.stringify(body),
    action: '资源创建',
  });
}

export function updatePlatformAsset(
  assetType: PlatformAssetType,
  id: number,
  body: PlatformAssetUpsert,
  auth: AuthContext | undefined,
): Promise<PlatformAsset> {
  return requestPlatform<PlatformAsset>(`/platform/${assetType}/${id}`, auth, {
    method: 'PUT',
    body: JSON.stringify(body),
    action: '资源保存',
  });
}

export async function deletePlatformAsset(
  assetType: PlatformAssetType,
  id: number,
  auth: AuthContext | undefined,
): Promise<void> {
  await requestPlatform<unknown>(`/platform/${assetType}/${id}`, auth, {
    method: 'DELETE',
    action: '资源删除',
  });
}

export function copyPlatformAgent(id: number, targetName: string, auth: AuthContext | undefined) {
  return requestPlatform<PlatformAsset>(`/platform/agents/${id}/copy`, auth, {
    method: 'POST',
    body: JSON.stringify({ targetName }),
    action: '智能体复制',
  });
}

export function publishPlatformAgent(
  id: number,
  channels: string[],
  auth: AuthContext | undefined,
) {
  return requestPlatform<PlatformAsset>(`/platform/agents/${id}/publish`, auth, {
    method: 'POST',
    body: JSON.stringify({ channels }),
    action: '智能体发布',
  });
}

export function testPlatformModel(id: number, auth: AuthContext | undefined) {
  return requestPlatform<PlatformAsset>(`/platform/model-services/${id}/test`, auth, {
    method: 'POST',
    action: '模型连接测试',
  });
}
