@echo off
REM ============================================================
REM 轻营 · 清空容器数据（危险操作）—— Windows 快捷入口
REM 实际逻辑在 reset-data.sh，本脚本通过 Git Bash 调用。
REM 用法：scripts\reset-data.bat
REM ============================================================
setlocal
where bash >nul 2>nul
if errorlevel 1 (
    echo 未找到 bash（Git Bash）。请用 Git Bash 执行 scripts/reset-data.sh
    exit /b 1
)
bash "%~dp0reset-data.sh"
endlocal
