# 平台基础功能 API（第一批）

本批次落地白皮书第 5 章的平台配置基座，覆盖以下资源的租户隔离创建、修改、详情、删除、分页搜索：

- 智能体：`/platform/agents`
- 工作流：`/platform/workflows`
- API/SQL 工具：`/platform/tools`
- 知识库：`/platform/knowledge-bases`
- 知识库文件：`/platform/knowledge-files`（通过 `parentId` 挂接知识库）
- 安全防护：`/platform/safety-guards`
- 模型服务：`/platform/model-services`
- 第三方数据库：`/platform/databases`

## 通用接口

- `GET /platform/{assetType}?page=1&pageSize=20&search=关键字`
- `POST /platform/{assetType}`
- `GET /platform/{assetType}/{id}`
- `PUT /platform/{assetType}/{id}`
- `DELETE /platform/{assetType}/{id}`（无返回体）

写操作仅 `ROLE_ADMIN`；读操作要求已认证。所有记录按认证过滤器解析出的租户隔离，`apiKey`、`password`、`secret`、`token` 等敏感配置字段在响应中会脱敏为 `******`。

### 请求体

```json
{
  "name": "售前问答智能体",
  "description": "基于产品白皮书回答售前问题",
  "configJson": "{\"prompt\":\"...\",\"modelId\":1,\"memoryTurns\":10}"
}
```

知识库文件额外要求 `parentId`：

```json
{
  "name": "whitepaper.pdf",
  "parentId": 1,
  "configJson": "{\"chunkStrategy\":\"SMART\",\"chunkPreview\":[\"...\"]}"
}
```

## 智能体操作

- 复制：`POST /platform/agents/{id}/copy`，可选请求体 `{"targetName":"新名称"}`
- 发布：`POST /platform/agents/{id}/publish`，可选请求体 `{"channels":["platform","rest-api"]}`

## 模型连接测试

`POST /platform/model-services/{id}/test` 会解析配置中的 `apiUrl` 或 `api_url`，执行带 3 秒超时的探测，并将状态写入配置与模型记录。探测结果为 `AVAILABLE`/`UNAVAILABLE`。

## 配置约定

为兼容后续界面和执行引擎，建议配置 JSON 使用以下键：

- 智能体：`prompt`、`modelId`、`modelParams`、`openingMessage`、`openingQuestions`、`workflowIds`、`tools`、`knowledgeBaseIds`、`safetyGuardIds`、`memoryTurns`
- 工作流：`graph`（画布节点/边）、`executionConfirm`
- 工具：`toolType`（`FORM_API`/`CODE_API`/`SQL`）、`schema`、`executionConfirm`
- 知识库：`listApiUrl`、`queryApiUrl`、`recallApiUrl`
- 安全防护：`blockedTopics`、`blockMessage`
- 模型：`category`、`provider`、`apiUrl`、`apiKey`、`modelParams`
- 数据库：`databaseType`、`jdbcUrl`、`username`、`password`

## 前端入口

管理员登录后，左侧导航新增「平台」页签，路径为 `/platform`。页面内置 8 个资源 Tab，支持搜索、分页、新增、编辑、删除、配置 JSON 展开，并提供智能体复制/发布、模型连接测试、知识文件挂接知识库操作。前端状态模块为 `frontend/src/composables/usePlatformAssets.ts`，页面组件为 `frontend/src/components/platform/PlatformView.vue`。

## 2026-10-10 前端页面配套接口

### 工作流

- 复制：`POST /platform/workflows/{id}/copy`
- 上线：`POST /platform/workflows/{id}/online`
- 下线：`POST /platform/workflows/{id}/offline`
- 试运行：`POST /platform/workflows/{id}/test-run`
- 导出：`GET /platform/workflows/{id}/export`
- 导入：`POST /platform/workflows/import`

导入和上线会校验：节点 ID 唯一、边引用有效、唯一 START、至少一个 END、START 可达 END。试运行当前真实支持 `START`、`END`、`HTTP_REQUEST`、`TOOL_CALL` 与简单 `key == value` 条件分支；`LLM`、知识召回、代码执行、子流程等节点会明确返回“不支持试运行”，不会伪造成功。

### 工具与防护

- 工具测试：`POST /platform/tools/{id}/test`
- 安全策略测试：`POST /platform/safety-guards/{id}/test`
- 数据库连接测试：`POST /platform/databases/{id}/test`

工具测试说明：

- `FORM_API` 执行真实 HTTP 请求，响应最多返回 16KB 摘要。
- `SQL` 通过数据库配置执行参数化只读查询，并应用只读 SQL 守卫、超时、最大 500 行与单元格截断。
- `CODE_API` 当前显式返回“沙箱尚未接入”，不模拟执行。

### 系统管理

- 用户列表：`GET /platform/users`
- 用户更新/启停/角色调整：`PUT /platform/users/{id}`
- 角色列表：`GET /platform/roles`
- 创建/修改/删除角色：`POST /platform/roles`、`PUT /platform/roles/{id}`、`DELETE /platform/roles/{id}`

系统管理复用 `users`、`roles`、`permissions` 表。内置 `ADMIN`、`USER`、`OPS` 不允许删除或改编码；仍绑定用户的角色不允许删除。禁用用户后，JWT 认证过滤器会实时校验账号状态，已签发访问令牌不能再继续访问接口。

### 敏感字段更新语义

`apiKey`、`credential`、`password`、`secret`、`token` 等字段更新时，若请求中缺失或提交 `******`，后端保留数据库原值；响应仍统一脱敏。因此前端编辑表单可以在不回显密钥的情况下保存其他配置。
