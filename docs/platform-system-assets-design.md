# 平台系统级共享资产设计（模型服务 / 数据库连接）

> 状态：已实施（2026-10-11，V26 迁移 + 前后端落地，见 CHANGELOG 当日条目）
> 日期：2026-10-11
> 关联：`docs/platform-remaining-requirements.md` R7（工具结构化配置）/ R12（安全与数据治理）
> 背景：当前八类平台资产全部按租户隔离，而模型凭据、数据库连接属系统级基础设施——
> 管理员 Key 建的模型服务落在 public 租户，账号租户（u-xxx）看不到，被迫跨租户复制维护。

---

## 1. 问题

- **凭据类资产被租户隔离切割**：`platform_asset` 的所有查询（列表/详情/更新/运行时解析）
  都按 `tenant_id` 硬过滤（见 `PlatformAssetMapper` 四个查询），模型服务在每个租户都要配一份。
- **认证租户与登录身份强绑定**：`ApiKeyOrJwtAuthFilter` 从凭证记录推导租户，请求头不可覆盖
  （设计使然，防越租户），无法"用管理员身份替别的租户建资产"。
- 与业内分层实践不一致：Dify / Coze / FastGPT 均将模型凭据作为**系统资源**由平台管理员
  统一配置、（可选）共享给全部工作区，工作区可自带凭据覆盖。

## 2. 目标 / 非目标

**目标**

1. 模型服务、数据库连接两类资产支持"系统共享"：一份配置全体租户可用。
2. 租户自带配置优先于系统共享（BYOK 语义，双通道）。
3. 不削弱现有租户隔离：业务资产（智能体/工作流/知识库等六类）隔离不变。
4. 敏感字段脱敏、写权限边界、用量计费语义全部有明确定义。

**非目标**

- 不引入"平台超级管理员"账号体系（沿用 ROLE_ADMIN，多租户 SaaS 化时收紧，挂 R12）。
- 不改认证过滤器的租户推导逻辑。
- 不做系统资源的配额分配（用量已按调用方租户计入 `tenant_usage_daily`，配额治理挂 R12）。

## 3. 核心概念

### 3.1 scope 字段

`platform_asset` 新增列：

```sql
scope VARCHAR(16) NOT NULL DEFAULT 'TENANT'   -- TENANT | SYSTEM
```

| scope | 含义 | tenant_id | 可见性 | 可写 |
|---|---|---|---|---|
| `TENANT`（默认） | 租户私有资产，现状语义不变 | 本租户 | 本租户 | 本租户 ADMIN |
| `SYSTEM` | 系统共享资源 | 固定 `public`（平台主租户） | **全部租户**（运行时解析 + ADMIN 列表） | 任意 ADMIN（见 §7 安全边界） |

**类型白名单**：仅 `model-services`、`databases` 允许 SYSTEM（`PlatformAssetService`
校验，其余六类创建时带 scope=SYSTEM 直接 400）。这两类是"凭据型基础设施"；
智能体/工作流/知识库等业务资产维持租户隔离。

**SYSTEM 行落位 `public` 租户**：平台主租户即系统资源的家，唯一键
`(tenant_id, asset_type, name)` 天然防止系统资源重名；不引入 `__system__` 哨兵值。

### 3.2 解析优先级（双通道）

运行时按资产 ID 解析（绑定关系存 ID，天然无同名歧义）：

```
findByIdAndTypeVisible(id, tenantId, type):
    WHERE id = ? AND asset_type = ? AND (tenant_id = ? OR scope = 'SYSTEM')
```

租户私有与系统共享同 ID 不可能冲突（ID 唯一）；若未来出现"按名称解析"场景，
规则为**租户优先、系统兜底**（本期不涉及按名解析）。

## 4. 数据迁移（V26）

```sql
-- V26__platform_asset_system_scope.sql
ALTER TABLE platform_asset
  ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'TENANT'
  AFTER asset_type,
  ADD INDEX idx_platform_asset_scope (asset_type, scope);

-- 存量标记：public 租户的两类凭据资产视为系统资源（本期只有 model-services 一行）
UPDATE platform_asset SET scope = 'SYSTEM'
WHERE tenant_id = 'public' AND asset_type IN ('model-services', 'databases');
```

