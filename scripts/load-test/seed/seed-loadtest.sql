-- =============================================================
-- 轻营 · 压测数据播种（用户 + 店铺 + 商品 + SKU + 图片 + 库存）
--
-- 用法（推荐用包装脚本，它还会顺带把商品写进 ES）：
--   cmd /c scripts\load-test\seed\seed-loadtest.bat
--   bash scripts/load-test/seed/seed-loadtest.sh
-- 手工执行：
--   docker exec -i lingmart-mysql mysql -uroot -proot --default-character-set=utf8mb4 \
--     < scripts/load-test/seed/seed-loadtest.sql
--
-- 【密码从哪来】密码是 BCrypt，纯 SQL 造不出来。本脚本**复制管理员那行的哈希**，
--   所以所有压测账号的密码 = 管理员密码（默认 admin123，见 ADMIN_PASSWORD）。
--   管理员由 lingmart-auth 启动时的 AdminUserInitializer 保证存在；
--   若还没启动过 auth，脚本会直接报错中止（见下面的 SIGNAL），不会插入用不了的账号。
--
-- 【为什么不用管理员当买家】管理员是 ROLE_ADMIN，语义上只能管理、不能下单。
--   所以这里造 20 个 ROLE_USER 买家（k6 用它们）+ 20 个 ROLE_SELLER 卖家（店铺主）。
--
-- 【幂等】脚本开头按固定 id 区间删除旧数据再插入，可反复重跑。
--
-- 【商品要能被搜到必须进 ES】SQL 插库不会进 ES。两条路：
--   ① 跑 post-seed.mjs（逐店调 /api/admin/product/shop/{id}/reindex-es）——立刻生效；
--   ② 什么都不做，等 reconcileEsIndex 定时任务（默认 5 分钟）自动补索引。
--
-- 【清理】teardown-loadtest.sql / .bat / .sh
--
-- 规模：20 店 × 25 商品 = 500 SPU / 1500 SKU / 3000 张 SKU 图片 / 1500 行库存
-- ID 区间（刻意避开雪花 id，便于一键清理）：
--   买家 9100001~9100020  卖家 9200001~9200020  店铺 9300001~9300020
--   SPU  9400001~9400500  SKU  9500001~9501500  图片 9600001~9603000  库存 9700001~9701500
-- =============================================================

-- 【为什么必须 USE 一个库】本脚本跨 4 个库（表名全部全限定），但**存储过程必须归属某个库**：
--   `DROP PROCEDURE` / `CREATE PROCEDURE` 不带库名时会报 `ERROR 1046 No database selected`。
--   这里借用 lingmart_auth 当归属库；表名仍然全部全限定，所以选哪个库不影响结果。
--   （例程内未限定的名字按"创建时的默认库"解析，临时表则始终优先在会话命名空间里找。）
USE `lingmart_auth`;

