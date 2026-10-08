# 产物部署

日常发布使用 `scripts/deploy-artifacts.sh`，不再依赖本机 Docker Desktop，也不传输 Docker 镜像。

## 发布流程

1. 本机构建后端 `jar` 与前端 `dist`。
2. 打包并上传到服务器 `/opt/knowledgeops-agent/releases/<timestamp>`。
3. 通过 `deploy/docker-compose.artifacts.yml` 把产物挂载进现有容器：
   - 后端：`releases/current/app/app.jar -> /app/app.jar`
   - 前端：`releases/current/web -> /usr/share/nginx/html`
4. 只重建 `app` 与 `web`，MySQL、Redis、RabbitMQ、pgvector 等基础服务不动。
5. 发布后自动做健康检查；失败时切回上一个 release。

```bash
bash scripts/deploy-artifacts.sh
```

默认跳过测试以缩短发布时间。发布前如需完整验证：

```bash
mvn test
cd frontend && npm run build && cd ..
RUN_TESTS=1 bash scripts/deploy-artifacts.sh
```

## 回滚

不传参数时回滚到上一个 release：

```bash
bash scripts/rollback-artifacts.sh
```

也可以指定 release ID：

```bash
bash scripts/rollback-artifacts.sh 20261008111502
```

服务器默认保留最近 5 个 release，并额外保留首次切换产物部署时抽取的 `image-base` 回滚基线。

## 适用边界

这条链路适合业务代码、前端页面和 Flyway 迁移的日常发布。以下情况仍需要更新基础镜像：

- 修改 `Dockerfile.ci` 或 `frontend/Dockerfile`
- 修改 JVM、Nginx、系统依赖或容器入口
- 新服务器首次初始化

这些场景继续使用 `scripts/deploy-images.sh`，或将基础镜像推入国内可达的镜像仓库后由服务器拉取。
