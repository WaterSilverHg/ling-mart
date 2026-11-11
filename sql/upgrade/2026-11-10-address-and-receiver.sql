-- =============================================================
-- 轻营 · 存量库增量迁移（v0.39）
--
-- 内容：收货地址簿 + 订单收货信息快照
--   1) 新建 user_address 表（地址簿）
--   2) order 表新增 5 列：address_id / receiver_name / receiver_phone /
--      receiver_region / receiver_detail
--
-- 背景与注意：
--   · 在此之前订单**完全没有收货信息**，卖家发货时系统给不出收件人；
--     本脚本补齐这条链路。
--   · 订单上的收货信息是**下单时的快照**，不是外键：买家事后修改/删除地址簿，
--     已生成的订单不受影响（这也是不直接 join user_address 的原因）。
--   · 存量订单的 5 个新列全部为 NULL，前端对这类订单回退为不展示收货信息，
--     不影响历史数据的展示与导出。
--   · user_address 刻意不加 (user_id, is_default) 唯一键：is_default 大量为 0，
--     联合唯一会让第二条普通地址插不进去；默认地址的唯一性由应用层事务保证。
--
-- 执行（Windows 下用 Git Bash，注意 -i 与重定向）：
--   docker exec -i lingmart-mysql mysql -uroot -proot < sql/upgrade/2026-11-10-address-and-receiver.sql
--
-- 幂等性：**不幂等**。重复执行会因「Duplicate column name」报错并中断（与
--   sql/upgrade/ 下其他脚本一致：先报错好过静默跳过）。建表语句本身带 IF NOT EXISTS。
--   若不确定是否已执行，先跑下面的「执行前自检」。
-- =============================================================

-- ---------- 执行前自检：若以下查询有结果，说明已经迁移过，不要再执行 ----------
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = 'lingmart_order' AND TABLE_NAME = 'order'
--    AND COLUMN_NAME IN ('address_id', 'receiver_name', 'receiver_phone', 'receiver_region', 'receiver_detail');

USE `lingmart_order`;

CREATE TABLE IF NOT EXISTS `user_address`
(
    `id`             BIGINT       NOT NULL COMMENT '主键',
    `user_id`        BIGINT       NOT NULL COMMENT '归属用户ID',
    `receiver_name`  VARCHAR(50)  NOT NULL COMMENT '收货人姓名',
    `receiver_phone` VARCHAR(20)  NOT NULL COMMENT '收货人手机号',
    `province`       VARCHAR(50)  NOT NULL COMMENT '省',
    `city`           VARCHAR(50)  NOT NULL COMMENT '市',
    `district`       VARCHAR(50)  NOT NULL COMMENT '区/县',
    `detail_address` VARCHAR(255) NOT NULL COMMENT '详细地址（街道门牌）',
    `is_default`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认地址：1是 0否',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '收货地址簿';

ALTER TABLE `order`
    ADD COLUMN `address_id` BIGINT DEFAULT NULL
        COMMENT '下单时选用的地址簿ID（仅留痕）' AFTER `tracking_no`,
    ADD COLUMN `receiver_name` VARCHAR(50) DEFAULT NULL
        COMMENT '收货人姓名（下单快照）' AFTER `address_id`,
    ADD COLUMN `receiver_phone` VARCHAR(20) DEFAULT NULL
        COMMENT '收货人手机号（下单快照）' AFTER `receiver_name`,
    ADD COLUMN `receiver_region` VARCHAR(120) DEFAULT NULL
        COMMENT '省市区（下单快照）' AFTER `receiver_phone`,
    ADD COLUMN `receiver_detail` VARCHAR(255) DEFAULT NULL
        COMMENT '详细地址（下单快照）' AFTER `receiver_region`;

-- ---------- 执行后验证 ----------
-- SHOW COLUMNS FROM lingmart_order.`order` LIKE 'receiver_name';
-- SHOW TABLES FROM lingmart_order LIKE 'user_address';
