# 贡献指南

感谢你有兴趣改进 Enterprise Agent Platform！

## 快速上手

### 前置条件

- JDK 17+
- Maven 3.9+
- Node.js 18+（用于前端）
- Docker 与 Docker Compose（用于基础设施服务）

### 后端环境

```bash
# Run tests
mvn -B -ntp test

# Full verification (includes static analysis, coverage, dependency check)
mvn -B -ntp verify

# Quick compile
mvn -B -ntp compile
```

### 前端环境

```bash
cd frontend
npm install
npm run build        # production build
npm run lint         # ESLint check
```

### 全栈（Docker）

```bash
./scripts/demo.sh
```

## 分支与提交

- 从 `main` 切出分支
- 保持提交小而聚焦
- 使用约定式提交风格：
  - `feat: ...` — 新功能
  - `fix: ...` — 缺陷修复
  - `docs: ...` — 仅文档变更
  - `refactor: ...` — 既非修复缺陷也非新增功能的代码调整
  - `chore: ...` — 工具、CI、依赖更新
  - `test: ...` — 新增或更新测试

## Pull Request 规范

- 每个 PR 只聚焦一个变更集
- 行为变更时新增或更新测试
- API/配置/用法变更时更新文档
- 请求评审前确保 `mvn -B -ntp verify` 通过
- 前端变更请运行 `cd frontend && npm run build && npm run lint`
- 不要提交生成物/运行时产物（`target/`、`node_modules/`、日志、本地环境文件）

## 代码风格

- **Java**：遵循现有约定；Checkstyle、PMD 和 SpotBugs 在 CI 中强制执行
- **TypeScript/Vue**：使用 `frontend/` 中的 ESLint + Prettier 配置；提交前运行 `npm run lint`
- 避免在功能/修复 PR 中夹带无关重构
- 优先选择清晰的命名与小方法，而非取巧的捷径

## 报告问题

- 可复现的问题请使用[缺陷报告模板](https://github.com/L1kugg/enterprise-agent-platform/issues/new?template=bug_report.yml)
- 功能建议请使用[功能请求模板](https://github.com/L1kugg/enterprise-agent-platform/issues/new?template=feature_request.yml)
- 安全漏洞请参阅 [SECURITY.md](SECURITY.md)——**不要**创建公开 issue

## 行为准则

本项目遵循 [Contributor Covenant 行为准则](CODE_OF_CONDUCT.md)。
参与本项目即表示你同意遵守该准则。