-- mysql 客户端在批处理模式下遇错即停（返回非 0），所以这里只需把错误抛出来
DROP PROCEDURE IF EXISTS `lingmart_auth`.`seed_loadtest`;
DELIMITER $$
CREATE PROCEDURE `lingmart_auth`.`seed_loadtest`()
BEGIN
    DECLARE v_pwd VARCHAR(100) DEFAULT NULL;

    -- 取管理员密码哈希；拿不到就中止，避免造出一批登录不了的账号（那会让压测"静默空跑"）
    SELECT `password` INTO v_pwd
      FROM `lingmart_auth`.`user` WHERE `phone` = '13800000000' LIMIT 1;
    IF v_pwd IS NULL THEN
        -- 消息保持 ASCII：中文在 GBK 控制台下会变乱码，反而看不清
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT =
            'SEED ABORTED: admin user (13800000000) not found. Start lingmart-auth first - AdminUserInitializer creates it on startup.';
    END IF;

    -- ---------- 0. 序列与图片池（临时表，会话结束自动消失） ----------
    -- 序号表：4 张各自独立的 10 行小表交叉连接 = 10000 个序号。
    -- 为什么这么绕：
    --   ① 不能写「同一张临时表自连接多次」——MySQL 限制同一查询里不能多次引用同一张临时表，
    --      会报 ERROR 1137 Can't reopen table（本脚本踩过）；4 张**不同**的表各出现一次就没这问题。
    --   ② 也不用递归 CTE——那要依赖 cte_max_recursion_depth（默认仅 1000，而这里需要 3000），
    --      还要赌「派生表里能否写 WITH」。这里选自己最有把握、没有隐含假设的写法。
    DROP TEMPORARY TABLE IF EXISTS `_seed_d1`;
    DROP TEMPORARY TABLE IF EXISTS `_seed_d2`;
    DROP TEMPORARY TABLE IF EXISTS `_seed_d3`;
    DROP TEMPORARY TABLE IF EXISTS `_seed_d4`;
    CREATE TEMPORARY TABLE `_seed_d1` (`d` INT PRIMARY KEY);
    CREATE TEMPORARY TABLE `_seed_d2` (`d` INT PRIMARY KEY);
    CREATE TEMPORARY TABLE `_seed_d3` (`d` INT PRIMARY KEY);
    CREATE TEMPORARY TABLE `_seed_d4` (`d` INT PRIMARY KEY);
    INSERT INTO `_seed_d1` (`d`) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
    INSERT INTO `_seed_d2` (`d`) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
    INSERT INTO `_seed_d3` (`d`) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
    INSERT INTO `_seed_d4` (`d`) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

    DROP TEMPORARY TABLE IF EXISTS `_seed_seq`;
    CREATE TEMPORARY TABLE `_seed_seq` (`n` INT PRIMARY KEY);
    INSERT INTO `_seed_seq` (`n`)
    SELECT a.`d` + b.`d` * 10 + c.`d` * 100 + e.`d` * 1000 + 1
      FROM `_seed_d1` a, `_seed_d2` b, `_seed_d3` c, `_seed_d4` e;

    -- 图片池复用仓库里真实存在的 /uploads 文件，这样前端能显示真图，数据看起来与真实一致
    DROP TEMPORARY TABLE IF EXISTS `_seed_img`;
    CREATE TEMPORARY TABLE `_seed_img` (`idx` INT PRIMARY KEY, `url` VARCHAR(255) NOT NULL);
    INSERT INTO `_seed_img` (`idx`, `url`) VALUES
        ( 1, '/uploads/20260830/1119617a092a4f5e8b9d380acb1dea0c.png'),
        ( 2, '/uploads/20260830/3c4ac65f23764209a515ff461fbafbcb.png'),
        ( 3, '/uploads/20260830/7c738181b8a74b02826607c2f67291cb.png'),
        ( 4, '/uploads/20260831/7f3cd35cd6bd4e2f95130da11db1d725.jpg'),
        ( 5, '/uploads/20260831/811ebe27468a47b0b141cb4d84e26d4d.jpg'),
        ( 6, '/uploads/20260831/906c1424c803471493d15358c1f55e6f.jpg'),
        ( 7, '/uploads/20260901/9c92fe540584475b90c91bd67e409bf6.jpg'),
        ( 8, '/uploads/20260901/9d197a1db7b543538f0158c70cfac28c.png'),
        ( 9, '/uploads/20260906/6eb330a58ce444b7adfb833c8e091d58.png'),
        (10, '/uploads/20260906/a493e30f31db4b27aa5d3e9da438bfdc.jpg');

    -- ---------- 1. 清理旧数据（幂等重跑的前提） ----------
    -- 先把这批买家的订单号捞进临时表：MySQL 不允许在 DELETE 的子查询里读目标表（错误 1093）。
    --
    -- 【为什么用 CREATE ... AS SELECT 建表，而不是手写列定义】collation 对齐问题：
    --   临时表的字符串列如果按"会话默认 collation"建，而正式表的列是另一种 collation，
    --   那么 `order_sn IN (SELECT order_sn FROM _seed_order)` 里两边都是列引用
    --   （coercibility 同为 2，MySQL 无法裁决）→ ERROR 1267 Illegal mix of collations。
    --   `CREATE TABLE ... AS SELECT` 会把**源列的字符集与 collation 原样复制**过来，
    --   于是与正式表的比较天生同源，写死 COLLATE 反而会在"库表并非该 collation"时制造出同样的错。
    --   （`WHERE 1 = 0` 只取列定义、不搬数据；下面再 INSERT 真正要用的行。）
    DROP TEMPORARY TABLE IF EXISTS `_seed_order`;
    CREATE TEMPORARY TABLE `_seed_order` AS
        SELECT `id`, `order_sn` FROM `lingmart_order`.`order` WHERE 1 = 0;
    INSERT INTO `_seed_order` (`id`, `order_sn`)
    SELECT `id`, `order_sn` FROM `lingmart_order`.`order` WHERE `buyer_id` BETWEEN 9100001 AND 9100020;

    DELETE FROM `lingmart_order`.`cart`             WHERE `buyer_id` BETWEEN 9100001 AND 9100020;
    DELETE FROM `lingmart_order`.`order_item`       WHERE `order_id` IN (SELECT `id` FROM `_seed_order`);
    DELETE FROM `lingmart_order`.`order_paid_event` WHERE `order_sn` IN (SELECT `order_sn` FROM `_seed_order`);
    DELETE FROM `lingmart_order`.`order`            WHERE `id` IN (SELECT `id` FROM `_seed_order`);
    DELETE FROM `lingmart_ware`.`ware_order_task`   WHERE `order_sn` IN (SELECT `order_sn` FROM `_seed_order`);
    DELETE FROM `lingmart_ware`.`stock_flow_log`    WHERE `order_sn` IN (SELECT `order_sn` FROM `_seed_order`);

    DELETE FROM `lingmart_product`.`sku_images` WHERE `id` BETWEEN 9600001 AND 9603000;
    DELETE FROM `lingmart_product`.`sku`        WHERE `id` BETWEEN 9500001 AND 9501500;
    DELETE FROM `lingmart_product`.`spu`        WHERE `id` BETWEEN 9400001 AND 9400500;
    DELETE FROM `lingmart_ware`.`ware_sku`      WHERE `id` BETWEEN 9700001 AND 9701500;
    DELETE FROM `lingmart_shop`.`shop`          WHERE `id` BETWEEN 9300001 AND 9300020;
    -- 收货地址（v0.39 起下单需要地址；压测买家各一条默认地址）
    DELETE FROM `lingmart_order`.`user_address` WHERE `id` BETWEEN 9900001 AND 9900020;
    DELETE FROM `lingmart_auth`.`user`          WHERE `id` BETWEEN 9100001 AND 9100020;
    DELETE FROM `lingmart_auth`.`user`          WHERE `id` BETWEEN 9200001 AND 9200020;

    -- 只按 id 区间删还不够：这两个唯一键与 id 无关，历史遗留的行会直接顶掉 INSERT。
    --   ① user.uk_phone —— 早期文档让人"自己注册一个压测账号"，默认手机号正是 13900000001，
    --      那行 id 是雪花值、不在上面的区间里，于是 INSERT 会报 Duplicate entry '13900000001'。
    --   ② shop.uk_shop_name —— 同名店铺同理。
    -- 下面按"压测保留号段/店名前缀"清一遍；**显式排除管理员手机号**（13800000000 也匹配这个前缀，
    -- 误删就再也登不进管理端了）。删掉的是压测号段里的账号，属脚本自己的命名空间。
    DELETE FROM `lingmart_auth`.`user`
     WHERE (`phone` LIKE '139000000%' OR `phone` LIKE '138000000%')
       AND `phone` <> '13800000000';
    DELETE FROM `lingmart_shop`.`shop` WHERE `shop_name` LIKE '压测店铺%';

    -- ---------- 2. 用户（买家 ROLE_USER / 卖家 ROLE_SELLER） ----------
    INSERT INTO `lingmart_auth`.`user` (`id`, `phone`, `password`, `nickname`, `avatar`, `role`, `status`)
    SELECT 9100000 + s.`n`, CONCAT('139', LPAD(s.`n`, 8, '0')), v_pwd,
           CONCAT('压测买家', LPAD(s.`n`, 2, '0')), NULL, 'ROLE_USER', 1
      FROM `_seed_seq` s WHERE s.`n` <= 20;

    INSERT INTO `lingmart_auth`.`user` (`id`, `phone`, `password`, `nickname`, `avatar`, `role`, `status`)
    SELECT 9200000 + s.`n`, CONCAT('138', LPAD(s.`n`, 8, '0')), v_pwd,
           CONCAT('压测卖家', LPAD(s.`n`, 2, '0')), NULL, 'ROLE_SELLER', 1
      FROM `_seed_seq` s WHERE s.`n` <= 20;

    -- ---------- 2.1 收货地址（每位买家一条默认地址） ----------
    -- 仅快递订单需要收货地址；压测默认走「到店自提」，这条地址是为覆盖快递链路留的（可选用例）。
    INSERT INTO `lingmart_order`.`user_address`
        (`id`, `user_id`, `receiver_name`, `receiver_phone`, `province`, `city`, `district`,
         `detail_address`, `is_default`)
    SELECT 9900000 + s.`n`, 9100000 + s.`n`, CONCAT('压测买家', LPAD(s.`n`, 2, '0')),
           CONCAT('139', LPAD(s.`n`, 8, '0')), '浙江省', '杭州市', '西湖区',
           CONCAT('压测路 ', s.`n`, ' 号'), 1
      FROM `_seed_seq` s WHERE s.`n` <= 20;

    -- ---------- 3. 店铺（店主 = 上面的卖家，直接置营业中 + 审核通过） ----------
    -- v0.40 起自提是默认履约方式：必须给每店一个门店地址，否则下单会失败在「尚未配置自提地址」。
    -- support_express 保持默认 0（仅自提），与自营实体店的典型配置一致。
    INSERT INTO `lingmart_shop`.`shop`
        (`id`, `user_id`, `shop_name`, `shop_logo`, `description`,
         `address`, `contact_phone`, `status`, `review_status`)
    SELECT 9300000 + s.`n`, 9200000 + s.`n`, CONCAT('压测店铺', LPAD(s.`n`, 2, '0')),
           im.`url`, '压测数据，可安全删除（见 seed-loadtest.sql）',
           CONCAT('杭州市西湖区压测路 ', s.`n`, ' 号'), CONCAT('0571-8888', LPAD(s.`n`, 4, '0')),
           1, 1
      FROM `_seed_seq` s
      JOIN `_seed_img` im ON im.`idx` = 1 + ((s.`n` - 1) MOD 10)
     WHERE s.`n` <= 20;

    -- ---------- 4. SPU（每店 25 个，共 500） ----------
    -- 标题 = 品类 + 成色 + 品牌 + 「N店第M款」：
    --   三类词覆盖用户词/品牌词/成色词，检索能真的走到 IK 分词，而不是只测 match_all
    INSERT INTO `lingmart_product`.`spu`
        (`id`, `shop_id`, `title`, `subtitle`, `category_id`, `brand_id`, `main_image`,
         `description`, `status`, `review_status`, `application_id`, `sale_count`, `min_price`)
    SELECT
        9400000 + s.`n`,
        9300000 + ((s.`n` - 1) DIV 25) + 1,
        CONCAT(
            ELT(1 + ((s.`n` - 1) MOD 8), '智能手机', '蓝牙耳机', '机械键盘', '双肩背包',
                                          '护眼台灯', '保温杯', '运动手表', '桌面音箱'),
            ' ',
            ELT(1 + ((s.`n` - 1) MOD 4), '全新', '九成新', '准新', '闲置'),
            ' ',
            ELT(1 + ((s.`n` - 1) MOD 5), '小米', '华为', '苹果', '索尼', '自用'),
            ' ', ((s.`n` - 1) DIV 25) + 1, '店第', ((s.`n` - 1) MOD 25) + 1, '款'),
        CONCAT('压测商品 · ', ((s.`n` - 1) MOD 25) + 1, ' 号位'),
        NULL, NULL, im.`url`,
        '本商品由 seed-loadtest.sql 生成，仅用于压测，可安全删除。',
        1, 1, NULL,
        (s.`n` * 7) MOD 500,
        ROUND(19 + ((s.`n` - 1) MOD 80) * 13.7, 2)
      FROM `_seed_seq` s
      JOIN `_seed_img` im ON im.`idx` = 1 + ((s.`n` - 1) MOD 10)
     WHERE s.`n` <= 500;

    -- ---------- 5. SKU（每 SPU 3 个，共 1500） ----------
    -- 库存策略：每店第 1 个 SPU 的 SKU 设为**不限量**（stock=NULL，ware_sku.unlimited=1），
    --   作为写链路（购物车/下单/支付）的兜底 —— 反复跑也不会把库存打空，
    --   同时让 v0.9 的"不限量"分支也被压到。其余 SKU 给 100000，避免长时间压测耗尽。
    INSERT INTO `lingmart_product`.`sku`
        (`id`, `spu_id`, `shop_id`, `title`, `price`, `stock`, `sale_count`, `status`)
    SELECT
        9500000 + s.`n`,
        9400000 + ((s.`n` - 1) DIV 3) + 1,
        9300000 + ((((s.`n` - 1) DIV 3) DIV 25)) + 1,
        CONCAT('规格', ((s.`n` - 1) MOD 3) + 1, ' · ',
               ELT(((s.`n` - 1) MOD 3) + 1, '标准版', '大容量版', '礼盒装')),
        ROUND(19 + (((s.`n` - 1) DIV 3) MOD 80) * 13.7, 2) + (((s.`n` - 1) MOD 3) * 20),
        -- 不限量判定：SPU 序号 p = (n-1) DIV 3 + 1，要求 p ≡ 1 (mod 25)（即每店第 1 个商品），
        -- 等价于 (p-1) MOD 25 = 0，而 p-1 就是 (n-1) DIV 3。注意别写成 ((n-1) DIV 3) - 1：
        -- MySQL 的 MOD 保留被除数符号，-1 MOD 25 仍是 -1，不会绕回 24。
        IF(((s.`n` - 1) DIV 3) MOD 25 = 0, NULL, 100000),
        (s.`n` * 3) MOD 200,
        1
      FROM `_seed_seq` s
     WHERE s.`n` <= 1500;

    -- ---------- 6. SKU 图片（每 SKU 2 张，共 3000） ----------
    INSERT INTO `lingmart_product`.`sku_images` (`id`, `sku_id`, `img_url`, `sort`)
    SELECT 9600000 + s.`n`, 9500000 + ((s.`n` - 1) DIV 2) + 1,
           im.`url`, ((s.`n` - 1) MOD 2)
      FROM `_seed_seq` s
      JOIN `_seed_img` im ON im.`idx` = 1 + ((s.`n` * 3) MOD 10)
     WHERE s.`n` <= 3000;

    -- ---------- 7. 仓储库存行（与 SKU 一一对应，共 1500） ----------
    INSERT INTO `lingmart_ware`.`ware_sku`
        (`id`, `spu_id`, `sku_id`, `shop_id`, `title`, `stock`, `locked_stock`, `unlimited`, `status`)
    SELECT
        9700000 + s.`n`,
        9400000 + ((s.`n` - 1) DIV 3) + 1,
        9500000 + s.`n`,
        9300000 + ((((s.`n` - 1) DIV 3) DIV 25)) + 1,
        CONCAT('规格', ((s.`n` - 1) MOD 3) + 1, ' · ',
               ELT(((s.`n` - 1) MOD 3) + 1, '标准版', '大容量版', '礼盒装')),
        IF(((s.`n` - 1) DIV 3) MOD 25 = 0, 0, 100000),
        0,
        IF(((s.`n` - 1) DIV 3) MOD 25 = 0, 1, 0),
        1
      FROM `_seed_seq` s
     WHERE s.`n` <= 1500;

    DROP TEMPORARY TABLE IF EXISTS `_seed_seq`;
    DROP TEMPORARY TABLE IF EXISTS `_seed_img`;
    DROP TEMPORARY TABLE IF EXISTS `_seed_order`;
    DROP TEMPORARY TABLE IF EXISTS `_seed_d1`;
    DROP TEMPORARY TABLE IF EXISTS `_seed_d2`;
    DROP TEMPORARY TABLE IF EXISTS `_seed_d3`;
    DROP TEMPORARY TABLE IF EXISTS `_seed_d4`;
