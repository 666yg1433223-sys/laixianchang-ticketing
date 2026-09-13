@echo off
chcp 65001 >nul
setlocal
set "TICKETING_PWSH=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe"
if not exist "%TICKETING_PWSH%" set "TICKETING_PWSH=pwsh.exe"
if "%TICKETING_PWSH%"=="pwsh.exe" (
  where /q pwsh.exe
  if errorlevel 1 (
    echo 未找到 PowerShell 7，无法读取本地验证码。
    pause
    exit /b 1
  )
)
"%TICKETING_PWSH%" -NoLogo -NoProfile -File "%~dp0scripts\show-local-code.ps1"
set "TICKETING_EXIT=%ERRORLEVEL%"
if not "%TICKETING_EXIT%"=="0" (
  echo 取码工具启动失败。请保留此窗口中的提示，便于排查。
  pause
)
exit /b %TICKETING_EXIT%
