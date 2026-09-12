@echo off
chcp 65001 >nul
setlocal
set "TICKETING_PWSH=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe"
if not exist "%TICKETING_PWSH%" set "TICKETING_PWSH=pwsh.exe"
if "%TICKETING_PWSH%"=="pwsh.exe" (
  where /q pwsh.exe
  if errorlevel 1 (
    echo 未找到 PowerShell 7，无法执行停止命令。
    pause
    exit /b 1
  )
)
echo 正在正常停止本地票务网站；订单、日志、MySQL 和 Redis 均会保留。
"%TICKETING_PWSH%" -NoProfile -File "%~dp0scripts\run-local.ps1" -Action Stop
set "TICKETING_EXIT=%ERRORLEVEL%"
if not "%TICKETING_EXIT%"=="0" pause
exit /b %TICKETING_EXIT%
