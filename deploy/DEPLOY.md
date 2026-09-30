# KnowledgeOps Agent 服务器部署指南

> 面向场景：一台 Linux 服务器（2C4G 起步，推荐 4C8G），使用 Docker Compose 一键部署完整栈。
> 交付物：`deploy/docker-compose.prod.yml` + `deploy/.env.production.example`（本指南配套生成）。

---

## 一、服务器要求

| 项目 | 最低 | 推荐 |
|------|------|------|
| CPU / 内存 | 2C4G | 4C8G |
| 磁盘 | 20G | 50G+（MySQL/向量库/日志） |
| 系统 | Linux（Ubuntu 22.04 / Debian 12 / CentOS Stream） | 同左 |
| 软件 | Docker 24+ 与 Docker Compose v2、git | |

安装 Docker（Ubuntu 示例）：

```bash
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker
# 验证 compose v2
docker compose version
```

国内服务器建议配置镜像加速器（阿里云容器镜像服务控制台可获取专属加速地址）。

---

## 二、上传项目到服务器

方式 A：git 拉取（推荐）

```bash
git clone https://github.com/however-yir/knowledgeops-agent.git
cd knowledgeops-agent
```

方式 B：本地打包上传（当前是本地修改版）

```bash
# 本地（Windows PowerShell，在项目根目录）
tar -czf knowledgeops-agent.tar.gz --exclude=node_modules --exclude=target --exclude=logs --exclude=.git .
# 上传到服务器后解压
tar -xzf knowledgeops-agent.tar.gz -C /opt/knowledgeops-agent
```

---

## 三、配置环境变量（关键步骤）

```bash
cd /opt/knowledgeops-agent/deploy
cp .env.production.example .env.production
chmod 600 .env.production   # 防止泄露
vi .env.production
```

必填项（全部用 `openssl rand -hex 32` 生成强随机值）：

| 变量 | 说明 |
|------|------|
| `OPENAI_API_KEY` | 阿里云 DashScope API Key（百度「百炼控制台」开通） |
| `DB_PASSWORD` | MySQL root 密码 |
| `APP_JWT_SECRET` | JWT 签名密钥（≥32 字节） |
| `APP_BOOTSTRAP_API_KEY` | **管理员引导密钥**：仓库内置的演示 key 已被 Flyway V15 吊销，必须自设（自定义字符串即可，如 `sk-admin-` + 随机串），首次启动自动注入为 ADMIN 凭据 |
| `APP_PGVECTOR_USERNAME/PASSWORD` | pgvector 库账号密码 |
| `RABBITMQ_PASSWORD` | RabbitMQ 密码 |
| `APP_CORS_ALLOWED_ORIGINS` | 前端访问地址，如 `http://服务器IP:8088` 或 `https://your-domain.com` |

> 安全要点：`.env.production` 已进 `.gitignore` 语义，切勿提交；生产务必保持 `APP_SECURITY_ENABLED=true`（compose 中已固定）。

---

## 四、启动

```bash
cd /opt/knowledgeops-agent/deploy
docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build
```

首次构建约 5-15 分钟（Maven 拉依赖 + 前端 npm build）。国内可提前配置 Maven 阿里镜像加速。

启动过程说明：

1. MySQL / Redis / RabbitMQ / pgvector 先启动并通过健康检查
2. app 容器启动时 Flyway 自动建表（V1→V15+ 迁移）
3. `BootstrapApiKeyInitializer` 将你的 `APP_BOOTSTRAP_API_KEY` 注入为管理员凭据（日志可见 `bootstrap api key 'bootstrap-admin' provisioned`）
4. web 前端等 app 健康后启动

查看进度：

```bash
docker compose -f docker-compose.prod.yml logs -f app
```

看到 `(♥◠‿◠)♥ application started` 或 health 返回 UP 即成功。

---

## 五、验证

```bash
# 1. 健康检查
curl http://localhost:8080/actuator/health

# 2. 用 bootstrap 管理员 key 换取 JWT（验证鉴权链路）
curl -s -X POST http://localhost:8080/auth/token \
  -H "X-API-Key: 你设置的APP_BOOTSTRAP_API_KEY值"

# 3. 浏览器访问前端控制台
# http://服务器IP:8088

# 4. Swagger UI
# http://服务器IP:8080/swagger-ui/index.html
```

在控制台「鉴权」卡片填入管理员 API Key 即可登录使用。

---

## 六、常用运维命令

```bash
cd /opt/knowledgeops-agent/deploy

# 查看全部服务状态
docker compose -f docker-compose.prod.yml ps

# 重启应用（不动数据）
docker compose -f docker-compose.prod.yml restart app

# 更新代码后重新构建发布（日常推荐用下面的 jar 快速模式）
git pull   # 或重新上传
docker compose -f docker-compose.prod.yml up -d --build app

# 查看日志
docker compose -f docker-compose.prod.yml logs -f --tail=200 app

# 停止（数据保留在命名卷）
docker compose -f docker-compose.prod.yml down

# 停止并清空数据（危险！删库）
docker compose -f docker-compose.prod.yml down -v
```

---

## 七、域名 + HTTPS（可选）

前端已内置 `/api/ → app:8080` 反代，只需在宿主机再加一层 Nginx + 证书：

