-- =============================================================
-- 轻营 · 主键策略迁移：自增 → 雪花（MyBatis-Plus ASSIGN_ID）
-- 日期：2026-09-06
--
-- 背景：
--   实体主键已切换为 MyBatis-Plus 雪花（@TableId(ASSIGN_ID)），新写入的行由应用分配
--   64 位 id（约 15~19 位十进制），不再依赖数据库自增；对外 JSON 中装箱 Long 已由
--   lingmart-common LongJsonAutoConfiguration 输出为字符串（避免 JS 精度丢失）。
--
-- 本脚本用途：仅针对【已用旧 DDL 初始化过的存量库】，把业务主键列的 AUTO_INCREMENT
--   属性去掉（MySQL 无 DROP AUTO_INCREMENT 语法，只能 MODIFY 整列定义）。
--   全新初始化的库直接用仓库内新 DDL（已无 AUTO_INCREMENT），无需执行本脚本。
--
-- 执行顺序建议（按你工作边界，由运维执行）：
--   1. 先停业务服务；
--   2. 逐库执行下方 ALTER（幂等：重复执行亦无副作用，MODIFY 同定义安全）；
--   3. 推送 Nacos 共享配置（scripts/push-nacos-config.sh）使 id-type=assign_id 生效（实体注解已显式指定，此项仅兜底）；
--   4. 重新编译启动全部服务。
--
-- 注意：undo_log（Seata）主键为 branch_id，非应用自增主键，无需迁移。
-- 多实例部署同一服务时，请为各实例配置不同 worker-id 避免雪花冲突。
-- =============================================================

-- ---------------- lingmart_chat ----------------
USE `lingmart_chat`;
ALTER TABLE `chat_conversation` MODIFY `id` BIGINT NOT NULL COMMENT '主键';
ALTER TABLE `chat_message`     MODIFY `id` BIGINT NOT NULL COMMENT '主键';

-- ---------------- lingmart_auth ----------------
USE `lingmart_auth`;
ALTER TABLE `user` MODIFY `id` BIGINT NOT NULL COMMENT '主键';

-- ---------------- lingmart_shop ----------------
USE `lingmart_shop`;
ALTER TABLE `shop`            MODIFY `id` BIGINT NOT NULL COMMENT '主键';
ALTER TABLE `shop_application` MODIFY `id` BIGINT NOT NULL COMMENT '主键';
ALTER TABLE `audit_log`       MODIFY `id` BIGINT NOT NULL COMMENT '主键';

-- ---------------- lingmart_product ----------------
USE `lingmart_product`;
ALTER TABLE `category`       MODIFY `id` BIGINT NOT NULL COMMENT '主键';
ALTER TABLE `brand`          MODIFY `id` BIGINT NOT NULL COMMENT '主键';
ALTER TABLE `spu`            MODIFY `id` BIGINT NOT NULL COMMENT '主键';
ALTER TABLE `sku`            MODIFY `id` BIGINT NOT NULL COMMENT '主键';
ALTER TABLE `sku_images`     MODIFY `id` BIGINT NOT NULL COMMENT '主键';
ALTER TABLE `audit_log`      MODIFY `id` BIGINT NOT NULL COMMENT '主键';
ALTER TABLE `es_write_retry` MODIFY `id` BIGINT NOT NULL COMMENT '主键';

-- ---------------- lingmart_order ----------------
USE `lingmart_order`;
ALTER TABLE `order`      MODIFY `id` BIGINT NOT NULL COMMENT '主键';
ALTER TABLE `order_item` MODIFY `id` BIGINT NOT NULL COMMENT '主键';
ALTER TABLE `cart`       MODIFY `id` BIGINT NOT NULL COMMENT '主键';

-- ---------------- lingmart_ware ----------------
USE `lingmart_ware`;
ALTER TABLE `ware_sku`         MODIFY `id` BIGINT NOT NULL COMMENT '主键';
ALTER TABLE `ware_order_task`  MODIFY `id` BIGINT NOT NULL COMMENT '主键';
