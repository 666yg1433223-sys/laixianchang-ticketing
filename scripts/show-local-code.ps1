#requires -Version 7.2
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskPowerShell = (Get-Process -Id $PID).Path
$taskLauncher = Join-Path $PSScriptRoot 'run-local.ps1'

Write-Host '票务 · 查看本地验证码' -ForegroundColor Cyan
Write-Host '先在网页输入手机号并点击“获取验证码”，再在这里输入相同手机号。'
Write-Host '查完后窗口会继续等待输入；输入 Q 再按回车，或点击窗口右上角 × 退出。'

while ($true) {
    $taskPhone = Read-Host "`n请输入手机号（输入 Q 退出）"
    if ($null -eq $taskPhone) { break }
    $taskPhone = $taskPhone.Trim()
    if ($taskPhone -ieq 'q') { break }
    if ($taskPhone -notmatch '^1[3-9][0-9]{9}$') {
        Write-Host '请输入完整的 11 位手机号，然后按回车。窗口会保持打开，请重新输入。' -ForegroundColor Yellow
        continue
    }

    try {
        # run-local exits after one operation. Run it in a child so it cannot close this prompt.
        $taskReply = (& $taskPowerShell -NoLogo -NoProfile -NonInteractive -File $taskLauncher -Action Code -Phone $taskPhone 2>&1 | Out-String)
        $taskExit = $LASTEXITCODE
        if ($taskExit -eq 0 -and $taskReply -match 'Latest local code: (?<code>\d{6}) \(valid for about (?<remaining>\d+) more seconds') {
            Write-Host "`n验证码：$($Matches.code)" -ForegroundColor Green
            Write-Host "请把这 6 位数字填回网页，再点击登录。约剩 $($Matches.remaining) 秒有效，登录后失效。"
        } elseif ($taskReply -match 'No code for this phone') {
            Write-Host '还没有查到这个手机号的验证码。请先在网页点击“获取验证码”，确认手机号一致，再回来输入手机号查询。' -ForegroundColor Yellow
        } elseif ($taskReply -match 'latest code has expired') {
            Write-Host '验证码已经过期。请回网页重新获取，再回来输入手机号查询。' -ForegroundColor Yellow
        } elseif ($taskReply -match 'local website is not ready') {
            Write-Host '票务网站尚未启动。请先双击“启动票务.cmd”，打开网页获取验证码，再回来查询。' -ForegroundColor Yellow
        } elseif ($taskReply -match 'Another local website command') {
            Write-Host '另一个票务操作正在执行。请稍后重新输入手机号查询。' -ForegroundColor Yellow
        } else {
            Write-Host '暂时无法读取验证码。窗口会保持打开，可以重新输入手机号查询。' -ForegroundColor Yellow
            Write-Host $taskReply.Trim()
        }
    } catch {
        Write-Host '读取验证码时遇到问题，窗口会保持打开。请确认网站正在运行后重试。' -ForegroundColor Yellow
        Write-Host $_.Exception.Message
    }
}
