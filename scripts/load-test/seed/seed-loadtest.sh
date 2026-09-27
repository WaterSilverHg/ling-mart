#!/bin/bash
# ============================================================
# 轻营 · 压测数据播种（Git Bash / Linux）
#
# 用法：bash scripts/load-test/seed/seed-loadtest.sh
#
# 两步：
#   ① 在 lingmart-mysql 容器里执行 seed-loadtest.sql（用户+店铺+商品+库存）
#   ② 跑 post-seed.mjs 把商品写进 ES，并做一次搜索冒烟
#
# ⚠️ Git Bash(MSYS) 的坑（本项目已实测踩过，脚本里做了处理）：
#   1. PATH 里的 docker 可能是 WindowsApps 的「应用执行别名」占位程序，
#      被非交互 shell 调用时静默退出（非零码 + 空输出）—— 下面会探测并尝试修正；
#   2. 不要用 `mysql -e "长 SQL"`：MSYS 会把「看起来像路径」的参数改写掉。本脚本用
#      `< 文件` 走 stdin（字节直通）+ --default-character-set=utf8mb4，不受影响。
#   若本脚本在你的环境下仍报 docker 不可用，请改用 cmd 版：
#      cmd /c scripts\load-test\seed\seed-loadtest.bat
# ============================================================
set -u

CONTAINER="${MYSQL_CONTAINER:-lingmart-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-root}"
CHARSET="utf8mb4"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SQL="$SCRIPT_DIR/seed-loadtest.sql"

cd "$ROOT"

echo "======== 轻营 · 压测数据播种 ========"
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
    echo "✗ docker 客户端连不上守护进程 —— 本脚本依赖 docker exec，无法继续。"
    echo "  处置：① 改用 cmd 执行：cmd /c scripts\\load-test\\seed\\seed-loadtest.bat；"
    echo "       ② 或在 Git Bash 里确认 docker 指向 Docker Desktop 的 CLI 后重试。"
    exit 1
fi

# ---------- ① 播种 SQL ----------
echo "[1/2] 执行 seed-loadtest.sql ..."
out=$(docker exec -i "$CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASS" \
        --default-character-set="$CHARSET" < "$SQL" 2>&1)
code=$?

echo "$out"

if [ $code -ne 0 ] || echo "$out" | grep -qiE "ERROR|SIGNAL|ABORTED"; then
    echo ""
    echo "✗ 播种失败。常见原因："
    echo "  · 报错行号指向 CALL 那一句，而不是过程体里真正出错的那条语句 —— 看错误内容，别看行号"
    echo "  · 报 'admin user ... not found' → 先启动 lingmart-auth"
    echo "    （AdminUserInitializer 会在启动时创建 13800000000 / admin123）"
    echo "  · 报 'Illegal mix of collations' → 临时表列与正式表列的 collation 不一致；"
    echo "    用 CREATE ... AS SELECT 建临时表可让列定义与正式表同源（见 SQL 内注释）"
    echo "  · 报表/列不存在 → 先跑 scripts/migrate.sh 补齐迁移（或 verify-health.sh 看「库结构」）"
    exit 1
fi

# ---------- ② 写进 ES + 冒烟 ----------
echo ""
echo "[2/2] 把商品写进 ES ..."
if ! command -v node >/dev/null 2>&1; then
    echo "  - 未找到 node，跳过。两条替代路径："
    echo "    a) 装 Node 后重跑：node scripts/load-test/seed/post-seed.mjs"
    echo "    b) 什么都不做，等最多 5 分钟：reconcileEsIndex 定时任务会自动补缺失的 ES 文档"
    echo "       （在此之前，搜索接口对这批数据返回空是正常的）"
    exit 0
fi
node "$SCRIPT_DIR/post-seed.mjs" || {
    echo "  ✗ post-seed 失败：数据已在 MySQL，但搜索可能仍为空"
    exit 1
}

echo ""
echo "完成。下一步：k6 run scripts/load-test/lingmart-core.k6.js"
