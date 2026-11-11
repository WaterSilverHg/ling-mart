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
#
# content 必须用 curl 的 `content@文件名` 语法（从文件读字节并 URL 编码），
# **不要**写成 content="$(cat "$f")"：
#   Windows 的 Git Bash 会把命令行参数从 UTF-8 转换为系统代码页（GBK）再交给 curl，
#   于是中文注释以 GBK 字节发出、而 Nacos 按 UTF-8 解码 → 存入一堆 U+FFFD 替换字符。
#   该损坏只落在注释上、YAML 仍能解析，所以在服务端完全不报错，极难发现。
#   已实测 `content@file` 在本机 Git Bash 下可逐字节原样存入（md5 一致）。
#
# 另：文件名要用**相对路径**。Windows 版 curl 打不开 MSYS 风格的绝对路径（/c/Users/...），
#     会报 `Required request parameter 'content' ... is not present`，故先 cd 进配置目录。
PUSHED=0
cd "$CONFIG_DIR"
for f in *.yaml *.yml *.json; do
    [ -e "$f" ] || continue
    DATAID="$f"
    EXT="${DATAID##*.}"
    echo "→ 推送配置：$DATAID (group=$GROUP, type=$EXT)"
    RESP=$(curl -s -X POST "$NACOS_ADDR/nacos/v1/cs/configs?accessToken=$TOKEN" \
        --data-urlencode "dataId=$DATAID" \
        --data-urlencode "group=$GROUP" \
        --data-urlencode "type=$EXT" \
        --data-urlencode "content@$f")
    if [ "$RESP" != "true" ]; then
        echo "✗ 推送失败：$DATAID 返回 $RESP"
        exit 1
    fi
    PUSHED=$((PUSHED + 1))
done

echo "完成：共推送 $PUSHED 个配置到 Nacos（$NACOS_ADDR）"
echo "提示：先推送配置再启动业务服务；否则服务启动时 Nacos 共享配置缺失，注册中心配置不生效。"
