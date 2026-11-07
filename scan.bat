@echo off
REM Scanner de aquecimento para Android (Windows). Mesmo uso do scan.sh:
REM   scan.bat boot [minutos]   reinicia o celular e coleta desde a inicializacao
REM   scan.bat start [minutos] ^| status ^| collect ^| run [minutos]
REM Envio automatico: coloque o token do GitHub no arquivo .github_token
setlocal enabledelayedexpansion
cd /d "%~dp0"
set REMOTE=/data/local/tmp/thermal_scan
set INTERVAL=15
set GH_REPO=lukas-lkc/App-mobile-performance
set GH_BRANCH=ccr-e3a2eeb0-ekv3ov
set MIN=%2
if "%MIN%"=="" set MIN=15
set /a DUR=%MIN%*60

where adb >nul 2>&1 || (echo ERRO: adb nao encontrado. Veja o README. & exit /b 1)

if /i "%1"=="boot" goto boot
if /i "%1"=="start" goto start
if /i "%1"=="run" goto run
if /i "%1"=="status" goto status
if /i "%1"=="collect" goto collect
echo Uso: scan.bat boot [minutos] ^| start [minutos] ^| status ^| collect ^| run [minutos]
exit /b 1

:push
adb shell mkdir -p %REMOTE%
adb push coletor\thermal_scan.sh %REMOTE%/thermal_scan.sh >nul || (echo ERRO: celular nao conectado? & exit /b 1)
exit /b 0

:boot
call :push || exit /b 1
echo Reiniciando o celular...
adb reboot
timeout /t 5 /nobreak >nul
echo Aguardando o celular voltar (desbloqueie com o PIN quando pedir)...
adb wait-for-device
for /l %%n in (1,1,120) do (
  adb shell "test -f %REMOTE%/thermal_scan.sh" >nul 2>&1 && goto bootready
  timeout /t 1 /nobreak >nul
)
:bootready
adb shell "setsid nohup sh %REMOTE%/thermal_scan.sh %DUR% 10 %REMOTE%/out 1 < /dev/null > %REMOTE%/log.txt 2>&1 &"
timeout /t 2 /nobreak >nul
for /f %%u in ('adb shell "cut -d. -f1 /proc/uptime"') do set UP=%%u
echo.
echo Coleta iniciada !UP!s depois de ligar. Vai durar %MIN% min.
echo  -^> DESBLOQUEIE o celular e DESCONECTE o cabo agora.
echo  -^> Depois de %MIN% min, reconecte e rode: scan.bat collect
exit /b 0

:start
call :push || exit /b 1
adb shell "setsid nohup sh %REMOTE%/thermal_scan.sh %DUR% %INTERVAL% %REMOTE%/out 0 < /dev/null > %REMOTE%/log.txt 2>&1 &"
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
set NAME=%TS%_android
set DEST=reports\%NAME%
adb pull %REMOTE%/out %DEST% >nul
adb shell "rm -rf %REMOTE%/out %REMOTE%/state"
echo Relatorio salvo em: %DEST%
where python >nul 2>&1 && python analisar.py %DEST%
if not exist .github_token (
  echo Sem .github_token: relatorio NAO enviado. Anexe a pasta %DEST% no chat do Claude.
  exit /b 0
)
tar -czf "%TEMP%\%NAME%.tar.gz" -C reports %NAME%
powershell -NoProfile -Command ^
  "$t=(Get-Content .github_token -Raw).Trim();" ^
  "$b=[Convert]::ToBase64String([IO.File]::ReadAllBytes('%TEMP%\%NAME%.tar.gz'));" ^
  "$j=@{message='Relatorio ADB %NAME%';branch='%GH_BRANCH%';content=$b}|ConvertTo-Json;" ^
  "try{Invoke-RestMethod -Method Put -Uri 'https://api.github.com/repos/%GH_REPO%/contents/reports/adb/%NAME%.tar.gz' -Headers @{Authorization=\"Bearer $t\";Accept='application/vnd.github+json'} -Body $j -ContentType 'application/json' | Out-Null; Write-Host 'Enviado para o GitHub: reports/adb/%NAME%.tar.gz - e so avisar o Claude.'}catch{Write-Host ('Falha ao enviar: '+$_.Exception.Message)}"
del "%TEMP%\%NAME%.tar.gz" >nul 2>&1
exit /b 0
