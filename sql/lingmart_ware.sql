-- =============================================================
-- 轻营 · 仓储服务数据库初始化脚本
-- 数据库：lingmart_ware（仓储服务独立库）
-- 说明：库存管理（锁定/扣减/解锁）；含 Seata AT 所需 undo_log 表
-- =============================================================

CREATE DATABASE IF NOT EXISTS `lingmart_ware`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `lingmart_ware`;

-- -------------------------------------------------------------
-- 库存表（卖家发布/更新商品时由 product 服务同步进来）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `ware_sku`;
CREATE TABLE `ware_sku`
(
    `id`           BIGINT       NOT NULL COMMENT '主键',
    `spu_id`       BIGINT       NOT NULL COMMENT '商品SPU ID',
    `sku_id`       BIGINT       NOT NULL COMMENT '商品SKU ID',
    `shop_id`      BIGINT       NOT NULL COMMENT '所属店铺ID（数据隔离关键字段）',
    `title`        VARCHAR(100) NOT NULL COMMENT 'SKU名称',
    `stock`        INT          NOT NULL DEFAULT 0 COMMENT '可售库存（不限量 SKU 固定为 0，仅占位）',
    `locked_stock` INT          NOT NULL DEFAULT 0 COMMENT '锁定库存（下单未支付）',
    `unlimited`    TINYINT      NOT NULL DEFAULT 0 COMMENT '不限量标记：1=不限量（库存锁定/扣减/解锁/回补全部跳过）',
    `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sku_id` (`sku_id`),
    KEY `idx_shop_id` (`shop_id`),
    KEY `idx_spu_id` (`spu_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '库存表';

-- -------------------------------------------------------------
-- 库存锁定任务表（下单锁定→支付扣减→取消解锁/退款回补的状态机记录）
-- status：0已锁定 1已扣减 2已解锁 3已退款回补
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `ware_order_task`;
CREATE TABLE `ware_order_task`
(
    `id`          BIGINT      NOT NULL COMMENT '主键',
    `order_sn`    VARCHAR(32) NOT NULL COMMENT '订单号',
    `sku_id`      BIGINT      NOT NULL COMMENT 'SKU ID',
    `shop_id`     BIGINT      NOT NULL COMMENT '所属店铺ID',
    `num`         INT         NOT NULL COMMENT '数量',
    `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '状态：0已锁定 1已扣减 2已解锁 3已退款回补',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_sn` (`order_sn`),
    KEY `idx_sku_id` (`sku_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '库存锁定任务表';

-- -------------------------------------------------------------
-- 库存流水日志表（扣减对账/定时补偿）
-- 说明：ware 消费「订单支付成功」MQ 事件幂等扣减后，在**同一事务**写入一条
--       change_num<0 且 status=1（已完成）的流水（正常路径一次成型）；
--       仅异常/历史遗留的 status=0 流水由定时任务每 5 分钟重放扣减并推进状态机
--       （0待处理→2补偿中→1完成 / 3人工介入）。取消/退款等正向加回不走本表。
--       id 保留自增（内部对账用、不对外下发，无需雪花；枚举量无风险）。
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `stock_flow_log`;
CREATE TABLE `stock_flow_log`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `sku_id`      BIGINT      NOT NULL COMMENT '商品SKU ID（= ware_sku.sku_id）',
    `order_sn`    VARCHAR(64) NOT NULL COMMENT '关联订单号',
    `change_num`  INT         NOT NULL COMMENT '变化量（负数为扣减）',
    `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0=待处理 1=已完成 2=补偿中 3=人工介入',
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

-- -------------------------------------------------------------
-- Seata AT 模式 undo_log 表（全局事务回滚用，勿删）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `undo_log`;
CREATE TABLE `undo_log`
(
    `id`            BIGINT       NOT NULL COMMENT '主键',
    `branch_id`     BIGINT       NOT NULL COMMENT '分支事务ID',
    `xid`           VARCHAR(128) NOT NULL COMMENT '全局事务ID',
    `context`       VARCHAR(128) NOT NULL COMMENT 'undo_log上下文，如序列化方式',
    `rollback_info` LONGBLOB     NOT NULL COMMENT '回滚信息',
    `log_status`    INT          NOT NULL COMMENT '0正常 1防御状态',
    `log_created`   DATETIME(6)  NOT NULL COMMENT '创建时间',
    `log_modified`  DATETIME(6)  NOT NULL COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB  DEFAULT CHARSET = utf8mb4
  COMMENT = 'AT事务模式undo表';
