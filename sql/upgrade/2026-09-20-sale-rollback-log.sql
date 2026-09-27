-- =============================================================
-- 轻营 · 新增销量回退幂等表（退款回退销量去重）
-- 日期：2026-09-20
-- 用途：为已初始化的 lingmart_product 存量库补建 sale_rollback_log 表。
--       退款审核通过回退销量时先插本表（order_sn 唯一），防止
--       「product 已回退 → 响应超时 → 订单全局事务回滚 → 卖家重试」导致的二次扣减。
-- 全新库已含在 sql/lingmart_product.sql，无需执行本脚本。
-- 用法：docker exec -i lingmart-mysql mysql -uroot -proot < 本文件
-- =============================================================
USE `lingmart_product`;

CREATE TABLE IF NOT EXISTS `sale_rollback_log`
(
    `id`          BIGINT      NOT NULL COMMENT '主键',
    `order_sn`    VARCHAR(64) NOT NULL COMMENT '订单号（唯一：同一订单只回退一次销量）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '销量回退幂等表';
