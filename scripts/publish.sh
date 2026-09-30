#!/usr/bin/env bash
# 一键发布：打包 jar → 收集前端源码与部署配置 → 上传服务器 → 解压 → 重建 app/web 容器
# 用法：项目根目录执行  bash scripts/publish.sh  （Windows PowerShell 可用 scripts\publish.cmd）
# 认证：走 SSH 密钥（~/.ssh/config 里配置了 82.157.60.115 → ubuntu + miyao.pem），全程免密。
# 服务器 /opt 归 root 所有，远端命令统一用 sudo。
set -euo pipefail

SERVER="ubuntu@82.157.60.115"
APP_DIR="/opt/knowledgeops-agent"
PKG_NAME="deploy-update.tar.gz"
PKG="/tmp/$PKG_NAME"

# 总是回到项目根目录执行（脚本放在 scripts/ 下）
cd "$(dirname "$0")/.."

echo "==> [1/4] Maven 打包（跳过测试）"
mvn -DskipTests package -q

echo "==> [2/4] 收集发布文件（jar + 前端源码 + 部署配置）"
# 注意：绝不包含 deploy/.env.production（生产密钥只留在服务器本地）
rm -f "$PKG"
tar czf "$PKG" \
  target/knowledgeops-agent-1.0-SNAPSHOT.jar \
  Dockerfile .dockerignore \
  deploy/docker-compose.prod.yml \
  frontend/Dockerfile frontend/nginx.conf frontend/index.html \
  frontend/package.json frontend/package-lock.json \
  frontend/tsconfig.json frontend/tsconfig.node.json frontend/vite.config.ts \
  frontend/src
echo "    包大小：$(ls -lh "$PKG" | awk '{print $5}')"

echo "==> [3/4] 上传到服务器（约 1-5 分钟看家宽上传速度）"
scp "$PKG" "$SERVER:/tmp/"

echo "==> [4/4] 解压并重建容器（构建约 1-3 分钟）"
ssh "$SERVER" "set -e; \
  sudo mkdir -p $APP_DIR/target; \
  sudo tar xzf /tmp/$PKG_NAME -C $APP_DIR; \
  sudo rm /tmp/$PKG_NAME; \
  cd $APP_DIR/deploy && sudo docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build app web"

echo ""
echo "✅ 发布完成：http://82.157.60.115:8088 （浏览器 Ctrl+F5 强制刷新看新页面）"
echo "   看应用日志：ssh $SERVER 'sudo docker logs -f knowledgeops-agent'"
