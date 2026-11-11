-- =============================================================
-- 轻营 · 订单服务数据库初始化脚本
-- 数据库：lingmart_order（订单服务独立库）
-- 说明：下单、购物车、订单状态流转；含 Seata AT 所需 undo_log 表
-- =============================================================

CREATE DATABASE IF NOT EXISTS `lingmart_order`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `lingmart_order`;

-- -------------------------------------------------------------
-- 订单主表
-- order_status：0待支付 1已支付 2已发货 3已完成 4已取消 5已退款
-- refund_status：0无退款 1退款中（买家申请待审核） 2已退款 3退款拒绝
-- delivery_mode：1快递 2无需物流（自提/面交）；0/NULL 表示尚未发货
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `order`;
CREATE TABLE `order`
(
    `id`            BIGINT         NOT NULL COMMENT '主键',
    `order_sn`      VARCHAR(32)    NOT NULL COMMENT '订单号（唯一）',
    `buyer_id`      BIGINT         NOT NULL COMMENT '买家用户ID',
    `shop_id`       BIGINT         NOT NULL COMMENT '所属店铺ID',
    `total_amount`  DECIMAL(10, 2) NOT NULL COMMENT '订单总金额',
    `pay_status`    TINYINT        NOT NULL DEFAULT 0 COMMENT '支付状态：0未支付 1已支付',
    `order_status`  TINYINT        NOT NULL DEFAULT 0 COMMENT '订单状态：0待支付 1已支付 2已发货 3已完成 4已取消 5已退款',
    `refund_status` TINYINT        NOT NULL DEFAULT 0 COMMENT '退款状态：0无退款 1退款中 2已退款 3退款拒绝',
    `refund_reason` VARCHAR(255)   DEFAULT NULL COMMENT '退款申请原因（买家填写）',
    `refund_reject_reason` VARCHAR(255) DEFAULT NULL COMMENT '退款拒绝原因（卖家填写，仅拒绝时有值）',
    `refund_time`   DATETIME       DEFAULT NULL COMMENT '退款处理时间（通过/拒绝）',
    -- 与 refund_time 区分：这里记「买家提交申请的时刻」，是超时自动同意的计时锚点。
    -- 存量订单该列为 NULL，故**不会被自动同意**（安全默认，避免升级后历史申请被批量退掉）
    `refund_apply_time` DATETIME   DEFAULT NULL COMMENT '退款申请时间（买家提交；超时自动同意的锚点）',
    `buyer_remark`  VARCHAR(255)   DEFAULT NULL COMMENT '买家备注',
    `pay_time`      DATETIME       DEFAULT NULL COMMENT '支付时间',
    `ship_time`     DATETIME       DEFAULT NULL COMMENT '发货时间',
    `delivery_mode` TINYINT        DEFAULT NULL COMMENT '发货方式：1快递 2无需物流',
    `shipping_company` VARCHAR(50) DEFAULT NULL COMMENT '快递公司（仅 delivery_mode=1 时有值）',
    `tracking_no`   VARCHAR(64)    DEFAULT NULL COMMENT '运单号（仅 delivery_mode=1 时有值）',
    -- 收货信息是**下单时快照**，不是外键引用：买家事后改地址簿或删地址，已生成的订单不受影响
    `address_id`    BIGINT         DEFAULT NULL COMMENT '下单时选用的地址簿ID（仅留痕，不参与查询）',
    `receiver_name` VARCHAR(50)    DEFAULT NULL COMMENT '收货人姓名（下单快照）',
    `receiver_phone` VARCHAR(20)   DEFAULT NULL COMMENT '收货人手机号（下单快照）',
    `receiver_region` VARCHAR(120) DEFAULT NULL COMMENT '省市区（下单快照，如 浙江省 杭州市 西湖区）',
    `receiver_detail` VARCHAR(255) DEFAULT NULL COMMENT '详细地址（下单快照）',
    `complete_time` DATETIME       DEFAULT NULL COMMENT '完成时间',
    `cancel_time`   DATETIME       DEFAULT NULL COMMENT '取消时间',
    `idempotency_key` VARCHAR(64)  DEFAULT NULL COMMENT '幂等键（客户端 Idempotency-Key 头，防重复下单）',
    `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sn` (`order_sn`),
    -- 幂等键按 (idempotency_key, buyer_id) 联合唯一：同一买家相同幂等键防重复下单，
    -- 不同买家可安全复用客户端幂等键（原单列唯一会跨买家冲突，兜底查询甚至可能返回他人订单）
    UNIQUE KEY `uk_idempotency_buyer` (`idempotency_key`, `buyer_id`),
    KEY `idx_buyer_id` (`buyer_id`),
    KEY `idx_shop_id` (`shop_id`),
    KEY `idx_order_status` (`order_status`),
    -- 超时扫描按 (order_status, create_time) 过滤，复合索引避免全表扫描
    KEY `idx_status_create` (`order_status`, `create_time`),
    -- 退款超时自动同意的扫描条件：refund_status=1 AND refund_apply_time < ?,同样用复合索引
    KEY `idx_refund_apply` (`refund_status`, `refund_apply_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '订单主表';

-- -------------------------------------------------------------
-- 订单明细表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `order_item`;
CREATE TABLE `order_item`
(
    `id`          BIGINT         NOT NULL COMMENT '主键',
    `order_id`    BIGINT         NOT NULL COMMENT '订单ID',
    `order_sn`    VARCHAR(32)    NOT NULL COMMENT '订单号',
    `spu_id`      BIGINT         NOT NULL COMMENT '商品SPU ID',
    `sku_id`      BIGINT         NOT NULL COMMENT '商品SKU ID',
    `shop_id`     BIGINT         NOT NULL COMMENT '所属店铺ID',
    `spu_title`   VARCHAR(100)   NOT NULL COMMENT '商品标题',
    `sku_title`   VARCHAR(100)   NOT NULL COMMENT 'SKU名称',
    `sku_image`   VARCHAR(255)   DEFAULT NULL COMMENT 'SKU图片',
    `price`       DECIMAL(10, 2) NOT NULL COMMENT '成交单价',
    `num`         INT            NOT NULL DEFAULT 1 COMMENT '购买数量',
    `total_price` DECIMAL(10, 2) NOT NULL COMMENT '小计金额',
    `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_sku_id` (`sku_id`),
    KEY `idx_shop_id` (`shop_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '订单明细表';

-- -------------------------------------------------------------
-- 购物车表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `cart`;
CREATE TABLE `cart`
(
    `id`          BIGINT         NOT NULL COMMENT '主键',
    `buyer_id`    BIGINT         NOT NULL COMMENT '买家用户ID',
    `spu_id`      BIGINT         NOT NULL COMMENT '商品SPU ID',
    `sku_id`      BIGINT         NOT NULL COMMENT '商品SKU ID',
    `shop_id`     BIGINT         NOT NULL COMMENT '所属店铺ID',
    `spu_title`   VARCHAR(100)   NOT NULL COMMENT '商品标题',
    `sku_title`   VARCHAR(100)   NOT NULL COMMENT 'SKU名称',
    `sku_image`   VARCHAR(255)   DEFAULT NULL COMMENT 'SKU图片',
    `price`       DECIMAL(10, 2) NOT NULL COMMENT '单价',
    `num`         INT            NOT NULL DEFAULT 1 COMMENT '数量',
    `checked`     TINYINT        NOT NULL DEFAULT 1 COMMENT '是否勾选：1勾选 0未勾选',
    `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_buyer_id` (`buyer_id`),
    KEY `idx_sku_id` (`sku_id`),
    -- 同一买家的同一 SKU 只能有一行：应用层是「先查后插」，并发/双击加购会产生两条同款明细
    -- （列表显示两条、改数量只作用于其中一条）。应用层已捕 DuplicateKeyException 转为累加。
    UNIQUE KEY `uk_buyer_sku` (`buyer_id`, `sku_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '购物车表';

-- -------------------------------------------------------------
-- 收货地址簿
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `user_address`;
CREATE TABLE `user_address`
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
    -- 这里刻意**不加** (user_id, is_default) 唯一键：is_default 大量为 0，
    -- 联合唯一会让「同一个非默认地址」互相冲突（第二条普通地址就插不进去）。
    -- MySQL 无部分索引，默认地址的唯一性由应用层在同一事务内「先清零再置一」保证。
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '收货地址簿';

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

-- -------------------------------------------------------------
-- 订单支付成功 Outbox 表（事务性事件源：支付状态与待发事件同库落盘）
-- 说明：pay() 本地事务内与订单状态一同写入 status=0 待发记录；提交后由
--       OrderPaidOutboxRelay 定时发布到 order.paid.* 交换器并置 status=1。
--       event_id 全局唯一，配合 ware 消费幂等，保证 Outbox 重发不重复扣减。
--       id 保留自增（内部对账用、不对外，见主键雪花改造说明）。
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `order_paid_event`;
CREATE TABLE `order_paid_event`
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
