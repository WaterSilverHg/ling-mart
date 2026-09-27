#!/bin/bash
# ============================================================
# 轻营 · 存量库增量迁移（一键按序执行 sql/upgrade 下的全部脚本）
#
# 用法：bash scripts/migrate.sh           # 需要 docker + 已在运行 lingmart-mysql 容器
#
# 为什么要有这个脚本：
#   1) MySQL 容器的 docker-entrypoint-initdb.d（挂载 ./sql）**只在数据目录为空时执行一次**，
#      老库不会自动补后续版本新增的表/列 —— 典型症状是起服务后才报
#      `Table 'lingmart_order.order_paid_event' doesn't exist`；
#   2) 手工 `Get-Content xxx.sql | docker exec -i ...` 在 PowerShell 下会按系统代码页（GBK）
#      重新编码，UTF-8 的中文注释变成乱码 → mysql 报 `ERROR 1064 ... near '?????,'`。
#      本脚本用 shell 重定向（字节直通）+ --default-character-set=utf8mb4，不受该问题影响。
#
# 幂等性：所有脚本都是 IF NOT EXISTS / MODIFY 形式，重复执行只会有「已存在」类报错，可忽略。
# ============================================================
set -u

CONTAINER="${MYSQL_CONTAINER:-lingmart-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-root}"
CHARSET="utf8mb4"

# 执行顺序有讲究：先补表/列，再补唯一键与索引（最后那个是三段式，需要人工看自检结果）
SCRIPTS=(
    "scripts/migrate-v0506.sql"
    "sql/upgrade/2026-09-03-stock-unlimited.sql"
    "sql/upgrade/2026-09-06-order-paid-outbox.sql"
    "sql/upgrade/2026-09-06-snowflake-id.sql"
    "sql/upgrade/2026-09-06-stock-flow-log.sql"
    "sql/upgrade/2026-09-20-sale-rollback-log.sql"
    "sql/upgrade/2026-09-20-integrity-and-indexes.sql"
)

if ! command -v docker >/dev/null 2>&1; then
    echo "✗ 未找到 docker 命令（需要 Docker Desktop 在运行）"
    exit 1
fi
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "✗ 容器 $CONTAINER 未在运行，请先执行：docker compose up -d"
    exit 1
fi
# 守护进程可达性：Git Bash(MSYS) 下的 docker 客户端有时连不上守护进程（PATH 里的 docker 与 cmd 用的
# 不是同一个 / 未设 DOCKER_HOST），此时所有 `docker exec` 都会失败。提前说清楚，别让人以为是脚本坏了。
if ! docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
    echo "✗ docker 客户端连不上守护进程 —— 本脚本全部依赖 docker exec，无法继续。"
    echo "  处置：① 改用 cmd 执行：cmd /c scripts\\migrate.bat；"
    echo "       ② 或在 Git Bash 里确认 docker 指向 Docker Desktop 的 CLI 并设置 DOCKER_HOST 后重试。"
    exit 1
fi

PASS=0
SKIP=0
FAIL=0
FAILED_ITEMS=""

echo "======== 轻营 · 存量库增量迁移 ========"
echo "容器：$CONTAINER    库账号：$MYSQL_USER"
echo ""
echo "提示：最后一个是三段式脚本（含重复数据自检），本脚本会把三段都执行一遍；"
echo "      若第二段因重复数据失败，请按 docs/ops-checklist.md 第三节人工处理后再单独执行。"
echo ""

for f in "${SCRIPTS[@]}"; do
    if [ ! -f "$f" ]; then
        echo "  - $f（文件不存在，跳过）"
        SKIP=$((SKIP + 1))
        continue
    fi
    out=$(docker exec -i "$CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASS" \
            --default-character-set="$CHARSET" < "$f" 2>&1)
    code=$?
    if [ $code -eq 0 ] && ! echo "$out" | grep -qi "ERROR"; then
        echo "  ✓ $f"
        PASS=$((PASS + 1))
        continue
    fi
    # 「已存在」类报错属于幂等预期（重复执行），不计为失败
    if echo "$out" | grep -qiE "already exists|Duplicate (column|key|entry)"; then
        echo "  - $f（对象已存在，跳过）"
        SKIP=$((SKIP + 1))
    else
        echo "  ✗ $f"
        echo "$out" | grep -i "ERROR" | head -3 | sed 's/^/      /'
        FAILED_ITEMS="$FAILED_ITEMS $f"
        FAIL=$((FAIL + 1))
    fi
done

echo ""
echo "======== 汇总 ========"
echo "成功 $PASS  跳过 $SKIP  失败 $FAIL"
if [ "$FAIL" -gt 0 ]; then
    echo "失败项：$FAILED_ITEMS"
    echo "排查：① 报 'Duplicate entry' → 存量库有重复数据，见 docs/ops-checklist.md 第三节第三段；"
    echo "      ② 报语法/乱码 → 确认用本脚本执行，而不是 PowerShell 的 Get-Content 管道。"
    exit 1
fi

echo ""
echo "下一步：bash scripts/verify-health.sh 看「库结构」一项是否变成 ✓，然后重启业务服务。"
