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
