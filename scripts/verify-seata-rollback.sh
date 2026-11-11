#!/bin/bash
# ============================================================
# 轻营 · Seata AT 全局回滚验证（故障注入）
#
# 目的：证明「order 下单成功锁库存 → 全局事务随后失败」时，ware 的锁定真的被 undo_log 撤销。
#       只看接口通不通是验证不了这件事的——必须做故障注入，再查库对比。
#
# 前置：
#   1. 全部服务已启动，且 order 以故障注入开关启动：
#        LINGMART_ORDER_FAULT_INJECTION_ENABLED=true
#      （IDEA：Run Configuration → Environment variables；命令行：直接 export 后 java -jar）
#   2. MySQL 容器 lingmart-mysql 在运行
#   3. 已播种数据（scripts/load-test/seed），默认买家 13900000001 / admin123
#   4. 知识：开关默认关闭，关闭时带 X-Fault-Inject 头也不会有任何效果（本脚本会明确提示）
#
# 用法：
#   bash scripts/verify-seata-rollback.sh              # 自动挑一个限量且有库存的 SKU
#   bash scripts/verify-seata-rollback.sh 1234567890   # 指定 SKU
#
# 环境变量：BASE_URL / BUYER_PHONE / PASSWORD / SKU_ID
#
# 退出码：0 = 回滚验证通过；1 = 失败（有残留）；2 = 前置不满足（开关没开/数据不足）
# ============================================================
set -u

BASE_URL="${BASE_URL:-http://localhost:88/api}"
BUYER_PHONE="${BUYER_PHONE:-13900000001}"
PASSWORD="${PASSWORD:-admin123}"
CONTAINER="${MYSQL_CONTAINER:-lingmart-mysql}"
SKU_ID="${1:-${SKU_ID:-}}"

# ---- Git Bash 下的 docker 占位程序修正（与 verify-health.sh 同口径）----
case "$(command -v docker 2>/dev/null)" in
    *WindowsApps*)
        for _d in "/c/Program Files/Docker/Docker/resources/bin" "${LOCALAPPDATA:-}/Docker/bin"; do
            if [ -n "$_d" ] && { [ -x "$_d/docker.exe" ] || [ -x "$_d/docker" ]; }; then
                export PATH="$_d:$PATH"
                echo "（提示：PATH 里的 docker 是 WindowsApps 占位程序，已改用 $_d 下的真实 CLI）"
                break
            fi
        done
        ;;
esac

fail() { echo "✗ $1"; exit "${2:-1}"; }
ok()   { echo "✓ $1"; }

mysql_q() {
    docker exec -i "$CONTAINER" mysql -uroot -proot -N -B --default-character-set=utf8mb4 -e "$1" 2>/dev/null
}

echo "======== 轻营 · Seata AT 回滚验证 ========"
echo

# ---------- 前置检查 ----------
command -v curl >/dev/null 2>&1 || fail "未找到 curl"
command -v docker >/dev/null 2>&1 || fail "未找到 docker（需要 Docker Desktop 在运行）"
docker ps --format '{{.Names}}' | grep -qx "$CONTAINER" || fail "容器 $CONTAINER 未在运行，请先 docker compose up -d"
docker version --format '{{.Server.Version}}' >/dev/null 2>&1 || fail "docker 客户端连不上守护进程（Git Bash 下请改用 cmd 或修正 PATH）"

if ! curl -sf --max-time 4 "http://localhost:14000/actuator/health" >/dev/null 2>&1; then
    : # agent 与本验证无关，不检查
fi
curl -sf --max-time 5 "http://localhost:9000/actuator/health" >/dev/null 2>&1 \
    || fail "order 服务(:9000) 未就绪，请先启动全部服务" 2

# ---------- 挑 SKU ----------
if [ -z "$SKU_ID" ]; then
    SKU_ID=$(mysql_q "SELECT sku_id FROM lingmart_ware.ware_sku WHERE unlimited=0 AND stock > 0 AND locked_stock = 0 ORDER BY sku_id LIMIT 1;" | tr -d '\r')
    [ -n "$SKU_ID" ] || fail "找不到可用于验证的 SKU（需要 unlimited=0 且 stock>0）——请先跑 scripts/load-test/seed 播种" 2
fi
echo "使用 SKU：$SKU_ID"

