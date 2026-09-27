#!/bin/bash
# ============================================================
# 轻营 · 一键健康巡检（基础设施 + 核心业务服务）
# 用法：bash scripts/verify-health.sh
# 覆盖：Nacos / MySQL / Redis / ES / RabbitMQ / Seata / Zipkin + 8 个核心服务 /actuator/health
# 不含：lingmart-agent（可选 AI 组件，需外部模型 Key 与 ChromaDB，故不纳入默认巡检）
# 退出码：全部通过 = 0；任一失败 = 1
# 可覆盖：NACOS_ADDR / NACOS_USER / NACOS_PASS（默认 nacos/nacos）
# ============================================================
set -u

NACOS_ADDR="${NACOS_ADDR:-http://localhost:8848}"
NACOS_USER="${NACOS_USER:-nacos}"
NACOS_PASS="${NACOS_PASS:-nacos}"

PASS=0
FAIL=0
SKIP=0
FAILED_ITEMS=""

ok()   { PASS=$((PASS + 1)); echo "  ✓ $1"; }
bad()  { FAIL=$((FAIL + 1)); FAILED_ITEMS="${FAILED_ITEMS}${1}\n"; echo "  ✗ $1"; }
skip() { SKIP=$((SKIP + 1)); echo "  - $1（跳过）"; }

# ---------- 基础设施 ----------
echo "======== 轻营 · 环境健康巡检 ========"
echo ""
echo "[基础设施]"

# 修正 PATH 里的 docker：Windows 的 WindowsApps 里有一堆「应用执行别名」占位程序，名字与真程序相同，
# 在 Git Bash 等非交互 shell 下被调用时静默退出（非零码 + 空输出），表现为「docker 存在但什么都干不了」。
# 若解析到该目录，尝试把 Docker Desktop 的真实 CLI 目录插到 PATH 最前（找不到就保持原状，不影响后续判断）。
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

if ! command -v docker >/dev/null 2>&1; then
    skip "Docker 不可用（基础设施不在本机，请检查 Docker Desktop）"
