@echo off
REM ============================================================
REM 轻营 · 停止业务服务（仅结束启动命令行中包含 lingmart 的 java 进程，
REM      不会误杀其他 Java 程序）
REM ============================================================
echo 正在停止 lingmart 相关 java 进程...
powershell -NoProfile -Command ^
  "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*lingmart*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Output ('已停止 PID ' + $_.ProcessId) }"
echo 完成。如需停止中间件容器，可执行：docker compose down