说明：

- 各租户已有的重复行（如 `u-agentadmin` 的 Qwen Plus）**迁移不动**，保留为租户私有；
  上线后由管理员在界面自行删除（系统共享生效后不再需要）。
- `MysqlContainerSmokeTest` 的 Flyway 版本断言 25 → 26，并补一例：
  建租户资产 + 系统资产，断言跨租户可见性查询正确。

## 5. 后端改动

### 5.1 Mapper（`PlatformAssetMapper`）

| 方法 | 改动 |
|---|---|
| `findForList` / `countForList` | WHERE 改为 `(tenant_id = #{tenantId} OR scope = 'SYSTEM' AND tenant_id = 'public')`；`model-services`/`databases` 两类走新 SQL，其余类型保持原句（避免六类业务资产查询计划变化）——实现为同方法内按类型分支，或新增 `findForListUnion` 由 Service 选路 |
| `findByIdAndType` | 新增伴生方法 `findByIdAndTypeVisible`（§3.2）；原方法保留给"严格本租户"语义（如 getOwned 写前校验） |
| `findPublishedByType` | 不变（agent 目录只涉 agents 类型，不在白名单） |

### 5.2 Service（`PlatformAssetService`）

- `create/update`：请求体新增可选 `scope` 字段（默认 TENANT）；
  - scope=SYSTEM 时：类型必须在白名单，`tenant_id` 强制写 `public`（忽略调用方租户）；
  - 更新 SYSTEM 行：ownership 判定从 `getOwned(tenant)` 放宽为
    `getOwned(tenant) OR (scope='SYSTEM' AND isAdmin)`——写接口本就 `hasRole('ADMIN')`，此处防的是"ADMIN 改别的租户私有行"，SYSTEM 行放开。
  - `preserveSecrets` 密钥保留逻辑对 SYSTEM 行同样生效（掩码 ****** 不落库）。
- `delete`：SYSTEM 行删除走同样的 ownership 放宽；删除前若被智能体绑定，
  行为与现状一致（运行时绑定校验报"模型服务不存在"，不做引用拦截，与现状对齐）。
- `testModel` / `testDatabase`：探测目标解析改用 `findByIdAndTypeVisible`
  （管理员测系统资源时资产在 public，当前租户是 u-xxx，必须走联合查询）。
- 响应透出 `scope` 字段（`PlatformAsset` 域对象加列，MyBatis-Plus 自动映射）。

### 5.3 运行时解析（四处换用 `findByIdAndTypeVisible`）

| 调用点 | 现状 | 改动 |
|---|---|---|
| `PlatformChatModelRegistry.resolve` | `findByIdAndType(id, tenantId, 'model-services')` | 换联合查询，缓存键不变（按资产 ID） |
| `AgentRuntimeConfigService.loadForChat`（绑定模型校验） | 同上 | 同上 |
| `PlatformSqlTestService.execute`（SQL 工具运行时） | `findByIdAndType(id, currentTenant, 'databases')` | 同上 |
| `PlatformDatabaseValidator` / `testDatabase` | 同上 | 同上 |

智能体绑定了系统模型服务后，任何租户的用户与该智能体会话即用系统凭据调用，
**用量与预算仍记到会话所属租户**（`tenantCostService` 记账在调用点，按
`tenantId` 参数，不随资产 scope 变化）。

### 5.4 API 变化

```
GET  /platform/{assetType}          响应新增 scope 字段；model-services/databases
                                    两类自动含系统共享行（带 scope=SYSTEM 标记）
POST /platform/{assetType}          请求体新增可选 scope（TENANT|SYSTEM，默认 TENANT）
PUT  /platform/{assetType}/{id}     同上（不可改 scope：系统↔租户转换 = 新建，
                                    避免绑定语义漂移；如需迁移管理员手动重建）
```

