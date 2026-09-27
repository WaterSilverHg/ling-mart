-- =============================================================
-- 轻营 · 唯一键与索引补齐（存量库迁移，三段式自检）
-- 日期：2026-09-20
--
-- 用途：为已初始化的存量库补上「并发兜底唯一键」与「列表/审核/对账路径缺失的索引」。
--       全新库已含在 sql/lingmart_*.sql 基线里，无需执行本脚本。
--
-- 本脚本分三段，请**按顺序、分段执行**（不要整文件一次性灌进去）：
--   第一段：自检 —— 只读查询，打印重复数据；有输出就先处理，否则第二段会失败
--   第二段：加约束 —— 唯一键与索引（执行前请确认第一段无输出）
--   第三段：清理语句 —— 默认注释掉，确认后按需启用（cart 可自动合并；shop 必须人工判断）
--
-- 用法（逐段复制执行，或按段拆分后执行）：
--   docker exec -i lingmart-mysql mysql -uroot -proot < 本文件
--   （若按段执行，建议先单独跑第一段看结果）
-- =============================================================


-- =============================================================
-- 第一段 · 自检（只读，先跑这一段）
-- =============================================================

USE `lingmart_order`;
-- 购物车重复行：同一买家同一 SKU 出现多条（双击加购/并发导致），会导致列表显示两条同款明细
SELECT '【cart 重复行】' AS `检查项`, `buyer_id`, `sku_id`, COUNT(*) AS `重复条数`, GROUP_CONCAT(`id`) AS `主键列表`
FROM `cart`
GROUP BY `buyer_id`, `sku_id`
HAVING COUNT(*) > 1;

USE `lingmart_shop`;
-- 店铺重复行：同一用户出现多家店铺（双击提交入驻申请导致）。⚠️ 后果严重：之后所有
-- selectOne(user_id) 会抛 TooManyResultsException，该用户永久无法开店/发布商品。
-- 这两行可能各自挂着商品、订单、会话，**不要**直接删——见第三段的人工判断指引。
SELECT '【shop 重复行】' AS `检查项`, `user_id`, COUNT(*) AS `重复条数`, GROUP_CONCAT(`id`) AS `店铺ID列表`,
       GROUP_CONCAT(`shop_name`) AS `店铺名列表`, GROUP_CONCAT(`review_status`) AS `审核状态列表`
FROM `shop`
GROUP BY `user_id`
HAVING COUNT(*) > 1;


-- =============================================================
-- 第二段 · 加唯一键与索引（第一段无输出后再执行）
-- =============================================================

-- ---------- lingmart_order ----------
USE `lingmart_order`;

-- 购物车 (buyer_id, sku_id) 唯一：应用层 CartServiceImpl.add 已捕 DuplicateKeyException 转为累加数量。
-- 若本句报 Duplicate entry，说明第一段检出了重复行 —— 先按第三段处理。
ALTER TABLE `cart`
    ADD UNIQUE KEY `uk_buyer_sku` (`buyer_id`, `sku_id`);

-- Outbox 事件状态新增 2=发送中（CAS 抢占后、投递完成前）；列类型 TINYINT 无需变更，仅更新注释
ALTER TABLE `order_paid_event`
    MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0=待发送 1=已发送 2=发送中（CAS 抢占后、投递完成前）';

-- ---------- lingmart_shop ----------
USE `lingmart_shop`;

-- 店铺 user_id 唯一：应用层 ShopServiceImpl.apply 已捕 DuplicateKeyException 兜底。
-- 若本句报 Duplicate entry，说明第一段检出了重复行 —— 先按第三段处理（需人工判断，勿自动删）。
ALTER TABLE `shop`
    ADD UNIQUE KEY `uk_user_id` (`user_id`);

-- 审计日志：管理端只按 create_time 倒序翻页，无索引会全表排序（日志只增不减，越翻越慢）
ALTER TABLE `audit_log`
    ADD KEY `idx_create_time` (`create_time`);

-- ---------- lingmart_product ----------
USE `lingmart_product`;

-- 入驻申请关联商品按 application_id 过滤（审核/拒绝/按申请查列表三处），此前无索引 → 全表扫描
ALTER TABLE `spu`
    ADD KEY `idx_application_id` (`application_id`);

-- 首页/检索列表：status=1 AND review_status=1 ORDER BY create_time DESC
ALTER TABLE `spu`
    ADD KEY `idx_status_review_create` (`status`, `review_status`, `create_time`);

-- 店铺主页：上述条件再加 shop_id
ALTER TABLE `spu`
    ADD KEY `idx_shop_status_review` (`shop_id`, `status`, `review_status`, `create_time`);

-- 审计日志：同 shop 库，按 create_time 倒序翻页
ALTER TABLE `audit_log`
    ADD KEY `idx_create_time` (`create_time`);


-- =============================================================
-- 第三段 · 清理语句（默认全部注释，确认后按需启用）
-- =============================================================

-- 【cart 重复行】可自动合并：把重复行的数量累加到最小 id 那一行，再删掉其余行。
-- 合并前建议先看一眼第一段的输出确认无异常。注意：若重复行的 num 已被用户改过，
-- 合并 = 数量求和，可能大于用户预期，可改为「保留 MAX(num)」。
-- USE `lingmart_order`;
-- UPDATE `cart` c
--     JOIN (SELECT `buyer_id`, `sku_id`, MIN(`id`) AS keep_id, SUM(`num`) AS total_num
--           FROM `cart` GROUP BY `buyer_id`, `sku_id` HAVING COUNT(*) > 1) d
--     ON c.`id` = d.keep_id
-- SET c.`num` = d.total_num;
-- DELETE c FROM `cart` c
--     JOIN (SELECT `buyer_id`, `sku_id`, MIN(`id`) AS keep_id
--           FROM `cart` GROUP BY `buyer_id`, `sku_id` HAVING COUNT(*) > 1) d
--     ON c.`buyer_id` = d.`buyer_id` AND c.`sku_id` = d.`sku_id` AND c.`id` <> d.keep_id;

-- 【shop 重复行】**不要自动删**。两行店铺可能各自挂着商品、订单、会话，删错会孤立数据。
-- 人工判断步骤：
--   1) 先看哪一行「在用」：以下三条查询分别列出各候选店铺名下的资产
--      SELECT `shop_id`, COUNT(*) AS `商品数` FROM `lingmart_product`.`spu` WHERE `shop_id` IN (...) GROUP BY `shop_id`;
--      SELECT `shop_id`, COUNT(*) AS `订单数` FROM `lingmart_order`.`order` WHERE `shop_id` IN (...) GROUP BY `shop_id`;
--      SELECT `shop_id`, COUNT(*) AS `会话数` FROM `lingmart_chat`.`chat_conversation` WHERE `shop_id` IN (...) GROUP BY `shop_id`;
--   2) 保留资产多/审核通过的那一行，把待删行的 `shop_id` 迁移到保留行（商品/订单/会话三处 UPDATE），
--      或（更省事）在确认待删行无任何资产后直接 DELETE；
--   3) 处理完再回到第二段执行 `ADD UNIQUE KEY uk_user_id`。
-- 参考：若要查某用户的两行店铺明细
--   SELECT * FROM `shop` WHERE `user_id` = ?;
