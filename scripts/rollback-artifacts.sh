#!/usr/bin/env bash
# 回滚产物部署。可传 release ID（例如 20261008111502）；不传时选择上一个 release。
set -euo pipefail

SERVER="${DEPLOY_SERVER:-ubuntu@82.157.60.115}"
APP_DIR="${DEPLOY_APP_DIR:-/opt/enterprise-agent-platform/deploy}"
RELEASE_ROOT="${DEPLOY_RELEASE_ROOT:-/opt/enterprise-agent-platform/releases}"
REQUESTED_RELEASE="${1:-}"

ssh "$SERVER" "REQUESTED_RELEASE='$REQUESTED_RELEASE' RELEASE_ROOT='$RELEASE_ROOT' APP_DIR='$APP_DIR' bash -s" <<'REMOTE'
set -euo pipefail
CURRENT_LINK="$RELEASE_ROOT/current"
CURRENT_RELEASE="$(readlink -f "$CURRENT_LINK")"
CURRENT_NAME="${CURRENT_RELEASE##*/}"

if [[ -z "$REQUESTED_RELEASE" ]]; then
  TARGET_RELEASE="$(find "$RELEASE_ROOT" -mindepth 1 -maxdepth 1 -type d \
    \( -name '20*' -o -name 'image-base' \) -printf '%f\n' \
    | sort -r | grep -v -F "$CURRENT_NAME" | head -1 || true)"
  if [[ -n "$TARGET_RELEASE" ]]; then
    TARGET_RELEASE="$RELEASE_ROOT/$TARGET_RELEASE"
  fi
else
  if [[ "$REQUESTED_RELEASE" = /* ]]; then
    TARGET_RELEASE="$REQUESTED_RELEASE"
  else
    TARGET_RELEASE="$RELEASE_ROOT/$REQUESTED_RELEASE"
  fi
fi

if [[ -z "$TARGET_RELEASE" || ! -f "$TARGET_RELEASE/app/app.jar" || ! -d "$TARGET_RELEASE/web" ]]; then
  echo "No rollback release found. Available releases:" >&2
  find "$RELEASE_ROOT" -mindepth 1 -maxdepth 1 -type d \
    \( -name '20*' -o -name 'image-base' \) -printf '%f\n' | sort -r
  exit 1
fi
if [[ "$(readlink -f "$TARGET_RELEASE")" == "$CURRENT_RELEASE" ]]; then
  echo "Target release is already current: $CURRENT_RELEASE" >&2
  exit 1
fi

echo "Rolling back: $CURRENT_RELEASE -> $TARGET_RELEASE"
sudo ln -sfn "$TARGET_RELEASE" "$CURRENT_LINK"
cd "$APP_DIR"
if ! sudo docker compose -f docker-compose.prod.yml -f docker-compose.artifacts.yml \
    --env-file .env.production up -d --no-deps --force-recreate app web; then
  echo "Recreate failed; restoring $CURRENT_RELEASE" >&2
  sudo ln -sfn "$CURRENT_RELEASE" "$CURRENT_LINK"
  sudo docker compose -f docker-compose.prod.yml -f docker-compose.artifacts.yml \
    --env-file .env.production up -d --no-deps --force-recreate app web
  exit 1
fi

for i in $(seq 1 40); do
  APP_HEALTH="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health || true)"
  WEB_HEALTH="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8088/ || true)"
  if [[ "$APP_HEALTH" == "200" && "$WEB_HEALTH" == "200" ]]; then
    echo "Rollback complete: $(readlink -f "$CURRENT_LINK")"
    exit 0
  fi
  sleep 3
done

echo "Rollback health check failed; restoring $CURRENT_RELEASE" >&2
sudo ln -sfn "$CURRENT_RELEASE" "$CURRENT_LINK"
sudo docker compose -f docker-compose.prod.yml -f docker-compose.artifacts.yml \
  --env-file .env.production up -d --no-deps --force-recreate app web
exit 1
REMOTE
