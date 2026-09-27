-- =============================================================
-- 轻营 · 新增库存流水日志表（对账/定时补偿用）
-- 日期：2026-09-06
-- 用途：为已初始化的 lingmart_ware 存量库补建 stock_flow_log 表。
-- 全新库已含在 sql/lingmart_ware.sql，无需执行本脚本。
-- 用法：docker exec -i lingmart-mysql mysql -uroot -proot < 本文件
-- =============================================================
USE `lingmart_ware`;

CREATE TABLE IF NOT EXISTS `stock_flow_log`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `sku_id`      BIGINT      NOT NULL COMMENT '商品SKU ID（= ware_sku.sku_id）',
    `order_sn`    VARCHAR(64) NOT NULL COMMENT '关联订单号',
    `change_num`  INT         NOT NULL COMMENT '变化量（负数为扣减）',
    `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0=待处理 1=已完成 2=补偿中 3=人工介入（正常路径一次成型为 1）',
    `version`     INT         NOT NULL DEFAULT 1 COMMENT '乐观锁版本号（预留，当前以 status CAS 抢占）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_sku_id` (`sku_id`),
    KEY `idx_order_sn` (`order_sn`),
    KEY `idx_status_create` (`status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '库存流水日志表';
