-- =============================================================
-- 轻营 · v0.5/v0.6 数据库增量迁移（对「改动前已初始化」的库执行）
-- 说明：不重置数据，仅补列/补表。每次变更只执行一次（重复执行 ADD COLUMN 会报已存在）。
-- 用法：mysql -h localhost -P 3307 -u root -proot < scripts/migrate-v0506.sql
-- =============================================================

-- ---------- product：spu.min_price 冗余列 + es_write_retry 补偿表 ----------
USE `lingmart_product`;

ALTER TABLE `spu`
    ADD COLUMN `min_price` DECIMAL(10, 2) DEFAULT NULL COMMENT '最低SKU价格（冗余，避免列表/检索每次全量计算SKU最低价）' AFTER `sale_count`;

CREATE TABLE IF NOT EXISTS `es_write_retry`
(
    `id`              BIGINT       NOT NULL COMMENT '主键',
    `spu_id`          BIGINT       NOT NULL COMMENT '商品SPU ID',
    `action`          VARCHAR(16)  NOT NULL COMMENT '动作：INDEX 写入 / DELETE 删除',
    `doc_json`        TEXT         DEFAULT NULL COMMENT 'INDEX 动作的完整检索文档JSON（DELETE 动作可空）',
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0待重试 1成功 2放弃(重试超限)',
    `retry_count`     INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `last_error`      VARCHAR(500) DEFAULT NULL COMMENT '最近一次失败原因',
    `next_retry_time` DATETIME     NOT NULL COMMENT '下次重试时间（指数退避）',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_next` (`status`, `next_retry_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'ES写失败补偿表';

-- ---------- order：退款状态四列 ----------
USE `lingmart_order`;

ALTER TABLE `order`
    ADD COLUMN `refund_status` TINYINT NOT NULL DEFAULT 0 COMMENT '退款状态：0无退款 1退款中 2已退款 3退款拒绝' AFTER `order_status`,
    ADD COLUMN `refund_reason` VARCHAR(255) DEFAULT NULL COMMENT '退款申请原因（买家填写）' AFTER `refund_status`,
    ADD COLUMN `refund_reject_reason` VARCHAR(255) DEFAULT NULL COMMENT '退款拒绝原因（卖家填写，仅拒绝时有值）' AFTER `refund_reason`,
    ADD COLUMN `refund_time` DATETIME DEFAULT NULL COMMENT '退款处理时间（通过/拒绝）' AFTER `refund_reject_reason`;