权限不变：读列表/详情 ADMIN、写 ADMIN（V25 后现状）。普通用户不枚举模型服务，
只通过智能体绑定间接触达系统资源（目录接口 `GET /ai/agents` 只透出展示字段，不受影响）。

## 6. 前端改动

- `ModelServiceStudio.vue` / `DatabaseStudio.vue`：
  - 表单新增「**共享给全部租户（系统资源）**」开关（默认关；保存时带 scope）；
  - 列表行加 `系统` 徽标（scope=SYSTEM），与现有 UNTESTED/AVAILABLE 状态标签并列。
- `AgentStudio.vue`：绑定模型下拉数据来自 `/platform/model-services`，自动看到
  系统共享行，无需改动（可选优化：下拉项加"系统"后缀）。
- `types/platform.ts`：`PlatformAsset` 加 `scope?: 'TENANT' | 'SYSTEM'`。

## 7. 安全边界

| 面 | 结论 |
|---|---|
| 凭据泄露 | SYSTEM 行读走既有脱敏（`isSecret` 掩码 `******`），全租户可见的是掩码后配置；明文仅存在 DB 与调用链内 |
| 越权写 | 写/删/测试接口 `@PreAuthorize("hasRole('ADMIN')")` 不变；ownership 校验防跨租户改私有行，SYSTEM 行对 ADMIN 开放 |
| 越权读 | 普通用户无法枚举平台资产（列表 ADMIN-only）；系统资源对普通用户只在运行时按绑定生效 |
| 租户隔离回归 | 六类业务资产查询路径零变化（mapper 分支隔离），回归用例锁定 |
| 平台管理员定义 | **已知放宽点**：当前任意租户的 ADMIN 可管理系统资源。现实部署只有一个 ADMIN（agentadmin）+ bootstrap Key，风险可控；多租户 SaaS 化时收紧为"public 租户 ADMIN 或 superuser 标记"（挂 R12，本设计预留：ownership 判定集中在 Service 一处，收紧只改一个方法） |

## 8. 测试计划

| 层 | 用例 |
|---|---|
| 单测 `PlatformAssetServiceTest` | scope 类型白名单拒绝；scope=SYSTEM 落 public 租户；SYSTEM 行可被非 public 租户 ADMIN 更新/删除；六类业务资产查询不含系统行；更新 SYSTEM 行 preserveSecrets 生效 |
| 单测 `PlatformChatModelRegistry` | 跨租户解析系统模型资产成功并构建客户端；缓存 evict 对 SYSTEM 行生效 |
| 集成 `MysqlContainerSmokeTest` | Flyway 版本断言 26；建 TENANT+SYSTEM 两行后，联合可见性 SQL 在真实 MySQL 下断言 |
| 回归 | 全量 `mvn test`；评测门禁不涉及（无 prompt/检索变化） |
| 手工 | 界面建系统模型 → 另一账号绑定智能体 → 会话走系统凭据（看 `agent_invocation_log.model_name`）→ 用量记到会话租户 |

## 9. 实施拆分（预计 0.5–1 天）

1. V26 迁移 + 域对象/Mapper（联合查询）
2. Service（scope 校验/ownership/测试探测解析）+ Controller（scope 透传）
3. 运行时四处解析切换 + 单测/集成测试
4. 前端（两个 Studio 开关与徽标、类型定义）
5. 文档同步（PROJECT_DOC §9、code-map、CHANGELOG）+ 发布

## 10. 备选方案（记录不采用原因）

- **`tenant_id='__system__'` 哨兵行**：查询可保持单一 WHERE，但引入魔法值、
  与"public=平台主租户"的现有语义冲突，放弃。
- **系统资源独立新表（system_model_provider）**：表更干净，但需要平行的一套
  CRUD/探测/注册表/前端页面，成本远超加一列；且绑定表（`agent_model_binding.resource_id`）
  的 ID 语义会分叉到两张表，放弃。
- **认证层放开租户头覆盖**：等于拆掉租户隔离的防越权闸，安全不可接受，放弃。
