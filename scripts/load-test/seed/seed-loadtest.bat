@echo off
REM ============================================================
REM LingMart - seed load-test data (users + shops + products + stock)
REM
REM Usage:  cmd /c scripts\load-test\seed\seed-loadtest.bat
REM
REM NOTE: this file is ASCII-ONLY BY DESIGN.
REM   cmd.exe parses .bat files using the system ANSI code page (GBK on
REM   Chinese Windows). A UTF-8 .bat containing Chinese text gets mis-parsed
REM   and can even eat the leading "echo" keyword. Keep Chinese explanations
REM   in docs/load-testing.md (UTF-8, read by editors, not by cmd).
REM
REM Steps:
REM   1) run seed-loadtest.sql inside the lingmart-mysql container
REM   2) run post-seed.mjs: index the products into ES + search smoke test
REM
REM Why not "Get-Content x.sql | docker exec ..." in PowerShell?
REM   That re-encodes UTF-8 Chinese comments to GBK, mysql then fails with
REM   `ERROR 1064 ... near '?????,'`. This script uses cmd redirection
REM   (raw bytes) plus --default-character-set=utf8mb4, so it is unaffected.
REM ============================================================
setlocal
set CONTAINER=lingmart-mysql
set SEED_DIR=%~dp0
set SQL=%SEED_DIR%seed-loadtest.sql
set OUTFILE=%TEMP%\lingmart-seed.out

cd /d "%SEED_DIR%..\..\.."

echo ==== LingMart load-test seed ====
echo.

if not exist "%SQL%" (
    echo [ERROR] not found: %SQL%
    exit /b 1
)

REM Reachability probe: everything below depends on "docker exec" working HERE.
docker exec %CONTAINER% mysql -uroot -proot -e "SELECT 1" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] "docker exec %CONTAINER%" failed.
    echo         1^) is Docker Desktop running and the container up?  docker compose up -d
    echo         2^) running this from PowerShell may break pipes - use cmd:
    echo            cmd /c scripts\load-test\seed\seed-loadtest.bat
    exit /b 1
)

echo [1/2] applying seed-loadtest.sql ...
docker exec -i %CONTAINER% mysql -uroot -proot --default-character-set=utf8mb4 < "%SQL%" > "%OUTFILE%" 2>&1
if errorlevel 1 (
    echo   FAIL - mysql reported:
    findstr /i /c:"ERROR" /c:"SIGNAL" /c:"ABORTED" "%OUTFILE%"
    echo.
    echo   ---- full output so far ^(shows how far it got before failing^) ----
    REM Do NOT hide this: the seed procedure may have already succeeded and only a
    REM trailing self-check query failed. Printing only the ERROR lines once caused
    REM exactly that confusion - the data was in the DB but looked like nothing ran.
    type "%OUTFILE%"
    echo.
    echo   - the reported line points at the CALL statement, NOT at the offending
    echo     statement inside the procedure - read the message, not the line number.
    echo   - "admin user ... not found"  =^> start lingmart-auth first; it creates
    echo     13800000000 / admin123 on startup ^(AdminUserInitializer^).
    echo   - "Illegal mix of collations" =^> a temporary table column and a real
    echo     column disagree; build the temp table with CREATE ... AS SELECT so the
    echo     column inherits the real table's collation ^(see the SQL comments^).
    echo   - other errors =^> run scripts\verify-health.sh and check the schema line
    echo     ^(missing table / column means the migrations were not applied^).
    exit /b 1
)
type "%OUTFILE%"

echo.
echo [2/2] indexing products into ES ...
where node >nul 2>&1
if errorlevel 1 (
    echo   SKIP - node not found. Options:
    echo     a^) install Node and rerun post-seed:
    echo        node scripts\load-test\seed\post-seed.mjs
    echo     b^) do nothing and wait up to 5 minutes: reconcileEsIndex fills
    echo        missing ES docs automatically ^(lingmart.es.reconcile-ms^).
    echo   Until then, the search endpoints will return empty for seeded data.
    exit /b 0
)
node "%SEED_DIR%post-seed.mjs"
if errorlevel 1 (
    echo   post-seed FAILED - rows are already in MySQL, but ES may stay empty.
    echo   This step calls the admin API through the gateway, so the services must
    echo   be running ^(gateway + product at least^). Options:
    echo     a^) start the services and rerun: node scripts\load-test\seed\post-seed.mjs
    echo     b^) do nothing and wait up to 5 minutes: reconcileEsIndex fills missing
    echo        ES docs automatically.
    exit /b 1
)

echo.
echo Done. Next:
echo   k6 run scripts/load-test/lingmart-core.k6.js
exit /b 0
