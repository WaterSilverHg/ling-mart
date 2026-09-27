-- =============================================================
-- 轻营 · 压测后数据校验（交易链路 + 库存状态机 + Outbox）
--
-- 只读查询，不改任何数据，可随时执行。
--
-- 用法（cmd）：
--   docker exec -i lingmart-mysql mysql -uroot -proot --default-character-set=utf8mb4 ^
--     < scripts\load-test\seed\verify-loadtest.sql
-- 用法（Git Bash）：
--   docker exec -i lingmart-mysql mysql -uroot -proot --default-character-set=utf8mb4 \
--     < scripts/load-test/seed/verify-loadtest.sql
--
-- ⚠️ 不要用 PowerShell 的 `Get-Content x.sql | docker exec ...`：它会把 UTF-8 中文按 GBK
--    重新编码（本项目已实测踩过，mysql 报 ERROR 1064 near '?????,'）。用上面的重定向。
--
-- 【怎么看结果】只看 verdict 列：PASS / FAIL。不需要自己拿 value 和 expected 比对
--   （上一版把"没有阈值的行"写成 expected = -1，反而让人以为是错误——现改为可读标签 + 自判断列）。
--   check5 的 verdict 是 info：它本来就是"预期内会残留"的数据，不是对错判断。
-- =============================================================

-- 【check1】购物车应被清空 —— cart 场景每轮都以「删除」收尾（闭环）
SELECT 'check1_cart_left' AS `item`, COUNT(*) AS `value`, 0 AS `expected`,
       IF(COUNT(*) = 0, 'PASS', 'FAIL') AS `verdict`
  FROM `lingmart_order`.`cart`
 WHERE `buyer_id` BETWEEN 9100001 AND 9100020;

-- 【check2】不应有「待支付」残留 —— order 场景每轮都调用了取消
SELECT 'check2_pending_left' AS `item`, COUNT(*) AS `value`, 0 AS `expected`,
       IF(COUNT(*) = 0, 'PASS', 'FAIL') AS `verdict`
  FROM `lingmart_order`.`order`
 WHERE `buyer_id` BETWEEN 9100001 AND 9100020 AND `order_status` = 0;

-- 【check3】不应有「锁了但既没扣减也没解锁」的库存任务
--   ware_order_task.status：0已锁定 1已扣减 2已解锁 3已退款回补
--   status 停在 0 = 有泄漏的半成品状态（库存被锁死、无人释放），这是最能说明问题的一条
SELECT 'check3_dangling_lock' AS `item`, COUNT(*) AS `value`, 0 AS `expected`,
       IF(COUNT(*) = 0, 'PASS', 'FAIL') AS `verdict`
  FROM `lingmart_ware`.`ware_order_task` t
  JOIN `lingmart_order`.`order` o ON o.`order_sn` = t.`order_sn`
 WHERE o.`buyer_id` BETWEEN 9100001 AND 9100020 AND t.`status` = 0;

-- 【check4】支付事件的 Outbox 应全部投递完成
--   order_paid_event.status：0待发送 1已发送 2发送中
--   只应出现 1；出现 0 或 2 说明 relay 没跑完或有卡死事件（2 超过 2 分钟会被回收）
SELECT 'check4_outbox_status' AS `item`, e.`status` AS `value`, 1 AS `expected`,
       IF(e.`status` = 1, 'PASS', 'FAIL') AS `verdict`, COUNT(*) AS `cnt`
  FROM `lingmart_order`.`order_paid_event` e
  JOIN `lingmart_order`.`order` o ON o.`order_sn` = e.`order_sn`
 WHERE o.`buyer_id` BETWEEN 9100001 AND 9100020
 GROUP BY e.`status`;

-- 【check5】pay 场景留下的「已支付」订单 —— **有值是正常的，不是错误**
--   已支付的订单不能取消（业务上就该如此），所以每条 pay 迭代留一行；
--   它不影响库存（pay 场景优先选不限量 SKU，库存数学全跳过）。压测后由 teardown 清理。
--   这个数字还能反推压测规模：应约等于「pay 权重 × 总迭代数」，明显偏小说明 pay 轮次被中途打断。
SELECT 'check5_paid_orders' AS `item`, COUNT(*) AS `value`, 'any(>0 ok)' AS `expected`,
       'info' AS `verdict`
  FROM `lingmart_order`.`order`
 WHERE `buyer_id` BETWEEN 9100001 AND 9100020 AND `order_status` = 1;

-- 【check6】库存任务状态分布（辅助 + 与 check3 互相印证）
--   正常只应出现 1(已扣减，pay 场景) 与 2(已解锁，order 场景)；出现 0 就是没释放的锁
SELECT 'check6_task_status' AS `item`, t.`status` AS `value`,
       ELT(t.`status` + 1, 'locked(0)', 'deducted(1)', 'unlocked(2)', 'refunded(3)') AS `meaning`,
       IF(t.`status` = 0, 'FAIL', 'ok') AS `verdict`, COUNT(*) AS `cnt`
  FROM `lingmart_ware`.`ware_order_task` t
  JOIN `lingmart_order`.`order` o ON o.`order_sn` = t.`order_sn`
 WHERE o.`buyer_id` BETWEEN 9100001 AND 9100020
 GROUP BY t.`status`;
