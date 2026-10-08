#!/usr/bin/env bash
# 镜像导出部署：本地构建 → docker save 打包 → SCP 上传 → 服务器 docker load → 重启
# 适合国内环境（Docker Hub 拉取慢），服务器零构建，部署总耗时 ~2 分钟
set -euo pipefail

DOCKER_HUB_USER="konliy207279916"
SERVER="ubuntu@82.157.60.115"
APP_DIR="/opt/knowledgeops-agent/deploy"
PKG="/tmp/knowledgeops-images.tar.gz"

cd "$(dirname "$0")/.."

echo "==> [1/5] 构建后端镜像"
docker build -f Dockerfile.ci -t "$DOCKER_HUB_USER/knowledgeops-agent:latest" . 2>&1 | tail -3

echo "==> [2/5] 构建前端镜像"
docker build -f frontend/Dockerfile -t "$DOCKER_HUB_USER/knowledgeops-agent-web:latest" . 2>&1 | tail -3

echo "==> [3/5] 打包镜像文件"
docker save "$DOCKER_HUB_USER/knowledgeops-agent:latest" "$DOCKER_HUB_USER/knowledgeops-agent-web:latest" | gzip > "$PKG"
SIZE=$(du -h "$PKG" | cut -f1)
echo "    包大小: $SIZE"

echo "==> [4/5] 上传到服务器"
scp -q "$PKG" "$SERVER:/tmp/knowledgeops-images.tar.gz"

echo "==> [5/5] 服务器加载并部署"
ssh "$SERVER" "
  set -e
  sudo docker load < /tmp/knowledgeops-images.tar.gz
  sudo docker tag $DOCKER_HUB_USER/knowledgeops-agent:latest knowledgeops-agent:prod
  sudo docker tag $DOCKER_HUB_USER/knowledgeops-agent-web:latest knowledgeops-agent-web:prod
  rm /tmp/knowledgeops-images.tar.gz
  cd $APP_DIR
  sudo docker compose -f docker-compose.prod.yml --env-file .env.production up -d app web
"

rm -f "$PKG"

# 健康检查
sleep 15
HEALTH=$(ssh "$SERVER" "curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health" || echo "000")
if [ "$HEALTH" = "200" ]; then
  echo "✅ 部署完成：http://82.157.60.115:8088"
else
  echo "⚠️  健康检查未通过（HTTP $HEALTH），应用可能还在启动"
  echo "   查看日志：ssh $SERVER 'sudo docker logs -f knowledgeops-agent'"
fi
