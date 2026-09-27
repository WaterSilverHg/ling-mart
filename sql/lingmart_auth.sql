-- =============================================================
-- 轻营 · 认证中心数据库初始化脚本
-- 数据库：lingmart_auth（认证中心独立库）
-- 说明：管理员账号无需在此插入，auth 服务启动时自动预置
--       （见 AdminUserInitializer，默认手机号 13800000000 / 密码 admin123）。
-- =============================================================

CREATE DATABASE IF NOT EXISTS `lingmart_auth`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `lingmart_auth`;

-- -------------------------------------------------------------
-- 用户表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`
(
    `id`          BIGINT       NOT NULL COMMENT '主键',
    `phone`       VARCHAR(20)  NOT NULL COMMENT '手机号（唯一）',
    `password`    VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密后的密码',
    `nickname`    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `avatar`      VARCHAR(255) DEFAULT NULL COMMENT '头像地址',
    `role`        VARCHAR(20)  NOT NULL DEFAULT 'ROLE_USER' COMMENT '角色：ROLE_USER/ROLE_SELLER/ROLE_ADMIN',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 正常，0 禁用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户表';
