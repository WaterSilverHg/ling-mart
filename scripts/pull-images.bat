@echo off
REM ============================================================
REM 轻营 · 中间件镜像拉取脚本
REM
REM 背景：本机 Docker 配置的阿里云加速器（39h4n4b7.mirror.aliyuncs.com）
REM       已失效（返回 403），直接 docker compose up 会拉取失败。
REM 方案：本脚本改用可用镜像源 docker.1panel.live 拉取并重打标准标签，
REM       之后 docker compose 会直接使用本地镜像，不再联网拉取。
REM
REM 若镜像已存在、或加速器已修复，可跳过本脚本直接运行 start-infra.bat。
REM ============================================================
setlocal
set MIRROR=docker.1panel.live

echo [1/6] 拉取 MySQL 8.0 ...
docker pull %MIRROR%/library/mysql:8.0
if %errorlevel% neq 0 ( echo 失败：MySQL 拉取失败 & exit /b 1 )
docker tag %MIRROR%/library/mysql:8.0 mysql:8.0

echo [2/6] 拉取 Redis 7 ...
docker pull %MIRROR%/library/redis:7
if %errorlevel% neq 0 ( echo 失败：Redis 拉取失败 & exit /b 1 )
docker tag %MIRROR%/library/redis:7 redis:7

echo [3/6] 拉取 Nacos 2.4.3 ...
docker pull %MIRROR%/nacos/nacos-server:v2.4.3
if %errorlevel% neq 0 ( echo 失败：Nacos 拉取失败 & exit /b 1 )
docker tag %MIRROR%/nacos/nacos-server:v2.4.3 nacos/nacos-server:v2.4.3

echo [4/6] 拉取 Elasticsearch 8.15.5 ...
docker pull %MIRROR%/library/elasticsearch:8.15.5
if %errorlevel% neq 0 ( echo 失败：ES 拉取失败 & exit /b 1 )
docker tag %MIRROR%/library/elasticsearch:8.15.5 elasticsearch:8.15.5

echo [5/6] 拉取 RabbitMQ 3.13（含管理台）...
docker pull %MIRROR%/library/rabbitmq:3.13-management
if %errorlevel% neq 0 ( echo 失败：RabbitMQ 拉取失败 & exit /b 1 )
docker tag %MIRROR%/library/rabbitmq:3.13-management rabbitmq:3.13-management

echo [6/6] 拉取 Seata TC 2.5.0 ...
docker pull %MIRROR%/apache/seata-server:2.5.0
if %errorlevel% neq 0 ( echo 失败：Seata 拉取失败 & exit /b 1 )
docker tag %MIRROR%/apache/seata-server:2.5.0 apache/seata-server:2.5.0

echo 镜像全部就绪，可运行 start-infra.bat 启动基础设施。
endlocal
