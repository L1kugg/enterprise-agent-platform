# 快速上手

本指南介绍如何用 Docker Compose 在本地跑起 KnowledgeOps Agent，并验证最重要的几个运行时入口。

## 前置条件

- JDK 17+（如需在 Docker 之外运行后端）。
- Docker 与 Docker Compose。
- 一个 OpenAI 兼容的模型端点和 API Key。

## 启动完整容器栈

```bash
git clone https://github.com/however-yir/knowledgeops-agent.git
cd knowledgeops-agent
./scripts/demo.sh
```

demo 脚本会在 `.env.demo` 缺失时创建这个已加入 gitignore 的文件，随后启动 Docker Compose 容器栈、等待 API 与前端健康检查通过，并执行一次 smoke test。

```bash
make demo
```

如果你偏好 Make 目标，可改用 `make demo`。

## 验证启动

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

## 鉴权

本地开发种子数据内置了一个演示管理员 API Key。取值见 `.env.example` 中的种子值，或前端「鉴权」卡片。

```bash
curl -X POST http://localhost:8080/auth/token \
  -H "X-API-Key: <local-demo-api-key>" \
  -H "X-Tenant-Id: default"
```

访问受保护路由时，将返回的 JWT 用作 `Authorization: Bearer <token>`。本地评估流程中也可以直接通过 `X-API-Key` 传递 API Key。

## 发起一次对话请求

```bash
curl "http://localhost:8080/ai/chat?prompt=hello&chatId=demo-chat" \
  -H "X-API-Key: <local-demo-api-key>" \
  -H "X-Tenant-Id: default"
```

## 关闭服务

```bash
./scripts/demo.sh down
```

仅在确实要删除本地卷时才使用 `docker compose down -v`。

## Demo 脚本命令

```bash
./scripts/demo.sh verify
./scripts/demo.sh logs
./scripts/demo.sh down
```

仅在确实要在启动前删除演示卷时才使用 `DEMO_RESET=1 ./scripts/demo.sh`。

## 下一步

- 使用 `demo-data/heat-safety-policy.pdf` 完整运行[可复现演示脚本](demo-script.md)。
- 常用接口示例参见 [API 示例](api-recipes.md)。
- 修改数据流或安全边界前，先阅读[企业架构说明](architecture-enterprise.md)。
- 运行演练、压测或可观测组件前，先阅读[运维手册](operations.md)。
