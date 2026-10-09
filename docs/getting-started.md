# 快速开始

本指南介绍如何在本地通过 Docker Compose 运行 Enterprise Agent Platform，并核验最重要的运行时入口。

## 前置条件

- 如果打算在 Docker 之外运行后端，需要 JDK 17+。
- Docker 与 Docker Compose。
- 一个 OpenAI 兼容的模型端点和 API Key。

## 启动完整技术栈

```bash
git clone https://github.com/L1kugg/enterprise-agent-platform.git
cd enterprise-agent-platform
./scripts/demo.sh
```

演示脚本会在 `.env.demo` 缺失时创建该文件（已加入 gitignore），启动 Docker Compose 技术栈，等待 API/Web 健康检查通过，并运行冒烟测试。

```bash
make demo
```

如果你更习惯使用 Make 目标，可以执行 `make demo`。

## 核验启动状态

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/prometheus
```

本地可访问的入口：

| 入口 | URL |
|---|---|
| 前端控制台 | `http://localhost:8088` |
| 后端 API | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui/index.html` |
| RabbitMQ 控制台 | `http://localhost:15672` |

## 身份认证

本地开发预置数据中包含一个演示管理员 API Key。请使用 `.env.example` 或前端认证卡片中的预置值。

```bash
curl -X POST http://localhost:8080/auth/token \
  -H "X-API-Key: <local-demo-api-key>" \
  -H "X-Tenant-Id: default"
```

在访问受保护路由时，将返回的 JWT 用作 `Authorization: Bearer <token>`。在本地评估流程中，也可以直接通过 `X-API-Key` 发送 API Key。

## 试一次聊天请求

```bash
curl "http://localhost:8080/ai/chat?prompt=hello&chatId=demo-chat" \
  -H "X-API-Key: <local-demo-api-key>" \
  -H "X-Tenant-Id: default"
```

## 关闭服务

```bash
./scripts/demo.sh down
```

只有在确实要删除本地卷时才使用 `docker compose down -v`。

## 演示脚本命令

```bash
./scripts/demo.sh verify
./scripts/demo.sh logs
./scripts/demo.sh down
```

如果想在启动前主动清空演示卷，可使用 `DEMO_RESET=1 ./scripts/demo.sh`。

## 后续步骤

- 使用 `demo-data/heat-safety-policy.pdf` 完整执行一遍[可复现演示脚本](demo-script.md)。
- 参考 [API 使用示例](api-recipes.md)查看常见端点示例。
- 在修改数据流或安全边界之前，先阅读[企业级架构说明](architecture-enterprise.md)。
- 在执行演练、压测或启用可观测性组件之前，先阅读[运维手册](operations.md)。
