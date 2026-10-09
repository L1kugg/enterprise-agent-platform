#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

echo "[enterprise-agent-platform] building..."
mvn -B -ntp -DskipTests clean package

echo "[enterprise-agent-platform] starting..."
java -jar target/enterprise-agent-platform-1.0-SNAPSHOT.jar
