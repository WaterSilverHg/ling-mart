-- =============================================================
-- 轻营 · 存量库增量迁移（v0.27）
--
-- 内容：order 表新增 4 列 + 1 个索引
--   1) refund_apply_time  退款申请时间（超时自动同意的计时锚点）
--   2) delivery_mode      发货方式：1快递 2无需物流
--   3) shipping_company   快递公司
--   4) tracking_no        运单号
--   5) idx_refund_apply   退款超时扫描的复合索引
--
-- 背景与注意：
--   · refund_time 的语义是「处理时间」（卖家通过/拒绝时写），与「申请时间」是两回事。
--     本脚本新增的 refund_apply_time 才是「卖家超时未处理则自动同意」的计时锚点。
--   · 存量「退款中」订单的 refund_apply_time 会是 NULL，因此**永远不会被自动同意**，
--     仍需卖家手工审核 —— 这是刻意的安全默认，避免升级后把历史积压的申请一次性全退掉。
--   · 本次升级之前已发货的存量订单 delivery_mode 也是 NULL，前端回退显示「已发货」。
--
-- 执行（Windows 下用 Git Bash，注意 -i 与重定向）：
--   docker exec -i lingmart-mysql mysql -uroot -proot < sql/upgrade/2026-10-03-refund-and-shipping.sql
--
-- 幂等性：本脚本**不幂等**。重复执行会因「Duplicate column name」报错并中断——
--   这是有意为之（与 sql/upgrade/ 下其他脚本一致）：先报错好过静默跳过。
--   若不确定是否已执行，先跑下面的「执行前自检」。
-- =============================================================

-- ---------- 执行前自检：若以下查询有结果，说明已经迁移过，不要再执行 ----------
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = 'lingmart_order' AND TABLE_NAME = 'order'
--    AND COLUMN_NAME IN ('refund_apply_time', 'delivery_mode', 'shipping_company', 'tracking_no');

USE `lingmart_order`;

ALTER TABLE `order`
    ADD COLUMN `refund_apply_time` DATETIME DEFAULT NULL
        COMMENT '退款申请时间（买家提交；超时自动同意的锚点）' AFTER `refund_time`,
    ADD COLUMN `delivery_mode` TINYINT DEFAULT NULL
        COMMENT '发货方式：1快递 2无需物流' AFTER `ship_time`,
    ADD COLUMN `shipping_company` VARCHAR(50) DEFAULT NULL
        COMMENT '快递公司（仅 delivery_mode=1 时有值）' AFTER `delivery_mode`,
    ADD COLUMN `tracking_no` VARCHAR(64) DEFAULT NULL
        COMMENT '运单号（仅 delivery_mode=1 时有值）' AFTER `shipping_company`,
    ADD INDEX `idx_refund_apply` (`refund_status`, `refund_apply_time`);

-- ---------- 执行后验证 ----------
-- SHOW COLUMNS FROM lingmart_order.`order` LIKE 'refund_apply_time';
-- SHOW COLUMNS FROM lingmart_order.`order` LIKE 'delivery_mode';
-- SHOW INDEX FROM lingmart_order.`order` WHERE Key_name = 'idx_refund_apply';
