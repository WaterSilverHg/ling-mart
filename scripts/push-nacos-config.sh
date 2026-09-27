#!/bin/bash
# ============================================================
# 轻营 · 一键推送 Nacos 配置（共享配置 + Sentinel 规则）
# 前置：Nacos 已启动（docker compose up -d nacos）
# 用法：scripts/push-nacos-config.sh
# 可覆盖：NACOS_ADDR / NACOS_USER / NACOS_PASS / GROUP（默认 nacos/nacos/DEFAULT_GROUP）
# ============================================================
set -euo pipefail

NACOS_ADDR="${NACOS_ADDR:-http://localhost:8848}"
NACOS_USER="${NACOS_USER:-nacos}"
NACOS_PASS="${NACOS_PASS:-nacos}"
GROUP="${GROUP:-DEFAULT_GROUP}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/nacos-config"

# 1. 登录获取 token（Nacos 开启鉴权后必需）
TOKEN=$(curl -s -X POST "$NACOS_ADDR/nacos/v1/auth/login" \
    --data-urlencode "username=$NACOS_USER" \
    --data-urlencode "password=$NACOS_PASS" \
    | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then
    echo "✗ Nacos 登录失败，请检查 NACOS_USER/NACOS_PASS（默认 nacos/nacos）"
    exit 1
fi

# 2. 逐文件推送（dataId=文件名，type 按扩展名）
# 注意：content 用 "$(cat "$f")" 传值而非 curl 的 name@file 语法——
#      后者在 Windows Git Bash 的 curl 上不兼容（会报 content 参数缺失）
PUSHED=0
for f in "$CONFIG_DIR"/*.yaml "$CONFIG_DIR"/*.yml "$CONFIG_DIR"/*.json; do
    [ -e "$f" ] || continue
    DATAID=$(basename "$f")
    EXT="${DATAID##*.}"
    CONTENT=$(cat "$f")
    echo "→ 推送配置：$DATAID (group=$GROUP, type=$EXT)"
    curl -s -X POST "$NACOS_ADDR/nacos/v1/cs/configs?accessToken=$TOKEN" \
        --data-urlencode "dataId=$DATAID" \
        --data-urlencode "group=$GROUP" \
        --data-urlencode "type=$EXT" \
        --data-urlencode "content=$CONTENT"
    echo
    PUSHED=$((PUSHED + 1))
done

echo "完成：共推送 $PUSHED 个配置到 Nacos（$NACOS_ADDR）"
echo "提示：先推送配置再启动业务服务；否则服务启动时 Nacos 共享配置缺失，注册中心配置不生效。"