```nginx
server {
    listen 80;
    server_name your-domain.com;

    # 申请证书后改 443 + ssl 配置，或先用 certbot --nginx 自动化
    location / {
        proxy_pass http://127.0.0.1:8088;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
    # 后端直连域名时也代理 8080
    location /api/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_http_version 1.1;
        proxy_buffering off;          # SSE 流式必需
        proxy_read_timeout 3600s;
    }
}
```

```bash
# Let's Encrypt 免费证书
sudo apt install certbot python3-certbot-nginx -y
sudo certbot --nginx -d your-domain.com
```

配好后把 `.env.production` 的 `APP_CORS_ALLOWED_ORIGINS` 改为 `https://your-domain.com` 并重启 app。

---

## 八、可观测性（可选）

项目自带完整观察栈，需要时单独拉起：

```bash
cd /opt/knowledgeops-agent
docker compose -f docker-compose.observability.yml up -d
# Grafana: http://服务器IP:3000（默认 admin/admin，登录后请改密）
```

包含 Prometheus（指标）、Loki（日志）、Tempo（链路）、Alertmanager（告警，预置 5 条规则：P95 延迟 / 入库失败率 / 连接池 / JVM 内存 / 磁盘）。Grafana 面板可导入 `observability/grafana/dashboard.json`。

---

## 九、防火墙端口清单

| 端口 | 服务 | 是否对外开放 |
|------|------|-------------|
| 8088 | 前端控制台 | 是（或走 Nginx 80/443） |
| 8080 | 后端 API | 是（或仅走 Nginx 反代） |
| 3306 / 6379 / 5672 / 5432 / 15672 | MySQL / Redis / RabbitMQ / pgvector / MQ 管理台 | **否**（生产 compose 已绑定 127.0.0.1） |

云服务器在安全组放行 8088 和 8080（或 80/443）即可。

---

## 十、常见问题

**Q: 启动报 `APP_JWT_SECRET is required`**
A: 没找到 `.env.production` 或未填写该值。确认用 `--env-file .env.production` 启动。

**Q: app 反复重启，日志显示连不上 MySQL**
A: 等待 MySQL 首次初始化完成（约 30-60 秒）；若持续失败检查 `DB_PASSWORD` 是否与 MySQL 容器一致。

**Q: 模型调用 401**
A: `OPENAI_API_KEY` 无效。到阿里云百炼控制台确认 Key 状态与余额。

**Q: 上传 PDF 后检索不到**
A: 入库是异步的，先查任务状态 `GET /ingestion/jobs?chatId=xxx`；确认 pgvector 容器健康、`APP_REQUIRE_PGVECTOR=true`。

**Q: 前端能打开但接口 403/CORS 报错**
A: `APP_CORS_ALLOWED_ORIGINS` 与浏览器实际访问地址不一致（协议/域名/端口必须完全匹配）。

**Q: 想横向扩容 app 多实例**
A: 单机 compose 不支持多 app 实例（内存限流与本地文件存储是单实例设计）。多实例需按 `docs/deployment-enterprise.md` 拆分并上分布式限流。

---

## 附：jar 快速更新模式（日常发布推荐）

Dockerfile 已改为"jar 预构建"模式：镜像内不再跑 Maven，只拷贝现成 jar，服务器更新构建从 2-5 分钟降到几秒。前端镜像仍按源码构建，但源码不变时走缓存、秒级完成。

**一键发布（推荐）**：在项目根目录执行 `bash scripts/publish.sh`（Windows PowerShell 用 `scripts\publish.cmd`）——自动完成打包、收集 jar + 前端源码 + 部署配置、上传、解压、重建 app/web 容器，走 SSH 密钥认证全程免密，不用逐个 scp 文件。

手动更新流程（只改了后端 Java 代码时）：

```bash
# 1. 本地打包（或 make package）
mvn -DskipTests package

# 2. 上传 jar（约 80MB；已配置 make deploy-jar 一键打包上传）
scp target/knowledgeops-agent-1.0-SNAPSHOT.jar ubuntu@服务器IP:/opt/knowledgeops-agent/target/

# 3. 服务器重建 app 镜像并替换（秒级，不动数据库和其他容器）
cd /opt/knowledgeops-agent/deploy
docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build app
```

注意：

- jar 文件必须放在服务器的 `/opt/knowledgeops-agent/target/` 下（构建上下文按此路径 COPY），首次使用先 `mkdir -p /opt/knowledgeops-agent/target`
- 改了 `Dockerfile`、`pom.xml` 依赖、前端代码时，把对应源码同步上传后同样执行第 3 步
- Dockerfile 已切换为 jar 模式，若回退"源码构建"（服务器上直接编译），用 git 恢复历史版 Dockerfile 即可

---

## 附：生产上线前检查清单（来自项目文档）

- [x] `APP_SECURITY_ENABLED=true`
- [x] JWT / DB / MQ / 管理员密钥全部使用强随机值
- [x] 中间件端口仅绑定 127.0.0.1
- [ ] 验证 API Key 签发/吊销流程（`POST /auth/api-keys/rotate`）
- [ ] 验证入库重试 + DLQ
- [ ] 执行 `python3 scripts/run_regression.py` 回归
- [ ] MySQL + pgvector 备份策略（`docker run --rm -v mysql-data:/data alpine tar czf - /data > backup.tgz`）
