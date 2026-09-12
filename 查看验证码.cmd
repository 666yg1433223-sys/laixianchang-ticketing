@echo off
chcp 65001 >nul
setlocal
set "TICKETING_SCRIPT_ROOT=%~dp0"
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
echo 请先在票务网页点击“获取验证码”，再输入网页中使用的同一个手机号。
echo 这里只读取本机日志中的最新验证码，有效期 2 分钟，成功登录后即失效。
"%TICKETING_PWSH%" -NoProfile -Command "$ticketingPhone = (Read-Host '请输入手机号').Trim(); if ($ticketingPhone -notmatch '^1[3-9][0-9]{9}$') { Write-Host '手机号格式不正确，请输入 11 位手机号。'; exit 1 }; & ([IO.Path]::Combine($env:TICKETING_SCRIPT_ROOT, 'scripts', 'run-local.ps1')) -Action Code -Phone $ticketingPhone"
set "TICKETING_EXIT=%ERRORLEVEL%"
pause
exit /b %TICKETING_EXIT%
