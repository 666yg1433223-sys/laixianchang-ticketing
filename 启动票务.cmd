@echo off
chcp 65001 >nul
setlocal
set "TICKETING_PWSH=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe"
if not exist "%TICKETING_PWSH%" set "TICKETING_PWSH=pwsh.exe"
if "%TICKETING_PWSH%"=="pwsh.exe" (
  where /q pwsh.exe
  if errorlevel 1 (
    echo 未找到 PowerShell 7，无法启动票务项目。
    echo 请先恢复 Codex 自带运行环境，或安装 PowerShell 7 后重试。
    pause
    exit /b 1
  )
)
echo 正在启动本地票务网站，成功后会自动打开浏览器。
echo 验证码只保存在本机；付款为模拟操作，不会真实扣款。
"%TICKETING_PWSH%" -NoProfile -File "%~dp0scripts\run-local.ps1" -Action Start
if errorlevel 1 (
  pause
  exit /b 1
)
start "" "http://127.0.0.1:18082"
exit /b 0
