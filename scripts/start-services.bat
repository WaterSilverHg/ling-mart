@echo off
REM ============================================================
REM 轻营 · 启动全部业务服务（agent + auth + shop + product + ware + order + search
REM                    + chat + gateway，共 9 个，各开一个独立窗口）
REM 前置：① 先运行 build.bat 完成打包；② 先运行 start-infra.bat 拉起中间件
REM 注意：lingmart-agent 已纳入必要模块（v0.30 起），它有三条额外前置，缺任一条都会启动失败：
REM       ① .env 里的 DEEPSEEK_API_KEY（对话）与 DASHSCOPE_API_KEY（知识库嵌入）
REM       ② ChromaDB 容器 —— Spring AI 的 ChromaVectorStore 在 bean 构造期就会请求它
REM       ③ 审计库 lingmart_agent（首次部署执行 sql\lingmart_agent.sql）
REM       缺 ①/② 会直接起不来；缺 ③ 能起来但审计静默失效（跑 verify-health.sh 可提前发现）
REM ============================================================
setlocal
set JAVA_HOME=C:\Users\p6LHG\Desktop\java\Java\jdk-21.0.12
set PATH=%JAVA_HOME%\bin;%PATH%

if not exist "..\lingmart-auth\target\lingmart-auth-0.0.1-SNAPSHOT.jar" (
    echo 未找到 auth 产物，请先在项目根目录运行 scripts\build.bat 构建。
    exit /b 1
)
if not exist "..\lingmart-agent\target\lingmart-agent-0.0.1-SNAPSHOT.jar" (
    echo 未找到 agent 产物，请先在项目根目录运行 scripts\build.bat 构建。
    exit /b 1
)

echo 启动 lingmart-auth（端口 8000）...
start "lingmart-auth" cmd /k "java -jar ..\lingmart-auth\target\lingmart-auth-0.0.1-SNAPSHOT.jar"

echo 启动 lingmart-shop（端口 8100）...
start "lingmart-shop" cmd /k "java -jar ..\lingmart-shop\target\lingmart-shop-0.0.1-SNAPSHOT.jar"

echo 启动 lingmart-product（端口 10001）...
start "lingmart-product" cmd /k "java -jar ..\lingmart-product\target\lingmart-product-0.0.1-SNAPSHOT.jar"

echo 启动 lingmart-ware（端口 11000）...
start "lingmart-ware" cmd /k "java -jar ..\lingmart-ware\target\lingmart-ware-0.0.1-SNAPSHOT.jar"

echo 启动 lingmart-order（端口 9000）...
start "lingmart-order" cmd /k "java -jar ..\lingmart-order\target\lingmart-order-0.0.1-SNAPSHOT.jar"

echo 启动 lingmart-search（端口 12000）...
start "lingmart-search" cmd /k "java -jar ..\lingmart-search\target\lingmart-search-0.0.1-SNAPSHOT.jar"

echo 启动 lingmart-chat（端口 13000）...
start "lingmart-chat" cmd /k "java -jar ..\lingmart-chat\target\lingmart-chat-0.0.1-SNAPSHOT.jar"

echo 启动 lingmart-agent（端口 14000，AI 浮窗；需 Key + ChromaDB + 审计库）...
start "lingmart-agent" cmd /k "java -jar ..\lingmart-agent\target\lingmart-agent-0.0.1-SNAPSHOT.jar"

echo 启动 lingmart-gateway（端口 88）...
start "lingmart-gateway" cmd /k "java -jar ..\lingmart-gateway\target\lingmart-gateway-0.0.1-SNAPSHOT.jar"

echo 服务窗口已打开，请等待各服务启动完成后联调（网关入口 http://localhost:88）。
echo 统一 OpenAPI 文档：http://localhost:88/v3/api-docs/merged （Apifox 一键导入地址）
endlocal
