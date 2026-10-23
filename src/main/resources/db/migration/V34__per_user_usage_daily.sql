-- 按用户用量记账（管理员查看具体用户用量）：
-- 租户 × 用户 × 日 粒度累计请求/token/费用，与 tenant_usage_daily（租户 × 日）并行写入——
-- 预算闸门与既有租户汇总完全不受影响，本表只服务"按用户"的运营统计。
-- user_id 为认证主体（JWT 用户名 / API Key 主体）；后台无身份线程（深度研究/图谱抽取）
-- 不记用户行，只记租户日表。
CREATE TABLE IF NOT EXISTS tenant_user_usage_daily (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  usage_date DATE NOT NULL,
  request_count BIGINT NOT NULL DEFAULT 0,
  input_tokens BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  total_cost_usd DECIMAL(14, 6) NOT NULL DEFAULT 0.000000,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_tenant_user_usage_daily (tenant_id, user_id, usage_date)
);

CREATE INDEX idx_tenant_user_usage_daily_range
  ON tenant_user_usage_daily (tenant_id, usage_date);
