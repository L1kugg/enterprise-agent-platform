#!/usr/bin/env bash
ENV_FILE=/opt/enterprise-agent-platform/deploy/.env.production
DBPASS=$(sudo grep -E '^DB_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2)
sudo docker exec enterprise-agent-platform-mysql mysql -uroot -p"$DBPASS" enterprise_agent_platform -e "DELETE FROM memory_item WHERE user_id='selftest02';" 2>&1 | grep -v "Using a password" || true
echo "memories cleared"