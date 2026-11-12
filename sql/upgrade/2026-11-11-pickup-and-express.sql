-- =============================================================
-- 轻营 · 存量库增量迁移（v0.40）
--
-- 内容：自提优先的履约模型
--   1) shop 表新增 3 列：support_express / address / contact_phone
--   2) order 表新增 3 列：pickup_shop_name / pickup_address / pickup_phone
--   3) order.delivery_mode 语义升级（列本身不变，仅注释变更，见下方说明）
--
-- 背景：
--   项目面向「自营实体店」，绝大多数订单是**到店自提**，只有少量快递。
--   此前 delivery_mode 是卖家发货时才填的（1 快递 / 2 无需物流），买家下单时无法选择；
--   改为：**下单时由买家选择** 1 快递 / 2 到店自提，且快递是否可选由店铺的
--   support_express 决定。自提订单不再需要收货地址，改为快照门店信息。
--
-- 注意：
--   · 存量订单的 delivery_mode 可能是 NULL（升级前尚未发货），也可能已被卖家写成 1/2，
--     其语义按旧规则理解；业务代码对 delivery_mode 为 NULL 的订单保留旧行为（发货时再定）。
--   · support_express 默认 0（仅自提）：升级后所有存量店铺默认「不支持快递」，
--     需要快递的店铺请让卖家在「我的店铺」里打开开关并填写门店地址。
--   · 存量店铺的 address / contact_phone 为 NULL，卖家补填前，
--     买家对这些店铺**无法下单自提**（服务端会明确提示「店铺尚未配置自提地址」）。
--
-- 执行（Windows 下用 Git Bash，注意 -i 与重定向）：
--   docker exec -i lingmart-mysql mysql -uroot -proot < sql/upgrade/2026-11-11-pickup-and-express.sql
--
-- 幂等性：**不幂等**。重复执行会因「Duplicate column name」报错并中断（与 sql/upgrade/ 下其它脚本一致）。
-- =============================================================

-- ---------- 执行前自检：若以下查询有结果，说明已经迁移过，不要再执行 ----------
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = 'lingmart_shop' AND TABLE_NAME = 'shop' AND COLUMN_NAME = 'support_express';
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = 'lingmart_order' AND TABLE_NAME = 'order' AND COLUMN_NAME = 'pickup_address';

ALTER TABLE `lingmart_shop`.`shop`
    ADD COLUMN `support_express` TINYINT NOT NULL DEFAULT 0
        COMMENT '是否支持快递发货：1支持 0仅到店自提' AFTER `description`,
    ADD COLUMN `address` VARCHAR(255) DEFAULT NULL
        COMMENT '门店地址（买家自提点）' AFTER `support_express`,
    ADD COLUMN `contact_phone` VARCHAR(20) DEFAULT NULL
        COMMENT '门店联系电话（自提/售后联系用）' AFTER `address`;

ALTER TABLE `lingmart_order`.`order`
    ADD COLUMN `pickup_shop_name` VARCHAR(50) DEFAULT NULL
        COMMENT '自提门店名称（下单快照）' AFTER `receiver_detail`,
    ADD COLUMN `pickup_address` VARCHAR(255) DEFAULT NULL
        COMMENT '自提门店地址（下单快照）' AFTER `pickup_shop_name`,
    ADD COLUMN `pickup_phone` VARCHAR(20) DEFAULT NULL
        COMMENT '自提门店电话（下单快照）' AFTER `pickup_address`;

-- order.delivery_mode 的列定义不变，仅语义从「卖家发货方式」变为「买家下单时选择的履约方式」：
--   1 快递 / 2 到店自提 / NULL = 本脚本执行前创建的存量订单
ALTER TABLE `lingmart_order`.`order`
    MODIFY COLUMN `delivery_mode` TINYINT DEFAULT NULL
        COMMENT '履约方式：1快递 2到店自提（下单时确定；NULL=升级前的存量订单）';

-- ---------- 执行后验证 ----------
-- SHOW COLUMNS FROM lingmart_shop.`shop` LIKE 'support_express';
-- SHOW COLUMNS FROM lingmart_order.`order` LIKE 'pickup_address';
