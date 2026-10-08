#!/usr/bin/env bash
ENV_FILE=/opt/knowledgeops-agent/deploy/.env.production
DBPASS=$(sudo grep -E '^DB_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2)
sudo docker exec knowledgeops-agent-mysql mysql -uroot -p"$DBPASS" knowledgeops_agent -e "DELETE FROM memory_item WHERE user_id='selftest02';" 2>&1 | grep -v "Using a password" || true
echo "memories cleared"