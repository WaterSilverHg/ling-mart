-- =============================================================
-- 轻营 · 店铺服务数据库初始化脚本
-- 数据库：lingmart_shop（店铺服务独立库）
-- 说明：商家入驻申请 + 店铺信息管理 + 审核
-- =============================================================

CREATE DATABASE IF NOT EXISTS `lingmart_shop`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `lingmart_shop`;

-- -------------------------------------------------------------
-- 店铺表（入驻申请通过后启用）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `shop`;
CREATE TABLE `shop`
(
    `id`            BIGINT       NOT NULL COMMENT '主键',
    `user_id`       BIGINT       NOT NULL COMMENT '店主用户ID',
    `shop_name`     VARCHAR(50)  NOT NULL COMMENT '店铺名称（唯一）',
    `shop_logo`     VARCHAR(255) DEFAULT NULL COMMENT '店铺logo地址',
    `description`   VARCHAR(255) DEFAULT NULL COMMENT '店铺描述',
    -- 自营实体店以「到店自提」为默认履约方式；是否提供快递由店主自行决定
    `support_express` TINYINT    NOT NULL DEFAULT 0 COMMENT '是否支持快递发货：1支持 0仅到店自提',
    `address`       VARCHAR(255) DEFAULT NULL COMMENT '门店地址（买家自提点）',
    `contact_phone` VARCHAR(20)  DEFAULT NULL COMMENT '门店联系电话（自提/售后联系用）',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '营业状态：0未启用 1营业中 2已禁用',
    `review_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '审核状态：0待审核 1已通过 2已拒绝 3待重审(描述变更)',
    `draft_content` TEXT         COMMENT '待审核的新店铺信息草稿(JSON)，宽松重审：旧信息先营业，通过后才生效',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_shop_name` (`shop_name`),
    -- 一个用户只能有一个店铺：全项目 7 处 selectOne(user_id) 依赖这一唯一性。
    -- 此前仅普通索引，双击「提交申请」可落下两行 → 之后 selectOne 抛 TooManyResultsException，
    -- 该用户永久无法开店/发商品（且报 500 而非可读错误）。应用层已捕 DuplicateKeyException 兜底。
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '店铺表';

-- -------------------------------------------------------------
-- 入驻申请表（审核留痕）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `shop_application`;
CREATE TABLE `shop_application`
(
    `id`           BIGINT       NOT NULL COMMENT '主键',
    `user_id`      BIGINT       NOT NULL COMMENT '申请人用户ID',
    `shop_name`    VARCHAR(50)  NOT NULL COMMENT '申请店铺名称',
    `shop_logo`    VARCHAR(255) DEFAULT NULL COMMENT '店铺logo地址',
    `description`  VARCHAR(255) DEFAULT NULL COMMENT '店铺描述',
    `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0待审核 1已通过 2已拒绝',
    `reject_reason` VARCHAR(255) DEFAULT NULL COMMENT '拒绝原因',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '入驻申请表';

-- -------------------------------------------------------------
-- 审核审计日志（谁在何时审了谁、结果与原因）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log`
(
    `id`          BIGINT      NOT NULL COMMENT '主键',
    `operator_id` BIGINT      NOT NULL COMMENT '操作人用户ID（管理员）',
    `action`      VARCHAR(32) NOT NULL COMMENT '动作：APPLY_REVIEW 入驻审核 / SHOP_REVIEW 店铺信息审核',
    `target_id`   BIGINT      NOT NULL COMMENT '被审对象ID（申请ID / 店铺ID）',
    `result`      TINYINT     NOT NULL COMMENT '结果：1通过 0拒绝',
    `reason`      VARCHAR(255) DEFAULT NULL COMMENT '拒绝原因（通过可为空）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审计时间',
    PRIMARY KEY (`id`),
    KEY `idx_action` (`action`),
    KEY `idx_target` (`target_id`),
    -- 管理端审计日志页只按 create_time 倒序翻页，无此索引会全表排序
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '审核审计日志';

-- -------------------------------------------------------------
-- Seata AT 模式 undo_log 表（全局事务回滚用，勿删；开店申请跨服务事务）
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
