@echo off
REM ============================================================
REM 轻营 · 一键构建脚本（使用 JDK 21 编译打包全部模块）
REM 用法：双击运行，或在 cmd 中执行  scripts\build.bat
REM ============================================================
setlocal

REM 修改为本机 JDK 21 的安装路径（若路径不符请自行调整）
set JAVA_HOME=C:\Users\p6LHG\Desktop\java\Java\jdk-21.0.12
set PATH=%JAVA_HOME%\bin;%PATH%

echo [构建] 开始编译 lingmart 工程（JDK %JAVA_HOME%）...
REM 加大 Maven 堆内存，避免 repackage 大 jar 时被静默终止
set MAVEN_OPTS=-Xmx1024m
call mvn -B -DskipTests clean package
if %errorlevel% neq 0 (
    echo [构建] 失败，请检查上方日志。
    exit /b 1
)

echo [构建] 成功！产物如下（各模块 target\ 下，同名 -0.0.1-SNAPSHOT.jar）：
echo   lingmart-common（库，无 fat jar）
echo   lingmart-auth     lingmart-shop      lingmart-product   lingmart-ware
echo   lingmart-order    lingmart-search    lingmart-chat      lingmart-agent
echo   lingmart-gateway
endlocal
