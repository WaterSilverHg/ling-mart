-- =============================================================
-- 轻营 · 聊天服务数据库初始化脚本
-- 数据库：lingmart_chat（聊天服务独立库）
-- 说明：买家 × 店铺老板的实时会话 + 消息持久化
-- =============================================================

CREATE DATABASE IF NOT EXISTS `lingmart_chat`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `lingmart_chat`;

-- -------------------------------------------------------------
-- 会话表（一个买家对一个店铺仅一条会话，反复进店复用）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `chat_conversation`;
CREATE TABLE `chat_conversation`
(
    `id`           BIGINT        NOT NULL COMMENT '主键',
    `buyer_id`     BIGINT        NOT NULL COMMENT '买家用户ID',
    `shop_id`      BIGINT        NOT NULL COMMENT '店铺ID',
    `shop_user_id` BIGINT        NOT NULL COMMENT '店主（老板）用户ID',
    `last_message` VARCHAR(1000) DEFAULT NULL COMMENT '最后一条消息摘要（会话列表展示）',
    `last_time`    DATETIME      DEFAULT NULL COMMENT '最后一条消息时间',
    `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_buyer_shop` (`buyer_id`, `shop_id`),
    KEY `idx_shop_user` (`shop_user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '聊天会话表';

-- -------------------------------------------------------------
-- 消息表（历史记录经 REST 分页拉取，实时推送走 WebSocket）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message`
(
    `id`              BIGINT        NOT NULL COMMENT '主键',
    `conversation_id` BIGINT        NOT NULL COMMENT '会话ID',
    `sender_id`       BIGINT        NOT NULL COMMENT '发送者用户ID',
    `receiver_id`     BIGINT        NOT NULL COMMENT '接收者用户ID',
    `content`         VARCHAR(1000) NOT NULL COMMENT '消息内容',
    `msg_type`        TINYINT       NOT NULL DEFAULT 1 COMMENT '消息类型：1文本',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation` (`conversation_id`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '聊天消息表';
