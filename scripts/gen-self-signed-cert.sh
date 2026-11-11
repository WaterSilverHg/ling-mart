#!/bin/bash
# ============================================================
# 生成本地自签 TLS 证书（仅供 docker-compose.prod.yml 的 tls profile 本地验证）
#
# 用法：bash scripts/gen-self-signed-cert.sh
# 产物：docker/nginx/certs/server.crt / server.key（已在 .gitignore 中，勿提交）
# 前置：openssl（Git Bash 自带）
#
# ⚠️ 自签证书只用于本地验证；生产请使用正式证书或由云 LB 终止 TLS。
# ============================================================
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/docker/nginx/certs"

command -v openssl >/dev/null 2>&1 || { echo "✗ 未找到 openssl"; exit 1; }

mkdir -p "$DIR"

openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
    -keyout "$DIR/server.key" \
    -out "$DIR/server.crt" \
    -subj "/C=CN/ST=Local/L=Local/O=LingMart/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

chmod 600 "$DIR/server.key"

echo "✓ 已生成自签证书："
echo "   $DIR/server.crt"
echo "   $DIR/server.key"
echo
echo "启动 TLS 入口："
echo "   docker compose -f docker-compose.yml -f docker-compose.business.yml -f docker-compose.prod.yml --profile tls up -d"
echo "访问：https://localhost/（浏览器会提示自签证书不安全，属预期）"
