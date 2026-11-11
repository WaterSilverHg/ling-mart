@echo off
REM ============================================================
REM LingMart - incremental DB migration for existing databases.
REM
REM Usage:  cmd /c scripts\migrate.bat
REM
REM NOTE: this file is ASCII-ONLY BY DESIGN.
REM   cmd.exe parses .bat files using the system ANSI code page (GBK on
REM   Chinese Windows). A UTF-8 .bat containing Chinese text gets mis-parsed:
REM   multibyte sequences swallow the following ASCII characters, which can
REM   even eat the leading "echo" keyword and turn the line into a bogus
REM   command. Keep all Chinese explanations in docs/ops-checklist.md
REM   (Markdown is UTF-8 and read by editors, not by cmd).
REM
REM Why not "Get-Content x.sql | docker exec ..." in PowerShell?
REM   That re-encodes UTF-8 Chinese comments to GBK, mysql then fails with
REM   `ERROR 1064 ... near '?????,'`. This script uses cmd redirection
REM   (raw bytes) plus --default-character-set=utf8mb4, so it is unaffected.
REM ============================================================
setlocal
set CONTAINER=lingmart-mysql
set FULLPATH=%~dp0

cd /d "%FULLPATH%.."

echo ==== LingMart DB migration ====
echo.

REM Reachability probe: the whole script depends on "docker exec" working HERE.
docker exec %CONTAINER% mysql -uroot -proot -e "SELECT 1" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] "docker exec %CONTAINER%" failed.
    echo         1^) is Docker Desktop running and the container up?  docker compose up -d
    echo         2^) running this from PowerShell may break pipes - use cmd:
    echo            cmd /c scripts\migrate.bat
    exit /b 1
)

set OK=0
set SKIP=0

call :run "scripts\migrate-v0506.sql"
call :run "sql\upgrade\2026-09-03-stock-unlimited.sql"
call :run "sql\upgrade\2026-09-06-order-paid-outbox.sql"
call :run "sql\upgrade\2026-09-06-snowflake-id.sql"
call :run "sql\upgrade\2026-09-06-stock-flow-log.sql"
call :run "sql\upgrade\2026-09-20-sale-rollback-log.sql"
REM 2026-10-03 is NOT idempotent (plain ADD COLUMN): a rerun reports
REM "Duplicate column", which :run treats as already-applied and skips.
call :run "sql\upgrade\2026-10-03-refund-and-shipping.sql"
REM three-stage script, must stay LAST (part 2 may fail on duplicate rows).
call :run "sql\upgrade\2026-09-20-integrity-and-indexes.sql"

echo.
echo ==== Summary ====
echo Applied: %OK%    Skipped-or-failed: %SKIP%
echo.
echo Next: run scripts\verify-health.sh and check the "schema" line, then restart services.
echo       If something was SKIPPED because of duplicate rows, see
echo       docs\ops-checklist.md section 3 (third part) for manual cleanup.
exit /b 0

:run
if not exist "%~1" (
    echo   MISS %~1
    set /a SKIP+=1
    exit /b 0
)
set OUTFILE=%TEMP%\lingmart-migrate.out
docker exec -i %CONTAINER% mysql -uroot -proot --default-character-set=utf8mb4 < "%~1" > "%OUTFILE%" 2>&1
if errorlevel 1 (
    echo   FAIL %~1   ^(reason below^)
    findstr /i /c:"ERROR" "%OUTFILE%"
    echo        - "already exists" ^= harmless, already applied
    echo        - "Duplicate entry" ^= existing duplicate rows; see docs\ops-checklist.md section 3 part 3
    set /a SKIP+=1
) else (
    echo   OK   %~1
    set /a OK+=1
)
exit /b 0
