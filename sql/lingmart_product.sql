-- =============================================================
-- 轻营 · 商品服务数据库初始化脚本
-- 数据库：lingmart_product（商品服务独立库）
-- 说明：SPU/SKU 管理、商品分类/品牌、商品上下架
-- =============================================================

CREATE DATABASE IF NOT EXISTS `lingmart_product`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `lingmart_product`;

-- -------------------------------------------------------------
-- 商品分类表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `category`;
CREATE TABLE `category`
(
    `id`        BIGINT      NOT NULL COMMENT '主键',
    `name`      VARCHAR(30) NOT NULL COMMENT '分类名称',
    `parent_id` BIGINT      NOT NULL DEFAULT 0 COMMENT '父分类ID（0为顶级）',
    `sort`      INT         NOT NULL DEFAULT 0 COMMENT '排序',
    `status`    TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
    `create_time` DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '商品分类表';

-- -------------------------------------------------------------
-- 品牌表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `brand`;
CREATE TABLE `brand`
(
    `id`        BIGINT      NOT NULL COMMENT '主键',
    `name`      VARCHAR(30) NOT NULL COMMENT '品牌名称',
    `logo`      VARCHAR(255) DEFAULT NULL COMMENT '品牌logo地址',
    `status`    TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
    `create_time` DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '品牌表';

-- -------------------------------------------------------------
-- SPU 表（标准商品单元）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `spu`;
CREATE TABLE `spu`
(
    `id`              BIGINT        NOT NULL COMMENT '主键',
    `shop_id`         BIGINT        NOT NULL COMMENT '所属店铺ID（数据隔离关键字段）',
    `title`           VARCHAR(100)  NOT NULL COMMENT '商品标题',
    `subtitle`        VARCHAR(255)  DEFAULT NULL COMMENT '副标题/卖点',
    `category_id`     BIGINT        DEFAULT NULL COMMENT '分类ID',
    `brand_id`        BIGINT        DEFAULT NULL COMMENT '品牌ID',
    `main_image`      VARCHAR(255)  DEFAULT NULL COMMENT '主图地址',
    `description`     TEXT          COMMENT '商品详情',
    `status`          TINYINT       NOT NULL DEFAULT 0 COMMENT '销售状态：0下架 1上架（决定是否可搜索/购买）',
    `review_status`   TINYINT       NOT NULL DEFAULT 0 COMMENT '审核状态：0待审核 1已通过 2已拒绝 3待重审',
    `draft_content`   TEXT          COMMENT '待审核的新内容草稿(JSON)，宽松重审：旧内容先卖，通过后才生效',
    `application_id`  BIGINT        DEFAULT NULL COMMENT '关联入驻申请ID（开店申请携带的初始商品）',
    `sale_count`      INT           NOT NULL DEFAULT 0 COMMENT '销量',
    `min_price`       DECIMAL(10, 2) DEFAULT NULL COMMENT '最低SKU价格（冗余，避免列表/检索每次全量计算SKU最低价）',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_shop_id` (`shop_id`),
    KEY `idx_category_id` (`category_id`),
    KEY `idx_status` (`status`),
    KEY `idx_review_status` (`review_status`),
    -- 入驻申请关联商品的审核/拒绝分支按 application_id 过滤，此前无索引 → 全表扫描（且在 Seata 全局事务内）
    KEY `idx_application_id` (`application_id`),
    -- 首页/检索列表：status=1 AND review_status=1 ORDER BY create_time DESC，单列索引会退化为回表 + filesort
    KEY `idx_status_review_create` (`status`, `review_status`, `create_time`),
    -- 店铺主页：在上述条件上再加 shop_id
    KEY `idx_shop_status_review` (`shop_id`, `status`, `review_status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '商品SPU表';

-- -------------------------------------------------------------
-- SKU 表（库存量单元）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `sku`;
CREATE TABLE `sku`
(
    `id`          BIGINT         NOT NULL COMMENT '主键',
    `spu_id`      BIGINT         NOT NULL COMMENT '所属SPU ID',
    `shop_id`     BIGINT         NOT NULL COMMENT '所属店铺ID（数据隔离关键字段）',
    `title`       VARCHAR(100)   NOT NULL COMMENT 'SKU名称（如：黑色/128G）',
    `price`       DECIMAL(10, 2) NOT NULL COMMENT '价格（元）',
    `stock`       INT            DEFAULT NULL COMMENT '库存：NULL 表示不限量；0..N 为限量可售（0=售罄）',
    `sale_count`  INT            NOT NULL DEFAULT 0 COMMENT '销量',
    `status`      TINYINT        NOT NULL DEFAULT 0 COMMENT '状态：0下架 1上架',
    `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_spu_id` (`spu_id`),
    KEY `idx_shop_id` (`shop_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '商品SKU表';

-- -------------------------------------------------------------
-- SKU 图片表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `sku_images`;
CREATE TABLE `sku_images`
(
    `id`      BIGINT       NOT NULL COMMENT '主键',
    `sku_id`  BIGINT       NOT NULL COMMENT '所属SKU ID',
    `img_url` VARCHAR(255) NOT NULL COMMENT '图片地址',
    `sort`    INT          NOT NULL DEFAULT 0 COMMENT '排序',
    PRIMARY KEY (`id`),
    KEY `idx_sku_id` (`sku_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'SKU图片表';

-- -------------------------------------------------------------
-- 种子数据：常用分类与品牌
-- -------------------------------------------------------------
INSERT INTO `category` (`name`, `parent_id`, `sort`) VALUES
('服饰鞋包', 0, 1),
('数码家电', 0, 2),
('家居日用', 0, 3),
('运动户外', 0, 4),
('闲置手工艺', 0, 5);

INSERT INTO `brand` (`name`, `logo`) VALUES
('无品牌', ''),
('小米', ''),
('华为', ''),
('苹果', ''),
('耐克', '');

-- -------------------------------------------------------------
-- 审核审计日志（谁在何时审了谁、结果与原因）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log`
(
    `id`          BIGINT      NOT NULL COMMENT '主键',
    `operator_id` BIGINT      NOT NULL COMMENT '操作人用户ID（管理员）',
    `action`      VARCHAR(32) NOT NULL COMMENT '动作：PRODUCT_REVIEW 商品审核（含批量）',
    `target_id`   BIGINT      NOT NULL COMMENT '被审对象ID（商品SPU ID）',
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
-- ES 写失败补偿表（上架写索引/下架删索引失败时落表，定时任务按退避重放，
-- 保证 ES 与 MySQL 最终一致；重试超限置为「放弃」等待人工介入）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `es_write_retry`;
CREATE TABLE `es_write_retry`
(
    `id`              BIGINT       NOT NULL COMMENT '主键',
    `spu_id`          BIGINT       NOT NULL COMMENT '商品SPU ID',
    `action`          VARCHAR(16)  NOT NULL COMMENT '动作：INDEX 写入 / DELETE 删除',
    `doc_json`        TEXT         DEFAULT NULL COMMENT 'INDEX 动作的完整检索文档JSON（DELETE 动作可为空）',
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

-- -------------------------------------------------------------
-- 销量回退幂等表（退款审核通过回退销量时先插本表：order_sn 唯一，
-- 防「product 已回退 → 响应超时 → 订单全局事务回滚 → 卖家重试」造成二次扣减）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `sale_rollback_log`;
CREATE TABLE `sale_rollback_log`
(
    `id`          BIGINT      NOT NULL COMMENT '主键',
    `order_sn`    VARCHAR(64) NOT NULL COMMENT '订单号（唯一：同一订单只回退一次销量）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_sn` (`order_sn`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '销量回退幂等表';

-- -------------------------------------------------------------
-- Seata AT 模式 undo_log 表（shop apply 跨服务事务中 product 为分支，必需）
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
