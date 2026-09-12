#requires -Version 7.2
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'Demo.Common.psm1') -Force -DisableNameChecking
$checks = [Collections.Generic.List[string]]::new()

function Assert-Check([string]$Name, [scriptblock]$Test) {
    if (-not (& $Test)) { throw "离线检查失败：$Name" }
    $checks.Add($Name)
}
function Assert-Refused([string]$Name, [scriptblock]$Test) {
    $refused = $false
    try { & $Test | Out-Null } catch { $refused = $true }
    Assert-Check $Name { $refused }
}

$temporary = Join-Path $PSScriptRoot ('.offline-' + [Guid]::NewGuid().ToString('N'))
try {
    foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object { $_.Extension -in @('.ps1', '.psm1') }) {
        $parseTokens = $null
        $parseErrors = $null
        [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$parseTokens, [ref]$parseErrors) | Out-Null
        Assert-Check ('语法：' + $file.Name) { $parseErrors.Count -eq 0 }
    }
    Assert-Check '合法运行编号' { Assert-DemoRunId 'demo-20260913'; $true }
    foreach ($bad in @('', 'short', '../demo1234', 'demo1234/../x', 'DEMO1234', '-demo1234', 'abcdefghijklmnopqrstuvwxy', "demo`n1234", 'demo 1234')) {
        Assert-Refused ('拒绝非法运行编号：' + ($bad -replace '[\r\n]', '?')) { Assert-DemoRunId $bad }
    }
    $nonceA = New-DemoNonce
    $nonceB = New-DemoNonce
    Assert-Check '随机启动标识格式与独立性' { $nonceA -cmatch '^[a-f0-9]{64}$' -and $nonceA -cne $nonceB }
    $paths = Get-DemoPaths 'demo-offline'
    Assert-Check '运行目录不可越出项目' { $paths.Run -eq 'D:\Codex\ticketing\.local\demo\demo-offline' }
    $expected = @{ pid = 42; startedAt = '2026-09-13T01:02:03.456Z'; javaPath = $paths.Java }
    Assert-Check '创建时间和路径匹配' { Test-DemoSameProcess $expected @{ pid = 42; startedAt = '2026-09-13T09:02:03.456+08:00'; javaPath = $paths.Java } }
    Assert-Check '拒绝 PID 重用' { -not (Test-DemoSameProcess $expected @{ pid = 42; startedAt = '2026-09-13T01:02:04Z'; javaPath = $paths.Java }) }
    Assert-Check '拒绝同 PID 不同程序' { -not (Test-DemoSameProcess $expected @{ pid = 42; startedAt = $expected.startedAt; javaPath = 'D:\different\java.exe' }) }
    Assert-Check '拒绝不同 PID' { -not (Test-DemoSameProcess $expected @{ pid = 43; startedAt = $expected.startedAt; javaPath = $paths.Java }) }
    Assert-Check '退出进程不匹配' { -not (Test-DemoSameProcess $expected $null) }
    $launch = @{ runId = 'demo-offline'; nonce = $nonceA }
    $record = @{ runId = 'demo-offline'; nonce = $nonceA; javaPath = $paths.Java }
    Assert-Check '运行编号和启动标识绑定' { Assert-DemoBinding $record $launch 'demo-offline'; $true }
    Assert-Refused '拒绝不同运行编号' { Assert-DemoBinding $record $launch 'demo-other01' }
    Assert-Refused '拒绝伪造启动标识' { Assert-DemoBinding @{ runId = 'demo-offline'; nonce = $nonceB; javaPath = $paths.Java } $launch 'demo-offline' }
    Assert-Refused '拒绝伪造 Java 路径' { Assert-DemoBinding @{ runId = 'demo-offline'; nonce = $nonceA; javaPath = 'D:\evil.exe' } $launch 'demo-offline' }
    $stopId = [Guid]::NewGuid().ToString()
    $stopped = @{ runId = 'demo-offline'; nonce = $nonceA; commandId = $stopId; status = 'STOPPED' }
    Assert-Check '正常关闭确认匹配' { Assert-DemoStopped $stopped $launch 'demo-offline' $stopId; $true }
    Assert-Refused 'FAILED 关闭结果不得报正常停止' { Assert-DemoStopped @{ runId = 'demo-offline'; nonce = $nonceA; commandId = $stopId; status = 'FAILED' } $launch 'demo-offline' $stopId }
    Assert-Refused '关闭状态必须严格大写 STOPPED' { Assert-DemoStopped @{ runId = 'demo-offline'; nonce = $nonceA; commandId = $stopId; status = 'stopped' } $launch 'demo-offline' $stopId }
    Assert-Refused '关闭确认不接受别次命令' { Assert-DemoStopped $stopped $launch 'demo-offline' ([Guid]::NewGuid().ToString()) }
    Assert-Check 'Java 参数空格和反斜线转义' { (ConvertTo-DemoJavaArg 'D:\space path\a.jar') -ceq '"D:\\space path\\a.jar"' }
    Assert-Refused '拒绝 Java 参数换行注入' { ConvertTo-DemoJavaArg "-cp`nOther" }
    Assert-Refused '拒绝空依赖清单' { Assert-DemoClasspath '' }
    Assert-Refused '拒绝项目外依赖' { Assert-DemoClasspath 'D:\Codex\ticketing\target\test-classes;D:\Codex\ticketing\target\classes;C:\Windows\evil.jar' }

    New-DemoPrivateDirectory $temporary
    Assert-Check '私有目录仅当前账户可访问' { Assert-DemoPrivateAcl $temporary; $true }
    $stateFile = Join-Path $temporary 'state.json'
    Write-DemoJson $stateFile $launch
    Assert-Check '原子私有状态读写' { $saved = Read-DemoJson $stateFile; $saved.runId -ceq 'demo-offline' -and $saved.nonce -ceq $nonceA }
    Assert-Refused '拒绝覆盖既有状态' { Write-DemoJson $stateFile @{ overwritten = $true } }
    Assert-Check '覆盖被拒后原状态保留' { (Read-DemoJson $stateFile).nonce -ceq $nonceA }
    $control = Enter-DemoControl $temporary
    try { Assert-Refused '同一运行命令互斥' { Enter-DemoControl $temporary } } finally { $control.Dispose() }
    $control2 = Enter-DemoControl $temporary
    $control2.Dispose()
    Assert-Check '命令锁释放后可重新获取' { $true }
    $state = @{ Paths = @{ Run = $temporary }; Launch = $launch }
    $id = Write-DemoCommand $state 'SCENARIOS'
    Assert-Check '命令有唯一编号和运行绑定' { $cmd = Read-DemoJson (Join-Path $temporary 'command.json'); $cmd.commandId -ceq $id -and $cmd.nonce -ceq $nonceA -and $cmd.action -ceq 'SCENARIOS' }
    Assert-Refused '不覆盖正在执行的命令' { Write-DemoCommand $state 'STOP' }
    Assert-Refused '拒绝额外控制动作' { Write-DemoCommand $state 'DELETE' }
    Assert-Check '当前进程身份可只读核对' { $actual = Get-DemoLiveProcess $PID; Test-DemoSameProcess $actual $actual }
    # Exercise the actual state reader with only process and network providers mocked.
    # Private state parsing, nonce binding, timestamps and readiness checks stay real.
    $processRecord = @{ runId = 'demo-offline'; nonce = $nonceA; pid = $expected.pid; startedAt = $expected.startedAt; javaPath = $expected.javaPath }
    Write-DemoJson (Join-Path $temporary 'launch.json') $launch
    Write-DemoJson (Join-Path $temporary 'process.json') $processRecord
    Write-DemoJson (Join-Path $temporary 'ready.json') (@{ status = 'READY'; baseUrl = 'http://127.0.0.1:18083' } + $processRecord)
    $demoModule = Get-Module 'Demo.Common'
    $healthStates = & $demoModule {
        param($Directory, $Live)
        $originalPaths = (Get-Item function:Get-DemoPaths).ScriptBlock
        $originalLive = (Get-Item function:Get-DemoLiveProcess).ScriptBlock
        $originalTcp = (Get-Item function:Test-DemoTcp).ScriptBlock
        try {
            $script:offlineDirectory = $Directory
            $script:offlineLive = $Live
            $script:offlineListening = $false
            Set-Item function:script:Get-DemoPaths { param([string]$RunId) @{ Run = $script:offlineDirectory } }
            Set-Item function:script:Get-DemoLiveProcess { param([int]$ProcessId) $script:offlineLive }
            Set-Item function:script:Test-DemoTcp { param([int]$Port) if ($Port -ne 18083) { throw 'Unexpected port' }; $script:offlineListening }
            $notListening = (Get-DemoState 'demo-offline').Status
            $script:offlineListening = $true
            $listening = (Get-DemoState 'demo-offline').Status
            @{ WithoutListener = $notListening; WithListener = $listening }
        } finally {
            Set-Item function:script:Get-DemoPaths $originalPaths
            Set-Item function:script:Get-DemoLiveProcess $originalLive
            Set-Item function:script:Test-DemoTcp $originalTcp
            Remove-Variable -Scope Script -Name offlineDirectory, offlineLive, offlineListening
        }
    } $temporary $expected
    Assert-Check 'Java 活着但无监听标记 UNHEALTHY' { $healthStates.WithoutListener -ceq 'UNHEALTHY' }
    Assert-Check '只有身份与实际监听均可用才 READY' { $healthStates.WithListener -ceq 'READY' }
    Write-Host ("演示脚本离线检查通过：{0}/{0}；未启动应用、访问数据库或操作 Docker。" -f $checks.Count)
    exit 0
} finally {
    if (Test-Path -LiteralPath $temporary) {
        $resolved = [IO.Path]::GetFullPath($temporary)
        if ([IO.Path]::GetDirectoryName($resolved) -ne $PSScriptRoot -or [IO.Path]::GetFileName($resolved) -notmatch '^\.offline-[a-f0-9]{32}$') { throw '临时目录范围不符，拒绝清理。' }
        Assert-DemoNoLinks $resolved
        Remove-Item -LiteralPath $resolved -Recurse
    }
}
