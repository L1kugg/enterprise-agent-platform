#!/usr/bin/env bash
# 一次性回填：pgvector 按 job_id 统计切片数 → MySQL ingestion_job.chunk_count
set -e
ENV_FILE=/opt/enterprise-agent-platform/deploy/.env.production
PGUSER=$(sudo grep -E '^APP_PGVECTOR_USERNAME=' "$ENV_FILE" | head -1 | cut -d= -f2)
PGPASS=$(sudo grep -E '^APP_PGVECTOR_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2)
DBPASS=$(sudo grep -E '^DB_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2)

# 从 pgvector 导出 "job_id,count" 行（只统计成功任务对应的 job）
PGDB=$(sudo docker exec enterprise-agent-platform-pgvector psql -U "$PGUSER" -d postgres -At -c "SELECT datname FROM pg_database WHERE NOT datistemplate AND datname != 'postgres'" | head -1)
echo "pg database: $PGDB"
sudo docker exec enterprise-agent-platform-pgvector psql -U "$PGUSER" -d "$PGDB" -At \
  -c "SELECT metadata->>'job_id', COUNT(*) FROM ai_knowledge_chunks WHERE metadata->>'job_id' IS NOT NULL GROUP BY 1" \
> /tmp/chunk_counts.tsv

# 组装 MySQL 批量 UPDATE（CASE WHEN 单语句，避免逐行往返）
{
  echo "UPDATE ingestion_job SET chunk_count = CASE job_id"
  while IFS='|' read -r job count; do
    echo "WHEN '$job' THEN $count"
  done < /tmp/chunk_counts.tsv
  echo "ELSE chunk_count END WHERE chunk_count IS NULL AND status='SUCCEEDED';"
} > /tmp/backfill.sql

sudo docker exec -i enterprise-agent-platform-mysql mysql -uroot -p"$DBPASS" enterprise_agent_platform < /tmp/backfill.sql 2>&1 | grep -v "Using a password" || true
echo "backfill applied: $(wc -l < /tmp/chunk_counts.tsv) jobs"
rm -f /tmp/chunk_counts.tsv /tmp/backfill.sql
