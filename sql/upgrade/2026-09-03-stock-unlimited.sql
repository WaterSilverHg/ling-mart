-- =============================================================
-- 轻营 · 存量库升级脚本：SKU 数量支持「不限量」
-- 日期：2026-09-03
-- 说明：请在 已存在的数据库 上执行一次（幂等性请见下方注意）。
--      全新环境无需执行本脚本（sql/*.sql 基线已同步更新）。
--
-- 语义变更：
--   product.sku.stock：NULL = 不限量；0..N = 限量可售（0 = 售罄）
--   ware_sku.unlimited：1 = 不限量，库存锁定/扣减/解锁/回补全部跳过
--
-- 注意：
--   1) 本脚本非幂等（ALTER 重复执行会报错），只执行一次；
--   2) 存量 sku.stock 均为非 NULL（旧默认 0）。按新语义 0 = 售罄，
--      原「数量为 0 且本意想卖」的商品请在后台把数量清空（=不限量）；
--   3) 执行后需重启 product / ware / order 服务（代码与 schema 需同步升级）。
-- =============================================================

USE `lingmart_product`;

ALTER TABLE `sku`
    MODIFY COLUMN `stock` INT DEFAULT NULL
    COMMENT '库存：NULL 表示不限量；0..N 为限量可售（0=售罄）';

USE `lingmart_ware`;

ALTER TABLE `ware_sku`
    ADD COLUMN `unlimited` TINYINT NOT NULL DEFAULT 0
    COMMENT '不限量标记：1=不限量（库存锁定/扣减/解锁/回补全部跳过）' AFTER `locked_stock`;