END$$
DELIMITER ;

CALL `lingmart_auth`.`seed_loadtest`();
DROP PROCEDURE IF EXISTS `lingmart_auth`.`seed_loadtest`;

-- ---------- 播种结果自检 ----------
-- 标签刻意用 ASCII：这段输出会直接打到控制台，而 Windows 控制台多是 GBK，
-- 中文列名会乱码（见 docs/ops-checklist.md §七）。期望值见文件头的"规模"一行。
SELECT 'users'      AS `item`, COUNT(*) AS `rows_expected_40`
  FROM `lingmart_auth`.`user` WHERE `id` BETWEEN 9100001 AND 9100020 OR `id` BETWEEN 9200001 AND 9200020
UNION ALL SELECT 'shops',      COUNT(*) FROM `lingmart_shop`.`shop`          WHERE `id` BETWEEN 9300001 AND 9300020
UNION ALL SELECT 'spu',        COUNT(*) FROM `lingmart_product`.`spu`        WHERE `id` BETWEEN 9400001 AND 9400500
UNION ALL SELECT 'sku',        COUNT(*) FROM `lingmart_product`.`sku`        WHERE `id` BETWEEN 9500001 AND 9501500
UNION ALL SELECT 'sku_images', COUNT(*) FROM `lingmart_product`.`sku_images` WHERE `id` BETWEEN 9600001 AND 9603000
UNION ALL SELECT 'ware_sku',   COUNT(*) FROM `lingmart_ware`.`ware_sku`      WHERE `id` BETWEEN 9700001 AND 9701500
UNION ALL SELECT 'address',    COUNT(*) FROM `lingmart_order`.`user_address` WHERE `id` BETWEEN 9900001 AND 9900020;

-- 抽样看一眼生成的中文标题（读中文需要控制台是 UTF-8：先 chcp 65001）
-- 注意 spu 表没有 price 列，最低价叫 min_price（price 在 sku 表上）
SELECT `id`, `shop_id`, `title`, `min_price` FROM `lingmart_product`.`spu`
 WHERE `id` IN (9400001, 9400008, 9400026, 9400201) ORDER BY `id`;
