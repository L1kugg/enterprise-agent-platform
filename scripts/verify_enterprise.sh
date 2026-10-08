#!/usr/bin/env bash
set -e
E2E_USER="${E2E_USER:-}"
E2E_PASS="${E2E_PASS:-}"
if [[ -z "$E2E_USER" || -z "$E2E_PASS" ]]; then
  echo "Set E2E_USER and E2E_PASS before running this script." >&2
  exit 1
fi

LOGIN=$(curl -s -X POST -H "Content-Type: application/json" \
  -d "{\"username\":\"$E2E_USER\",\"password\":\"$E2E_PASS\"}" \
  http://localhost:8088/api/auth/login)
TOKEN=$(printf '%s' "$LOGIN" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)
RESP=$(curl -s -X POST "http://localhost:8088/api/ai/react/chat" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"chatId":"enterprise-v3","prompt":"你是谁？你能帮我做什么？","modelProfile":"economy"}')
printf '%s' "$RESP" | grep -o '"answer":"[^"]*"' | head -1 | cut -d'"' -f4 | head -c 300
echo
