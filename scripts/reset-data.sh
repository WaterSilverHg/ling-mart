#!/bin/bash
# ============================================================
# 轻营 · 清空容器数据并重建（危险操作，慎用！）
# 将删除：MySQL 全部业务库 / Redis / Nacos 配置 / Seata 会话 / ES 数据 / 店铺上传图片
# 适用场景：中间件数据脏了 / 各服务大面积"系统繁忙" / 想从干净环境重新联调。
# 用法（Git Bash / WSL）：scripts/reset-data.sh
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/.."

echo "============================================================"
echo " 轻营 · 重置容器数据（危险操作）"
echo " 将删除：MySQL 业务库 / Redis / Nacos 配置 / Seata / ES / 上传图片"
echo "============================================================"
read -rp "确认清空？输入 yes 继续：" CONFIRM
[ "$CONFIRM" = "yes" ] || { echo "已取消"; exit 1; }

# 1. 停止并删除所有容器 + 数据卷（含 compose 中的业务服务及其 volume）
echo "→ 停止并清理容器与数据卷..."
docker compose -f docker-compose.yml -f docker-compose.business.yml down -v

# 2. 重新拉起基础设施（MySQL 首次启动自动执行 sql/ 建库建表）
echo "→ 重新启动基础设施..."
docker compose -f docker-compose.yml up -d

# 3. 等待 MySQL / Nacos 就绪
echo "→ 等待基础设施就绪（最多 60s）..."
MYSQL_OK=""
NACOS_OK=""
for _ in $(seq 1 30); do
  if [ -z "$MYSQL_OK" ] && docker exec lingmart-mysql mysql -uroot -proot -e "SELECT 1" >/dev/null 2>&1; then
    echo "  ✓ MySQL 就绪"; MYSQL_OK=1
  fi
  if [ -z "$NACOS_OK" ] && curl -sf -X POST "http://localhost:8848/nacos/v1/auth/login" \
      --data-urlencode "username=nacos" --data-urlencode "password=nacos" >/dev/null 2>&1; then
    echo "  ✓ Nacos 就绪"; NACOS_OK=1
  fi
  [ -n "$MYSQL_OK" ] && [ -n "$NACOS_OK" ] && break
  sleep 2
done

# 4. 推送 Nacos 配置（共享配置 + Sentinel 规则；数据卷清空后必须重推）
echo "→ 推送 Nacos 配置..."
bash scripts/push-nacos-config.sh

echo ""
echo "✅ 数据已清空并重建。请按顺序启动业务服务："
echo "   本地启动：IDEA 或 scripts/start-services.bat（先 mvn package）"
echo "   容器启动：docker compose -f docker-compose.yml -f docker-compose.business.yml up -d --build"
echo ""
echo "⚠️ 注意："
echo "   · Nacos 管理员密码已重置为默认 nacos / nacos"
echo "   · 若已有业务表索引变更（如 order 幂等键联合索引），MySQL 重新初始化会自动带上"
