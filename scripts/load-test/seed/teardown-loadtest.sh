#!/bin/bash
# ============================================================
# 轻营 · 压测数据清理（Git Bash / Linux）
#
# 用法：bash scripts/load-test/seed/teardown-loadtest.sh
#
# 只删 seed-loadtest.sql 那批**固定 ID 区间**的数据，不碰真实数据：
#   买家 9100001~9100020  卖家 9200001~9200020  店铺 9300001~9300020
#   SPU 9400001~9400500  SKU 9500001~9501500  图片 9600001~9603000  库存 9700001~9701500
# 管理员账号（13800000000）不在任何区间内，不受影响。
#
# ⚠️ SQL 删库**不会**带走 ES 文档（reconcileEsIndex 只补不删）。残留文档会让已删商品
#    仍能被搜到（点进去 404），所以第二步必须跑 post-seed.mjs --clean。
# ============================================================
set -u

CONTAINER="${MYSQL_CONTAINER:-lingmart-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-root}"
CHARSET="utf8mb4"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SQL="$SCRIPT_DIR/teardown-loadtest.sql"

cd "$ROOT"

echo "======== 轻营 · 压测数据清理 ========"
echo "容器：$CONTAINER    库账号：$MYSQL_USER"
echo ""

if [ ! -f "$SQL" ]; then
    echo "✗ 找不到 $SQL"
    exit 1
fi

# WindowsApps 占位程序识别（与 scripts/verify-health.sh 同一处理）
case "$(command -v docker 2>/dev/null)" in
    *WindowsApps*)
        for _d in "/c/Program Files/Docker/Docker/resources/bin"; do
            if [ -x "$_d/docker.exe" ] || [ -x "$_d/docker" ]; then
                export PATH="$_d:$PATH"
                echo "（提示：PATH 里的 docker 是 WindowsApps 占位程序，已改用 $_d 下的真实 CLI）"
                break
            fi
        done
        ;;
esac

if ! command -v docker >/dev/null 2>&1; then
    echo "✗ 未找到 docker 命令（需要 Docker Desktop 在运行）"
    exit 1
fi
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "✗ 容器 $CONTAINER 未在运行，请先执行：docker compose up -d"
    exit 1
fi
if ! docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
    echo "✗ docker 客户端连不上守护进程，无法继续。"
    echo "  处置：改用 cmd 执行：cmd /c scripts\\load-test\\seed\\teardown-loadtest.bat"
    exit 1
fi

# ---------- ① 删 MySQL 行 ----------
echo "[1/2] 删除 MySQL 中的压测数据 ..."
out=$(docker exec -i "$CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASS" \
        --default-character-set="$CHARSET" < "$SQL" 2>&1)
code=$?

echo "$out"

if [ $code -ne 0 ] || echo "$out" | grep -qi "ERROR"; then
    echo ""
    echo "✗ 清理失败（上面的 ERROR 行是原因）"
    exit 1
fi

# ---------- ② 删 ES 文档 ----------
echo ""
echo "[2/2] 删除 ES 中的压测文档 ..."
if ! command -v node >/dev/null 2>&1; then
    echo "  - 未找到 node，跳过。手工清理："
    echo "    curl -X POST 'http://localhost:9201/lingmart_product/_delete_by_query?refresh=true' \\"
    echo "         -H 'Content-Type: application/json' \\"
    echo "         -d '{\"query\":{\"range\":{\"shopId\":{\"gte\":9300001,\"lte\":9300020}}}}'"
    exit 0
fi
node "$SCRIPT_DIR/post-seed.mjs" --clean || {
    echo "  ✗ ES 清理失败：MySQL 已干净，但搜索可能仍有残留命中"
    exit 1
}

echo ""
echo "完成。验证：curl 'http://localhost:88/api/search/shops?keyword=手机' 应返回空"
