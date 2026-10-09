#!/usr/bin/env bash
set -euo pipefail
SERVER="${DEPLOY_SERVER:-ubuntu@82.157.60.115}"
ssh "$SERVER" '
  set -e
  curl -s http://localhost:8080/actuator/health
  echo
  curl -s -o /dev/null -w "web=%{http_code}\n" http://localhost:8088/
  printf "release=%s\n" "$(readlink -f /opt/enterprise-agent-platform/releases/current)"
  echo "app-mounts:"
  sudo docker inspect enterprise-agent-platform --format "{{range .Mounts}}  {{.Source}} -> {{.Destination}}{{println}}{{end}}"
  echo "recent-flyway:"
  sudo docker logs enterprise-agent-platform 2>&1 | grep -E "V20|Successfully applied" | tail -10
'
