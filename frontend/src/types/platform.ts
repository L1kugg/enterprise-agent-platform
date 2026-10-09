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
