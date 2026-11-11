@echo off
REM ============================================================
REM LingMart - middleware image fetch script
REM
REM Why this exists:
REM   The Docker daemon on this machine is configured with an Aliyun
REM   accelerator (39h4n4b7.mirror.aliyuncs.com) that now returns 403
REM   for EVERY image, so "docker compose up" fails on any image that
REM   is not already present locally.
REM
REM What it does:
REM   Pulls each image from docker.1panel.live and re-tags it with the
REM   standard name, so docker compose finds it locally and never has
REM   to reach the registry.
REM
REM Idempotent: images whose standard tag already exists are skipped,
REM so this can be re-run safely at any time.
REM
REM NOTE: comments in this file are ASCII on purpose. cmd.exe misparses
REM       UTF-8 batch files on a GBK console (it drops the head of some
REM       Chinese lines and runs the tail as a command). Chinese docs:
REM       docs/ops-checklist.md section 3.
REM ============================================================
setlocal
set MIRROR=docker.1panel.live

call :ensure "%MIRROR%/library/mysql:8.0"                "mysql:8.0"                 "MySQL 8.0"            || goto :fail
call :ensure "%MIRROR%/library/redis:7"                  "redis:7"                   "Redis 7"              || goto :fail
call :ensure "%MIRROR%/nacos/nacos-server:v2.4.3"        "nacos/nacos-server:v2.4.3" "Nacos 2.4.3"          || goto :fail
call :ensure "%MIRROR%/library/elasticsearch:8.15.5"     "elasticsearch:8.15.5"      "Elasticsearch 8.15.5" || goto :fail
call :ensure "%MIRROR%/library/rabbitmq:3.13-management" "rabbitmq:3.13-management"  "RabbitMQ 3.13"        || goto :fail
call :ensure "%MIRROR%/openzipkin/zipkin:3.4"            "openzipkin/zipkin:3.4"     "Zipkin 3.4"           || goto :fail
call :ensure "%MIRROR%/apache/seata-server:2.5.0"        "apache/seata-server:2.5.0" "Seata TC 2.5.0"       || goto :fail
call :ensure "%MIRROR%/chromadb/chroma:latest"           "chromadb/chroma:latest"    "ChromaDB"             || goto :fail
call :ensure "%MIRROR%/prom/prometheus:v2.55.0"          "prom/prometheus:v2.55.0"   "Prometheus 2.55.0"    || goto :fail
call :ensure "%MIRROR%/grafana/grafana:11.2.0"           "grafana/grafana:11.2.0"    "Grafana 11.2.0"       || goto :fail

echo All images ready. Next: scripts\start-infra.bat
echo (Ollama is an optional profile and is not pulled here.)
endlocal
exit /b 0

:fail
echo.
echo Image pull failed. Check network, or change MIRROR= to another reachable registry mirror.
endlocal
exit /b 1

:ensure
REM %1 = source ref, %2 = standard tag, %3 = display name
docker image inspect %~2 >nul 2>&1
if not errorlevel 1 (
  echo     [skip] %~2 already present
  exit /b 0
)
echo     [pull] %~3 ...
docker pull %~1
if errorlevel 1 exit /b 1
docker tag %~1 %~2
exit /b 0