else
    # Docker 可用性：本块里所有 docker exec 都依赖它。
    # 两类典型故障（都遇到过）：
    #   ① Git Bash 的 PATH 里解析到 WindowsApps 的「应用执行别名」——0 字节占位程序，被非交互 shell
    #      调用时静默退出（非零码 + 空输出），于是所有 exec 类检查失败而 curl 类检查正常；
    #   ② 真 CLI 但连不上守护进程（Docker Desktop 没起 / DOCKER_HOST 不对）。
    # 这里把「解析到的路径」和「命令输出」都打出来，一眼可分。
    DOCKER_BIN=$(command -v docker)
    if DOCKER_SERVER=$(docker version --format '{{.Server.Version}}' 2>&1) && [ -n "$DOCKER_SERVER" ]; then
        ok "Docker     $DOCKER_BIN（Server $DOCKER_SERVER）"
    else
        bad "Docker     不可用：docker=$DOCKER_BIN，输出「${DOCKER_SERVER:-（空）}」"
        case "$DOCKER_BIN" in
            *WindowsApps*)
                echo "      → 解析到的是 Windows 应用执行别名（0 字节占位程序，静默退出），不是真正的 Docker CLI。"
                echo "        处置：改用 cmd / PowerShell 运行本脚本（那边解析到真 CLI），"
                echo "              或把 Docker Desktop 的 bin 目录放到 PATH 更靠前的位置。"
                ;;
            *)
                echo "      → 下面凡是用 docker exec 的检查（MySQL/Redis/Seata/库结构）都会失败。"
                echo "        处置：确认 Docker Desktop 已启动；必要时 export DOCKER_HOST=... 后重试，或改用 cmd。"
                ;;
        esac
    fi

    # Nacos：登录取 token 即视为就绪
    if NACOS_TOKEN=$(curl -s --max-time 4 -X POST "$NACOS_ADDR/nacos/v1/auth/login" \
            --data-urlencode "username=$NACOS_USER" --data-urlencode "password=$NACOS_PASS" \
            | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'); then
        [ -n "$NACOS_TOKEN" ] && ok "Nacos       $NACOS_ADDR" || bad "Nacos       $NACOS_ADDR（登录失败：检查 NACOS_USER/NACOS_PASS）"
    else
        bad "Nacos       $NACOS_ADDR（不可达）"
    fi

    # MySQL：容器内 mysqladmin ping（保留 stderr 首行作为失败原因，别再吞掉）
    if MYSQL_ERR=$(docker exec lingmart-mysql mysqladmin -uroot -proot ping 2>&1); then
        ok "MySQL       容器 lingmart-mysql"
    else
        bad "MySQL       容器 lingmart-mysql（${MYSQL_ERR%%$'\n'*}）"
    fi

    # Redis：容器内 redis-cli ping
    if REDIS_OUT=$(docker exec lingmart-redis redis-cli ping 2>&1) && [ "$REDIS_OUT" = "PONG" ]; then
        ok "Redis       容器 lingmart-redis"
    else
        bad "Redis       容器 lingmart-redis（${REDIS_OUT%%$'\n'*}）"
    fi

    # Elasticsearch：HTTP 9201
    if curl -sf --max-time 4 http://localhost:9201 >/dev/null 2>&1; then
        ok "ES          http://localhost:9201"
    else
        bad "ES          http://localhost:9201"
    fi

    # 存量库结构：MySQL 的 docker-entrypoint-initdb.d 只在数据目录为空时执行一次，
    # 老库不会自动补上后续版本新增的表/唯一键 —— 典型症状就是启动后才报
    # `Table 'lingmart_order.order_paid_event' doesn't exist`。这里提前暴露。
    DB_SQL=$(cat <<'SQL'
SELECT CONCAT(table_schema,'.',table_name) FROM information_schema.tables
 WHERE (table_schema='lingmart_order'   AND table_name IN ('order_paid_event','cart'))
    OR (table_schema='lingmart_ware'    AND table_name IN ('stock_flow_log'))
    OR (table_schema='lingmart_product' AND table_name IN ('es_write_retry','sale_rollback_log'))
    OR (table_schema='lingmart_shop'    AND table_name IN ('shop'))
UNION ALL
SELECT CONCAT(table_schema,'.',table_name,'.',index_name) FROM information_schema.statistics
 WHERE (table_schema='lingmart_shop'  AND table_name='shop' AND index_name='uk_user_id')
    OR (table_schema='lingmart_order' AND table_name='cart' AND index_name='uk_buyer_sku');
SQL
)
    # 走 stdin 而不是 `-e "长 SQL"`：Git Bash(MSYS) 会改写「看起来像路径」的命令行参数，
    # 超长带引号的 SQL 参数也可能被拆坏（症状就是查询结果为空、却看不到任何报错）。
    if DB_META=$(printf '%s\n' "$DB_SQL" | docker exec -i lingmart-mysql mysql -uroot -proot -N -B \
                    --default-character-set=utf8mb4 2>&1); then
        MISSING=""
        for item in lingmart_order.order_paid_event lingmart_ware.stock_flow_log \
                    lingmart_product.es_write_retry lingmart_product.sale_rollback_log \
                    lingmart_shop.shop.uk_user_id lingmart_order.cart.uk_buyer_sku; do
            echo "$DB_META" | grep -qx "$item" || MISSING="$MISSING $item"
        done
        if [ -n "$MISSING" ]; then
            bad "库结构      缺对象：$MISSING（按 docs/ops-checklist.md 第三节补迁移；order 服务会因此启动/支付报错）"
        else
            ok "库结构      关键表与唯一键齐全（order_paid_event / stock_flow_log / es_write_retry / sale_rollback_log / uk_user_id / uk_buyer_sku）"
        fi
    else
        bad "库结构      查询失败（${DB_META%%$'\n'*}）"
    fi

    # ChromaDB：心跳（v2 API，失败再试 v1，兼容旧镜像）
    # 为什么查它：lingmart-agent 启动时 ChromaVectorStore 会**必定**发一次 getCollection 请求
    # （Spring AI 1.1.8 的 afterPropertiesSet 行为，与 initialize-schema 无关），
    # Chroma 没就绪 → agent 启动直接失败。起 agent 前先看这一项。
    if curl -sf --max-time 4 http://localhost:18000/api/v2/heartbeat >/dev/null 2>&1 \
       || curl -sf --max-time 4 http://localhost:18000/api/v1/heartbeat >/dev/null 2>&1; then
        ok "ChromaDB    http://localhost:18000（agent 知识库前置）"
    else
        bad "ChromaDB    http://localhost:18000（未就绪 → lingmart-agent 会启动失败，见 ops-checklist 四-4.6）"
    fi

    # RabbitMQ：管理台 HTTP 15672（200/401/302 均视为服务在线）
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 4 http://localhost:15672/ 2>/dev/null)
    case "$code" in
        200|401|302) ok "RabbitMQ    http://localhost:15672" ;;
        *) bad "RabbitMQ    http://localhost:15672（管理台无响应，$code）" ;;
    esac

    # Seata：容器运行 + 端口映射存在。
    # 用 docker inspect / docker port 而不是 `docker exec ... cat /绝对路径`：
    # Git Bash(MSYS) 会把命令行参数里的 `/seata-server/...` 改写成 Windows 路径，导致 exec 无条件失败。
    SEATA_RUNNING=$(docker inspect -f '{{.State.Running}}' lingmart-seata 2>&1)
    if [ "$SEATA_RUNNING" = "true" ] && docker port lingmart-seata 8091 >/dev/null 2>&1; then
        ok "Seata       lingmart-seata（容器运行中，8091 已映射）"
    else
        bad "Seata       lingmart-seata（${SEATA_RUNNING%%$'\n'*}）"
    fi

    # Zipkin：UI http://localhost:9411（链路追踪）
    # 自 v0.11 起 zipkin 随 `docker compose up -d` 默认启动、ZIPKIN_ENABLED 默认 true（全链路上报开箱可用），
    # 故此处的「未启动」属于真异常而非可选跳过。
    if curl -sf --max-time 4 http://localhost:9411 >/dev/null 2>&1; then
        ok "Zipkin      http://localhost:9411"
    else
        bad "Zipkin      http://localhost:9411（未启动；应随 docker compose up -d 一起起来）"
    fi
