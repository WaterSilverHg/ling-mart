@echo off
REM ============================================================
REM LingMart - remove load-test data (MySQL rows + ES documents)
REM
REM Usage:  cmd /c scripts\load-test\seed\teardown-loadtest.bat
REM
REM NOTE: ASCII-ONLY BY DESIGN - see scripts\migrate.bat for the reason.
REM
REM Deletes ONLY the fixed ID ranges used by seed-loadtest.sql:
REM   buyers 9100001-9100020   sellers 9200001-9200020   shops 9300001-9300020
REM   spu    9400001-9400500   sku     9500001-9501500   images 9600001-9603000
REM   ware_sku 9700001-9701500
REM Real data is untouched. The admin account (13800000000) is outside every range.
REM ============================================================
setlocal
set CONTAINER=lingmart-mysql
set SEED_DIR=%~dp0
set SQL=%SEED_DIR%teardown-loadtest.sql
set OUTFILE=%TEMP%\lingmart-teardown.out

cd /d "%SEED_DIR%..\..\.."

echo ==== LingMart load-test teardown ====
echo.

if not exist "%SQL%" (
    echo [ERROR] not found: %SQL%
    exit /b 1
)

docker exec %CONTAINER% mysql -uroot -proot -e "SELECT 1" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] "docker exec %CONTAINER%" failed.
    echo         1^) is Docker Desktop running and the container up?  docker compose up -d
    echo         2^) running this from PowerShell may break pipes - use cmd:
    echo            cmd /c scripts\load-test\seed\teardown-loadtest.bat
    exit /b 1
)

echo [1/2] removing MySQL rows ...
docker exec -i %CONTAINER% mysql -uroot -proot --default-character-set=utf8mb4 < "%SQL%" > "%OUTFILE%" 2>&1
if errorlevel 1 (
    echo   FAIL - reason below:
    findstr /i /c:"ERROR" "%OUTFILE%"
    exit /b 1
)
type "%OUTFILE%"

echo.
echo [2/2] removing ES documents ...
REM ES is NOT cleaned by SQL: reconcileEsIndex only fills missing docs, it never
REM deletes. Leftovers make deleted products still searchable (click -> 404).
where node >nul 2>&1
if errorlevel 1 (
    echo   SKIP - node not found. Clean ES manually:
    echo     curl -X POST "http://localhost:9201/lingmart_product/_delete_by_query?refresh=true" ^
    echo          -H "Content-Type: application/json" ^
    echo          -d "{\"query\":{\"range\":{\"shopId\":{\"gte\":9300001,\"lte\":9300020}}}}"
    exit /b 0
)
node "%SEED_DIR%post-seed.mjs" --clean
if errorlevel 1 (
    echo   ES cleanup FAILED - MySQL is clean, but stale search hits may remain.
    exit /b 1
)

echo.
echo Done. Verification (no Chinese here on purpose - a .bat with UTF-8 Chinese
echo gets mis-parsed by cmd, see scripts\migrate.bat):
echo   node scripts\load-test\seed\post-seed.mjs
echo     -^> its "search smoke" line should now report total shops=0
echo   See docs\load-testing.md for the curl equivalent of that smoke check.
exit /b 0
