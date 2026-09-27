@echo off
REM ============================================================
REM 轻营 · 拉起基础设施（docker compose up -d 会启动全部基础设施容器）
REM   实际包含：Nacos + MySQL + Redis + Elasticsearch + RabbitMQ + Seata + Zipkin + ChromaDB
REM             （Ollama 需 --profile ai 显式启用；只在跑 AI 组件时才需要）
REM 依赖：docker compose（Docker Desktop）；镜像请先执行 pull-images.bat
REM 前置：项目根目录需存在 .env（含 NACOS_AUTH_TOKEN 与 JWT_SECRET，模板见 .env.example）
REM ============================================================
echo [1/1] 启动基础设施（Nacos + MySQL + Redis + ES + RabbitMQ + Seata + Zipkin）...
if not exist "..\.env" (
    echo 警告：未找到 .env 文件，请复制 .env.example 为 .env 并填写 NACOS_AUTH_TOKEN。
)
docker compose up -d
if %errorlevel% neq 0 ( echo 启动失败，请检查 Docker 是否运行 & exit /b 1 )

echo 启动指令已下发，等待容器就绪后访问：
echo   Nacos 控制台     : http://localhost:8848/nacos   （默认账号 nacos / nacos）
echo   MySQL            : localhost:3307  （root / root）
echo   Redis            : localhost:6380  （宿主映射 6380，容器内 6379）
echo   Elasticsearch    : http://localhost:9201
echo   RabbitMQ 管理台  : http://localhost:15672  （guest / guest）
echo   Seata TC         : localhost:8091
echo   Zipkin UI        : http://localhost:9411
echo   ChromaDB         : http://localhost:18000  （仅 AI 组件使用）
echo 提示：MySQL 首次启动会自动执行 sql\ 目录下的建库建表脚本。
