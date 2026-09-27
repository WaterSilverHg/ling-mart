-- =============================================================
-- 轻营 · 压测数据清理（DB 侧）
--
-- 用法：
--   cmd /c scripts\load-test\seed\teardown-loadtest.bat
--   bash scripts/load-test/seed/teardown-loadtest.sh
-- 手工执行：
--   docker exec -i lingmart-mysql mysql -uroot -proot --default-character-set=utf8mb4 \
--     < scripts/load-test/seed/teardown-loadtest.sql
--
-- 只删 seed-loadtest.sql 那批固定 ID 区间的数据，**不会碰任何真实数据**：
--   买家 9100001~9100020  卖家 9200001~9200020  店铺 9300001~9300020
--   SPU  9400001~9400500  SKU  9500001~9501500  图片 9600001~9603000  库存 9700001~9701500
--
-- ⚠️ ES 侧的文档不在这个文件里：ES 由 reconcileEsIndex 只补不删，SQL 删库不会带走索引文档，
--    残留文档会让搜索仍能搜到已删商品（点进去 404）。请用 post-seed.mjs 清理：
--      node scripts/load-test/seed/post-seed.mjs --clean
--    （等价于 ES 的 POST /lingmart_product/_delete_by_query，按 shopId 区间删）
-- =============================================================

-- 选一个默认库：下面的表名虽然都全限定，但"未指定库时能否建临时表"不值得赌，
-- 显式 USE 一下最稳（选哪个库都无所谓，因为每条语句都带库名）。
USE `lingmart_order`;

-- 先捞订单号：MySQL 不允许在 DELETE 的子查询里读目标表（错误 1093）
DROP TEMPORARY TABLE IF EXISTS `_seed_order`;
CREATE TEMPORARY TABLE `_seed_order` (`id` BIGINT PRIMARY KEY, `order_sn` VARCHAR(64));
INSERT INTO `_seed_order` (`id`, `order_sn`)
SELECT `id`, `order_sn` FROM `lingmart_order`.`order` WHERE `buyer_id` BETWEEN 9100001 AND 9100020;

-- 订单链路（买家侧：购物车、订单、明细、Outbox 事件）
DELETE FROM `lingmart_order`.`cart`             WHERE `buyer_id` BETWEEN 9100001 AND 9100020;
DELETE FROM `lingmart_order`.`order_item`       WHERE `order_id` IN (SELECT `id` FROM `_seed_order`);
DELETE FROM `lingmart_order`.`order_paid_event` WHERE `order_sn` IN (SELECT `order_sn` FROM `_seed_order`);
DELETE FROM `lingmart_order`.`order`            WHERE `id` IN (SELECT `id` FROM `_seed_order`);

-- 仓储链路（锁定任务、扣减流水）；库存数量随 ware_sku 整行删除，无需回补
DELETE FROM `lingmart_ware`.`ware_order_task` WHERE `order_sn` IN (SELECT `order_sn` FROM `_seed_order`);
DELETE FROM `lingmart_ware`.`stock_flow_log`  WHERE `order_sn` IN (SELECT `order_sn` FROM `_seed_order`);

-- 商品与店铺数据可能留下的副产物
DELETE FROM `lingmart_product`.`sale_rollback_log` WHERE `order_sn` IN (SELECT `order_sn` FROM `_seed_order`);
DELETE FROM `lingmart_product`.`es_write_retry`    WHERE `spu_id` BETWEEN 9400001 AND 9400500;

-- 目录数据（先子后父）
DELETE FROM `lingmart_product`.`sku_images` WHERE `id` BETWEEN 9600001 AND 9603000;
DELETE FROM `lingmart_product`.`sku`        WHERE `id` BETWEEN 9500001 AND 9501500;
DELETE FROM `lingmart_product`.`spu`        WHERE `id` BETWEEN 9400001 AND 9400500;
DELETE FROM `lingmart_ware`.`ware_sku`      WHERE `id` BETWEEN 9700001 AND 9701500;
DELETE FROM `lingmart_shop`.`shop`          WHERE `id` BETWEEN 9300001 AND 9300020;

-- 账号（买家 + 卖家；管理员 13800000000 不在区间内，不受影响）
DELETE FROM `lingmart_auth`.`user` WHERE `id` BETWEEN 9100001 AND 9100020;
DELETE FROM `lingmart_auth`.`user` WHERE `id` BETWEEN 9200001 AND 9200020;

DROP TEMPORARY TABLE IF EXISTS `_seed_order`;

-- 清理结果自检：全部应为 0（列名用 ASCII，避免 GBK 控制台乱码）
SELECT 'users'      AS `item`, COUNT(*) AS `rows_expected_0`
  FROM `lingmart_auth`.`user` WHERE `id` BETWEEN 9100001 AND 9100020 OR `id` BETWEEN 9200001 AND 9200020
UNION ALL SELECT 'shops',      COUNT(*) FROM `lingmart_shop`.`shop`          WHERE `id` BETWEEN 9300001 AND 9300020
UNION ALL SELECT 'spu',        COUNT(*) FROM `lingmart_product`.`spu`        WHERE `id` BETWEEN 9400001 AND 9400500
UNION ALL SELECT 'sku',        COUNT(*) FROM `lingmart_product`.`sku`        WHERE `id` BETWEEN 9500001 AND 9501500
UNION ALL SELECT 'sku_images', COUNT(*) FROM `lingmart_product`.`sku_images` WHERE `id` BETWEEN 9600001 AND 9603000
UNION ALL SELECT 'ware_sku',   COUNT(*) FROM `lingmart_ware`.`ware_sku`      WHERE `id` BETWEEN 9700001 AND 9701500;