# ---------- 快照 ----------
snapshot() {
    SYNC=$(mysql_q "SELECT CONCAT(stock, ':', locked_stock) FROM lingmart_ware.ware_sku WHERE sku_id=$SKU_ID;" | tr -d '\r')
    TASKS=$(mysql_q "SELECT COUNT(*) FROM lingmart_ware.ware_order_task WHERE sku_id=$SKU_ID;" | tr -d '\r')
    UNDO=$(mysql_q "SELECT COUNT(*) FROM lingmart_ware.undo_log;" | tr -d '\r')
}
snapshot
BEFORE_SYNC="$SYNC"; BEFORE_TASKS="$TASKS"; BEFORE_UNDO="$UNDO"
echo "调用前：stock:locked_stock=$BEFORE_SYNC  ware_order_task=$BEFORE_TASKS  undo_log=$BEFORE_UNDO"
[ -n "$BEFORE_SYNC" ] || fail "读取 ware_sku 失败（SKU 不存在？）" 2

# ---------- 登录 ----------
LOGIN=$(curl -s --max-time 10 -X POST "$BASE_URL/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"phone\":\"$BUYER_PHONE\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || fail "登录失败（$BUYER_PHONE）。请确认账号已播种、密码正确。响应：$(echo "$LOGIN" | head -c 200)" 2

# ---------- 故障注入下单 ----------
IDEM="seata-rollback-$RANDOM$RANDOM$RANDOM"
RESP=$(curl -s --max-time 30 -X POST "$BASE_URL/order" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IDEM" \
    -H "X-Fault-Inject: after-lock" \
    -d "{\"items\":[{\"skuId\":\"$SKU_ID\",\"num\":1}],\"buyerRemark\":\"seata-rollback-verify\"}")

echo "下单响应：$(echo "$RESP" | head -c 300)"
echo "$RESP" | grep -q '"code":200' && fail \
    "下单居然成功了——说明 order 服务没有开启故障注入。请以 LINGMART_ORDER_FAULT_INJECTION_ENABLED=true 重启 order 后重试。" 2

# 等 Seata 二阶段回滚完成（异步）
sleep 3

# ---------- 对比 ----------
snapshot
AFTER_SYNC="$SYNC"; AFTER_TASKS="$TASKS"; AFTER_UNDO="$UNDO"
echo "调用后：stock:locked_stock=$AFTER_SYNC  ware_order_task=$AFTER_TASKS  undo_log=$AFTER_UNDO"
echo

PASS=1
if [ "$BEFORE_SYNC" = "$AFTER_SYNC" ]; then
    ok "库存锁定已回滚（locked_stock 回到调用前）"
else
    echo "✗ 库存残留：$BEFORE_SYNC → $AFTER_SYNC（期望不变）"; PASS=0
fi
if [ "$BEFORE_TASKS" = "$AFTER_TASKS" ]; then
    ok "ware_order_task 已回滚（行数回到调用前）"
else
    echo "✗ 任务残留：$BEFORE_TASKS → $AFTER_TASKS（期望不变）"; PASS=0
fi
if [ "$AFTER_UNDO" -le "$BEFORE_UNDO" ] 2>/dev/null; then
    ok "undo_log 无残留（回滚成功后应被清理）"
else
    echo "✗ undo_log 有残留：$BEFORE_UNDO → $AFTER_UNDO"; PASS=0
fi

echo
if [ "$PASS" = "1" ]; then
    echo "======== 结论：PASS —— Seata 全局回滚生效 ========"
    echo "补充取证（可选）：确认被调方确实注册成分支："
    echo "  grep -c 'org\\.apache\\.seata\\.rm\\.datasource' logs/lingmart-ware.log"
    echo "  （>0 表示 ware 的 AT 分支已注册；为 0 说明 XID 又没传过去）"
    exit 0
else
    echo "======== 结论：FAIL —— 存在未回滚的残留 ========"
    echo "排查方向："
    echo "  1) order/ware 日志里是否有 org.apache.seata 的 rollback 失败；"
    echo "  2) ware 是否真的成为分支（grep rm.datasource logs/lingmart-ware.log）；"
    echo "  3) docker logs lingmart-seata | tail -100 看 TC 侧回滚结果；"
    echo "  4) 确认四个库都有 undo_log 表（scripts/verify-health.sh 的「库结构」项）。"
    exit 1
fi
