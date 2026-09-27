-- =============================================================
-- 轻营 · 新增订单支付成功 Outbox 表（事务性事件源）
-- 日期：2026-09-06
-- 用途：为已初始化的 lingmart_order 存量库补建 order_paid_event 表。
-- 全新库已含在 sql/lingmart_order.sql，无需执行本脚本。
-- 用法：docker exec -i lingmart-mysql mysql -uroot -proot < 本文件
-- =============================================================
USE `lingmart_order`;

CREATE TABLE IF NOT EXISTS `order_paid_event`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `event_id`    VARCHAR(64) NOT NULL COMMENT '事件唯一ID（UUID）',
    `order_sn`    VARCHAR(64) NOT NULL COMMENT '订单号',
    `payload`     TEXT        NOT NULL COMMENT '事件载荷（OrderPaidMessage JSON）',
    `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0=待发送 1=已发送 2=发送中（CAS 抢占后、投递完成前）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`),
    KEY `idx_order_sn` (`order_sn`),
    KEY `idx_status_create` (`status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '订单支付成功 Outbox 事件表';