fi

# ---------- 业务服务 ----------
echo ""
echo "[业务服务]"
# name:port 列表（网关必须最后检查，其余无先后）
for svc in auth:8000 shop:8100 product:10001 ware:11000 order:9000 search:12000 chat:13000 gateway:88; do
    name="${svc%%:*}"
    port="${svc##*:}"
    if curl -sf --max-time 4 "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"UP"'; then
        ok "$name        :$port"
    else
        bad "$name        :$port（服务未启动或健康检查异常）"
    fi
done

# ---------- 汇总 ----------
echo ""
echo "======== 汇总 ========"
echo "通过 $PASS  失败 $FAIL  跳过 $SKIP"
if [ "$FAIL" -gt 0 ]; then
    echo "失败项："
    echo -e "$FAILED_ITEMS" | sed '/^$/d' | sed 's/^/  - /'
    echo ""
    echo "处置提示："
    echo "  1. 业务服务未启动 → scripts\\start-services.bat（或 IDEA「全部服务」）"
    echo "  2. 基础设施未就绪 → scripts\\start-infra.bat；镜像缺失先 scripts\\pull-images.bat"
    echo "  3. 服务起来又挂 → 看 ./logs/{服务名}.log 首 30 行（Nacos 配置/密码/JDK 版本）"
    echo "  4. 业务 500 → 检查数据库是否缺列（docs/ops-checklist.md §三 迁移）"
    exit 1
fi
echo ""
echo "✅ 全部就绪，可进行端到端冒烟（docs/ops-checklist.md §四）。"
exit 0
