-- ============================================================
-- 电商售后客服演示场景：业务表建表 + 种子数据
-- 目标库：enterprise_agent_platform（与应用同库，biz_ 前缀隔离）
-- 幂等：可重复执行（先删后建）
-- 注意：所有业务表必须带 tenant_id 列——query_database 的租户占位符
-- 机制（模型写 tenant_id='__TENANT__'，服务端替换为真实租户）依赖它。
-- ============================================================

-- 业务订单表
DROP TABLE IF EXISTS biz_orders;
CREATE TABLE biz_orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(32) NOT NULL,
  customer_name VARCHAR(64) NOT NULL,
  product_name VARCHAR(128) NOT NULL,
  quantity INT NOT NULL DEFAULT 1,
  amount DECIMAL(10, 2) NOT NULL,
  status VARCHAR(16) NOT NULL COMMENT '待支付/待发货/已发货/已签收/退款中/已完成/已关闭',
  shipping_status VARCHAR(16) NOT NULL COMMENT '未发货/运输中/已送达/已退回',
  logistics_no VARCHAR(32) NULL,
  logistics_company VARCHAR(32) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'public',
  UNIQUE KEY uk_biz_orders_no (order_no),
  INDEX idx_biz_orders_tenant (tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 商品表
DROP TABLE IF EXISTS biz_products;
CREATE TABLE biz_products (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_name VARCHAR(128) NOT NULL,
  category VARCHAR(32) NOT NULL,
  price DECIMAL(10, 2) NOT NULL,
  stock INT NOT NULL DEFAULT 0,
  warehouse VARCHAR(64) NOT NULL,
  warranty_months INT NOT NULL DEFAULT 12 COMMENT '保修月数',
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'public',
  UNIQUE KEY uk_biz_products_name (tenant_id, product_name),
  INDEX idx_biz_products_tenant (tenant_id, category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 退款/售后单表
DROP TABLE IF EXISTS biz_refunds;
CREATE TABLE biz_refunds (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  refund_no VARCHAR(32) NOT NULL,
  order_no VARCHAR(32) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  refund_amount DECIMAL(10, 2) NOT NULL,
  status VARCHAR(16) NOT NULL COMMENT '待审核/已同意/已退款/已拒绝',
  applied_at DATETIME NOT NULL,
  finished_at DATETIME NULL,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'public',
  UNIQUE KEY uk_biz_refunds_no (refund_no),
  INDEX idx_biz_refunds_order (order_no),
  INDEX idx_biz_refunds_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================================================
-- 种子数据：public 主租户（管理员 API Key 所在租户，演示主力）
-- + u-demo2 演示租户（租户隔离对照，注册用户后即为 u-<用户名>）
-- ============================================================

INSERT INTO biz_orders
  (order_no, customer_name, product_name, quantity, amount, status, shipping_status, logistics_no, logistics_company, created_at, updated_at, tenant_id)
VALUES
  ('SO2026100101', '王晓明', '星语无线降噪耳机 Pro', 1, 899.00, '已签收', '已送达', 'SF1380000001', '顺丰速运', '2026-09-28 10:23:00', '2026-10-02 14:05:00', 'public'),
  ('SO2026100102', '李慧', '云栖智能手表 S2', 1, 1299.00, '已完成', '已送达', 'SF1380000002', '顺丰速运', '2026-09-15 09:10:00', '2026-09-20 18:30:00', 'public'),
  ('SO2026100103', '张伟', '星语无线降噪耳机 Pro', 2, 1798.00, '退款中', '已退回', 'SF1380000003', '顺丰速运', '2026-10-01 20:41:00', '2026-10-10 11:22:00', 'public'),
  ('SO2026100104', '刘芳', '净界扫地机器人 T8', 1, 2599.00, '已发货', '运输中', 'JD777000004', '京东物流', '2026-10-18 16:55:00', '2026-10-20 08:12:00', 'public'),
  ('SO2026100105', '陈晨', '云栖智能手表 S2', 1, 1299.00, '待发货', '未发货', NULL, NULL, '2026-10-22 22:03:00', '2026-10-22 22:03:00', 'public'),
  ('SO2026100106', '赵磊', '光年桌面音箱 M1', 1, 499.00, '已签收', '已送达', 'YT990000006', '圆通速递', '2026-10-05 11:47:00', '2026-10-08 15:20:00', 'public'),
  ('SO2026100107', '孙倩', '净界扫地机器人 T8', 1, 2599.00, '退款中', '已退回', 'JD777000007', '京东物流', '2026-09-30 19:28:00', '2026-10-11 10:05:00', 'public'),
  ('SO2026100108', '周涛', '星语无线降噪耳机 青春版', 3, 897.00, '已完成', '已送达', 'SF138000008', '顺丰速运', '2026-08-22 13:02:00', '2026-08-27 09:44:00', 'public'),
  ('SO2026100109', '吴静', '光年桌面音箱 M1', 2, 998.00, '已关闭', '未发货', NULL, NULL, '2026-10-12 08:31:00', '2026-10-12 09:00:00', 'public'),
  ('SO2026100110', '郑浩', '云栖智能手表 S2', 1, 1299.00, '已发货', '运输中', 'YT990000010', '圆通速递', '2026-10-21 17:40:00', '2026-10-22 07:55:00', 'public');

INSERT INTO biz_products
  (product_name, category, price, stock, warehouse, warranty_months, tenant_id)
VALUES
  ('星语无线降噪耳机 Pro', '数码音频', 899.00, 152, '华东一号仓', 12, 'public'),
  ('星语无线降噪耳机 青春版', '数码音频', 299.00, 430, '华东一号仓', 12, 'public'),
  ('云栖智能手表 S2', '智能穿戴', 1299.00, 87, '华南一号仓', 24, 'public'),
  ('净界扫地机器人 T8', '家用电器', 2599.00, 45, '华东二号仓', 24, 'public'),
  ('光年桌面音箱 M1', '数码音频', 499.00, 210, '华北一号仓', 12, 'public'),
  ('暖屋石墨烯取暖器', '家用电器', 399.00, 320, '华北一号仓', 18, 'public');

INSERT INTO biz_refunds
  (refund_no, order_no, reason, refund_amount, status, applied_at, finished_at, tenant_id)
VALUES
  ('RF2026100003', 'SO2026100103', '左耳有杂音，质量问题', 1798.00, '已同意', '2026-10-08 09:15:00', NULL, 'public'),
  ('RF2026100007', 'SO2026100107', '扫地机器人噪音过大，七天无理由退货', 2599.00, '已退款', '2026-10-06 14:30:00', '2026-10-12 16:00:00', 'public'),
  ('RF2026100009', 'SO2026100109', '误拍，申请取消订单', 998.00, '已同意', '2026-10-12 08:50:00', '2026-10-12 09:00:00', 'public');

-- 演示租户 u-demo2 的数据（其他租户查不到，用于租户隔离演示）
INSERT INTO biz_orders
  (order_no, customer_name, product_name, quantity, amount, status, shipping_status, logistics_no, logistics_company, created_at, updated_at, tenant_id)
VALUES
  ('SO2026200001', '演示客户一', '星语无线降噪耳机 Pro', 1, 899.00, '已发货', '运输中', 'SF2380000001', '顺丰速运', '2026-10-20 10:00:00', '2026-10-21 09:30:00', 'u-demo2'),
  ('SO2026200002', '演示客户二', '云栖智能手表 S2', 1, 1299.00, '退款中', '已退回', 'SF2380000002', '顺丰速运', '2026-10-02 12:20:00', '2026-10-09 17:45:00', 'u-demo2');

INSERT INTO biz_products
  (product_name, category, price, stock, warehouse, warranty_months, tenant_id)
VALUES
  ('星语无线降噪耳机 Pro', '数码音频', 919.00, 12, '西南租户仓', 12, 'u-demo2');
