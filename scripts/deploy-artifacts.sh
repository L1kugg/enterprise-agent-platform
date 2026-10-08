#!/usr/bin/env bash
# 产物部署：本地构建 jar/dist，上传到服务器并挂载进现有容器。
# 不需要本机 Docker Desktop，也不需要传输 Docker 镜像；基础镜像仍由服务器保留。
set -euo pipefail

SERVER="${DEPLOY_SERVER:-ubuntu@82.157.60.115}"
APP_DIR="${DEPLOY_APP_DIR:-/opt/knowledgeops-agent/deploy}"
RELEASE_ROOT="${DEPLOY_RELEASE_ROOT:-/opt/knowledgeops-agent/releases}"
RELEASE_ID="$(date +%Y%m%d%H%M%S)"
PACKAGE="/tmp/knowledgeops-artifacts.tar.gz"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

cleanup() {
  rm -f "$PACKAGE"
}
trap cleanup EXIT

cd "$ROOT"

echo "==> [1/6] 构建后端 jar"
if [[ "${RUN_TESTS:-0}" == "1" ]]; then
  mvn -q test package
else
  echo "    跳过测试（发布前可在本机执行 mvn test）"
  mvn -q -DskipTests package
fi

JAR="$(find target -maxdepth 1 -name 'knowledgeops-agent-*.jar' ! -name '*-sources.jar' | sort | tail -1)"
if [[ -z "$JAR" ]]; then
  echo "Backend jar not found" >&2
  exit 1
fi

echo "==> [2/6] 构建前端静态文件"
(
  cd frontend
  if [[ ! -x node_modules/.bin/vue-tsc || package-lock.json -nt node_modules/.package-lock.json ]]; then
    npm ci --no-audit --no-fund
  else
    echo "    复用已有 node_modules，跳过 npm ci"
  fi
  npm run build
)

echo "==> [3/6] 打包发布产物"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE" "$PACKAGE"' EXIT
mkdir -p "$STAGE/app" "$STAGE/web"
cp "$JAR" "$STAGE/app/app.jar"
cp -a frontend/dist/. "$STAGE/web/"
tar -czf "$PACKAGE" -C "$STAGE" app web
echo "    包大小: $(du -h "$PACKAGE" | cut -f1)"

echo "==> [4/6] 上传产物与 compose override"
scp -q "$PACKAGE" "$SERVER:/tmp/knowledgeops-artifacts.tar.gz"
scp -q deploy/docker-compose.artifacts.yml "$SERVER:/tmp/docker-compose.artifacts.yml"

echo "==> [5/6] 服务器安装 release 并重启 app/web"
ssh "$SERVER" "RELEASE_ID='$RELEASE_ID' RELEASE_ROOT='$RELEASE_ROOT' APP_DIR='$APP_DIR' bash -s" <<'REMOTE'
set -euo pipefail
PACKAGE=/tmp/knowledgeops-artifacts.tar.gz
OVERRIDE=/tmp/docker-compose.artifacts.yml
RELEASE_DIR="$RELEASE_ROOT/$RELEASE_ID"
CURRENT="$RELEASE_ROOT/current"
PREVIOUS="$(readlink -f "$CURRENT" 2>/dev/null || true)"

sudo mkdir -p "$RELEASE_ROOT"
if [[ -z "$PREVIOUS" ]]; then
  # 首次切到产物部署时，把当前容器内产物抽出来作为回滚基线。
  IMAGE_BASE="$RELEASE_ROOT/image-base"
  sudo mkdir -p "$IMAGE_BASE/app" "$IMAGE_BASE/web"
  sudo docker cp knowledgeops-agent:/app/app.jar "$IMAGE_BASE/app/app.jar"
  sudo docker cp knowledgeops-agent-web:/usr/share/nginx/html/. "$IMAGE_BASE/web/"
  sudo find "$IMAGE_BASE" -type f -exec chmod a+r {} +
  sudo find "$IMAGE_BASE" -type d -exec chmod a+rx {} +
  PREVIOUS="$IMAGE_BASE"
fi

STAGE="$(mktemp -d)"
tar -xzf "$PACKAGE" -C "$STAGE"
sudo rm -rf "$RELEASE_DIR"
sudo mkdir -p "$RELEASE_DIR"
sudo cp -a "$STAGE/app" "$STAGE/web" "$RELEASE_DIR/"
rm -rf "$STAGE"
sudo find "$RELEASE_DIR" -type f -exec chmod a+r {} +
sudo find "$RELEASE_DIR" -type d -exec chmod a+rx {} +
sudo ln -sfn "$RELEASE_DIR" "$CURRENT"
sudo cp "$OVERRIDE" "$APP_DIR/docker-compose.artifacts.yml"
rm -f "$PACKAGE" "$OVERRIDE"

cd "$APP_DIR"
sudo docker compose -f docker-compose.prod.yml -f docker-compose.artifacts.yml \
  --env-file .env.production up -d --no-deps --force-recreate app web

for i in $(seq 1 40); do
  APP_HEALTH="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health || true)"
  WEB_HEALTH="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8088/ || true)"
  if [[ "$APP_HEALTH" == "200" && "$WEB_HEALTH" == "200" ]]; then
    echo "release=$RELEASE_ID"
    echo "rollback=$PREVIOUS"
    sudo find "$RELEASE_ROOT" -mindepth 1 -maxdepth 1 -type d -name '20*' \
      | sort -r | tail -n +6 | while read -r old_release; do
        sudo rm -rf -- "$old_release"
      done
    exit 0
  fi
  sleep 3
done

echo "Health check failed; rolling back to $PREVIOUS" >&2
sudo ln -sfn "$PREVIOUS" "$CURRENT"
sudo docker compose -f docker-compose.prod.yml -f docker-compose.artifacts.yml \
  --env-file .env.production up -d --no-deps --force-recreate app web
exit 1
REMOTE

echo "==> [6/6] 健康检查"
echo "发布完成: http://82.157.60.115:8088"
