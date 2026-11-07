@echo off
REM Scanner de aquecimento para Android (Windows). Mesmo uso do scan.sh:
REM   scan.bat start [minutos] ^| status ^| collect ^| run [minutos]
setlocal enabledelayedexpansion
cd /d "%~dp0"
set REMOTE=/data/local/tmp/thermal_scan
set INTERVAL=15
set MIN=%2
if "%MIN%"=="" set MIN=15
set /a DUR=%MIN%*60

where adb >nul 2>&1 || (echo ERRO: adb nao encontrado. Veja o README. & exit /b 1)

if /i "%1"=="start" goto start
if /i "%1"=="run" goto run
if /i "%1"=="status" goto status
if /i "%1"=="collect" goto collect
echo Uso: scan.bat start [minutos] ^| status ^| collect ^| run [minutos]
exit /b 1

:push
adb shell mkdir -p %REMOTE%
adb push coletor\thermal_scan.sh %REMOTE%/thermal_scan.sh >nul || (echo ERRO: celular nao conectado? & exit /b 1)
exit /b 0

:start
call :push || exit /b 1
adb shell "cd %REMOTE% && setsid nohup sh thermal_scan.sh %DUR% %INTERVAL% > %REMOTE%/log.txt 2>&1 < /dev/null &"
echo Scan de %MIN% min iniciado.
echo  -^> DESCONECTE o cabo e use o celular do jeito que costuma esquentar.
echo  -^> Depois de %MIN% min, reconecte e rode: scan.bat collect
exit /b 0

:run
call :push || exit /b 1
echo Coletando por %MIN% min... use o celular normalmente.
adb shell "cd %REMOTE% && sh thermal_scan.sh %DUR% %INTERVAL%"
goto collect

:status
adb shell "cat %REMOTE%/state 2>/dev/null || echo nenhum scan encontrado"
exit /b 0

:collect
for /f "delims=" %%s in ('adb shell "cat %REMOTE%/state 2>/dev/null"') do set ST=%%s
echo !ST! | findstr /b "done" >nul || (echo Scan nao concluido ainda: !ST! & exit /b 1)
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set TS=%%i
if not exist reports mkdir reports
set DEST=reports\%TS%_android
adb pull %REMOTE%/out %DEST% >nul
adb shell "rm -rf %REMOTE%"
echo Relatorio salvo em: %DEST%
where python >nul 2>&1 && python analisar.py %DEST%
echo.
echo Para o Claude ler: git add reports ^&^& git commit -m "scan" ^&^& git push
exit /b 0
