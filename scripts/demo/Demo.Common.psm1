#requires -Version 7.2
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:ProjectRoot = 'D:\Codex\ticketing'
$script:JavaPath = "C:\Users\l'z\.jdks\temurin-17.0.20.1\bin\java.exe"
$script:Utf8 = [System.Text.UTF8Encoding]::new($false)

function Assert-DemoRunId([string]$RunId) {
    if ($RunId -cnotmatch '^[a-z0-9][a-z0-9-]{7,23}$') { throw '运行编号须为 8～24 位小写字母、数字或横线，且不能以横线开头。' }
}

function Get-DemoPaths([string]$RunId) {
    Assert-DemoRunId $RunId
    if ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')) -ne $script:ProjectRoot) { throw '演示脚本不在已配置的票务工程中。' }
    @{ Root = $script:ProjectRoot; Base = "$script:ProjectRoot\.local\demo"; Run = "$script:ProjectRoot\.local\demo\$RunId"; Java = $script:JavaPath }
}

function Assert-DemoNoLinks([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '路径包含链接或重解析点，已停止。' }
        }
        $next = [IO.Path]::GetDirectoryName($cursor)
        if ($next -eq $cursor) { break }
        $cursor = $next
    }
}

function Get-DemoUserSid { [Security.Principal.WindowsIdentity]::GetCurrent().User }

function Assert-DemoPrivateAcl([string]$Path) {
    Assert-DemoNoLinks $Path
    $sid = Get-DemoUserSid
    $acl = Get-Acl -LiteralPath $Path
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid.Value) { throw '演示私有文件的所有者不匹配，已停止。' }
    foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
        if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -ne $sid.Value) { throw '演示目录或文件可被其他账户访问，已停止；不会自动放宽权限。' }
    }
}

function New-DemoPrivateDirectory([string]$Path) {
    Assert-DemoNoLinks $Path
    if (Test-Path -LiteralPath $Path) { throw '运行目录已经存在；请使用新的运行编号，旧现场不会被覆盖。' }
    $null = New-Item -ItemType Directory -Path $Path
    $sid = Get-DemoUserSid
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, [Security.AccessControl.FileSystemRights]::FullControl, [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit', [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $acl
    Assert-DemoPrivateAcl $Path
}

function Read-DemoJson([string]$Path) {
    Assert-DemoPrivateAcl $Path
    $file = Get-Item -LiteralPath $Path
    if ($file.PSIsContainer -or $file.Length -gt 65536) { throw '演示状态文件格式或大小异常。' }
    Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Write-DemoJson([string]$Path, $Value) {
    $parent = [IO.Path]::GetDirectoryName($Path)
    Assert-DemoPrivateAcl $parent
    Assert-DemoNoLinks $Path
    if (Test-Path -LiteralPath $Path) { throw '目标状态文件已经存在，拒绝覆盖未完成命令或旧现场。' }
    $temporary = Join-Path $parent ([Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $bytes = $script:Utf8.GetBytes(($Value | ConvertTo-Json -Depth 8 -Compress))
        $stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
        Assert-DemoPrivateAcl $temporary
        [IO.File]::Move($temporary, $Path)
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}

function New-DemoNonce {
    $bytes = [byte[]]::new(32)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Test-DemoTcp([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(1200)) { return $false }
        $client.EndConnect($pending)
        return $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Get-DemoLiveProcess([int]$ProcessId) {
    try { $process = [Diagnostics.Process]::GetProcessById($ProcessId) }
    catch [ArgumentException] { return $null }
    try {
        if ($process.HasExited) { return $null }
        @{ pid = $process.Id; startedAt = $process.StartTime.ToUniversalTime().ToString('o'); javaPath = $process.MainModule.FileName }
    } catch { throw '无法确认进程路径或创建时间；已停止，不会操作未知进程。' }
    finally { $process.Dispose() }
}

function ConvertTo-DemoInstant($Value) {
    # PowerShell 7.5+ may parse an ISO JSON value directly into DateTime.
    # Casting it to string first drops fractional seconds under some cultures.
    if ($Value -is [DateTimeOffset]) { return $Value }
    if ($Value -is [DateTime]) { return [DateTimeOffset]$Value }
    [DateTimeOffset]::Parse([string]$Value, [Globalization.CultureInfo]::InvariantCulture)
}

function Test-DemoSameProcess($Expected, $Actual) {
    if ($null -eq $Actual -or [int]$Expected.pid -le 0 -or [int]$Expected.pid -ne [int]$Actual.pid) { return $false }
    if ([IO.Path]::GetFullPath([string]$Expected.javaPath) -ine [IO.Path]::GetFullPath([string]$Actual.javaPath)) { return $false }
    $expectedTime = ConvertTo-DemoInstant $Expected.startedAt
    $actualTime = ConvertTo-DemoInstant $Actual.startedAt
    [Math]::Abs(($expectedTime - $actualTime).TotalMilliseconds) -le 2
}

function Assert-DemoBinding($Record, $Launch, [string]$RunId) {
    if ([string]$Launch.runId -cne $RunId -or [string]$Record.runId -cne $RunId -or
        [string]$Launch.nonce -cnotmatch '^[a-f0-9]{64}$' -or [string]$Record.nonce -cne [string]$Launch.nonce) {
        throw '运行编号或启动凭据不匹配，拒绝接管。'
    }
    if ([IO.Path]::GetFullPath([string]$Record.javaPath) -ine $script:JavaPath) { throw '记录中的程序路径不匹配，拒绝接管。' }
}

function Get-DemoState([string]$RunId) {
    $paths = Get-DemoPaths $RunId
    Assert-DemoNoLinks $paths.Run
    if (-not (Test-Path -LiteralPath $paths.Run)) { return @{ Status = 'ABSENT'; Paths = $paths } }
    Assert-DemoPrivateAcl $paths.Run
    $launch = Read-DemoJson (Join-Path $paths.Run 'launch.json')
    $processFile = Join-Path $paths.Run 'process.json'
    if (-not (Test-Path -LiteralPath $processFile)) { return @{ Status = 'INCOMPLETE'; Paths = $paths; Launch = $launch } }
    $record = Read-DemoJson $processFile
    Assert-DemoBinding $record $launch $RunId
    $live = Get-DemoLiveProcess ([int]$record.pid)
    if ($null -eq $live) { return @{ Status = 'STOPPED'; Paths = $paths; Launch = $launch; Process = $record } }
    if (-not (Test-DemoSameProcess $record $live)) { throw 'PID 已被复用或进程身份不同；不会接管或停止它。' }
    $readyFile = Join-Path $paths.Run 'ready.json'
    if (-not (Test-Path -LiteralPath $readyFile)) { return @{ Status = 'STARTING'; Paths = $paths; Launch = $launch; Process = $record } }
    $ready = Read-DemoJson $readyFile
    Assert-DemoBinding $ready $launch $RunId
    if (-not (Test-DemoSameProcess $ready $live) -or [string]$ready.status -cne 'READY' -or [string]$ready.baseUrl -cne 'http://127.0.0.1:18083') { throw '就绪记录与实际进程不一致，拒绝接管。' }
    $health = if (Test-DemoTcp 18083) { 'READY' } else { 'UNHEALTHY' }
    @{ Status = $health; Paths = $paths; Launch = $launch; Process = $record; Ready = $ready }
}

function Assert-DemoStopped($Stopped, $Launch, [string]$RunId, [string]$CommandId) {
    if ([string]$Stopped.runId -cne $RunId -or [string]$Stopped.nonce -cne [string]$Launch.nonce -or [string]$Stopped.commandId -cne $CommandId) { throw '关闭确认不匹配本次命令。' }
    if ([string]$Stopped.status -cne 'STOPPED') { throw '应用已经退出，但正常关闭或旧数据核对未通过；失败现场保留，不能宣称正常停止。' }
}

function Enter-DemoControl([string]$Directory) {
    Assert-DemoPrivateAcl $Directory
    $path = Join-Path $Directory 'control.lock'
    Assert-DemoNoLinks $path
    try { [IO.File]::Open($path, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch { throw '同一演示运行已有命令正在执行，请等待后再试。' }
}

function Write-DemoCommand($State, [string]$Action) {
    if ($Action -cnotin @('STOP', 'SCENARIOS')) { throw '不支持的控制动作。' }
    $commandId = [Guid]::NewGuid().ToString()
    Write-DemoJson (Join-Path $State.Paths.Run 'command.json') @{ runId = $State.Launch.runId; nonce = $State.Launch.nonce; commandId = $commandId; action = $Action }
    $commandId
}

function Assert-DemoClasspath([string]$Classpath) {
    $parts = $Classpath.Trim().Split(';')
    if ($parts.Count -lt 3 -or $parts.Count -gt 500) { throw '测试运行依赖清单不完整。' }
    $required = @("$script:ProjectRoot\target\test-classes", "$script:ProjectRoot\target\classes")
    foreach ($part in $parts) {
        if ([string]::IsNullOrWhiteSpace($part) -or $part.Contains('"') -or $part.Contains("`n") -or $part.Contains("`r")) { throw '测试运行依赖路径非法。' }
        $full = [IO.Path]::GetFullPath($part)
        if ($full -notin $required -and (-not $full.StartsWith("$script:ProjectRoot\.m2\", [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetExtension($full) -ine '.jar')) { throw '测试运行依赖越出本项目范围。' }
        Assert-DemoNoLinks $full
        if (-not (Test-Path -LiteralPath $full)) { throw '测试运行依赖缺失；不会下载或替换依赖。' }
    }
    foreach ($entry in $required) { if ($entry -notin $parts) { throw '缺少当前工程测试类或业务类目录。' } }
    $parts -join ';'
}

function ConvertTo-DemoJavaArg([string]$Value) {
    if ($Value.Contains("`r") -or $Value.Contains("`n") -or $Value.Contains([char]0)) { throw 'Java 参数含有非法字符。' }
    '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"'
}

function Invoke-DemoCompile($Paths) {
    # The cached Surefire plugin exports a fresh classpath through one offline test.
    # No dependency plugin download or old XML classpath is required.
    $worker = Join-Path $PSScriptRoot 'Invoke-DemoCompile.ps1'
    $shell = (Get-Process -Id $PID).Path
    $args = '-NoLogo -NoProfile -NonInteractive -File "' + $worker + '" -RunId "' + [IO.Path]::GetFileName($Paths.Run) + '"'
    $process = Start-Process -FilePath $shell -ArgumentList $args -WorkingDirectory $Paths.Root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $Paths.Run 'build.stdout.log') -RedirectStandardError (Join-Path $Paths.Run 'build.stderr.log')
    try {
        $deadline = [DateTime]::UtcNow.AddMinutes(3)
        while (-not $process.WaitForExit(1000)) {
            if ([DateTime]::UtcNow -ge $deadline) { throw '离线构建超时，构建进程及日志保留；请勿并发启动另一构建。' }
        }
        if ($process.ExitCode -ne 0) { throw '离线构建失败；请检查本轮私有构建日志，未启动演示服务。' }
    } finally { $process.Dispose() }
    $classpathFile = Join-Path $Paths.Run 'classpath.txt'
    Assert-DemoPrivateAcl $classpathFile
    Assert-DemoClasspath ([IO.File]::ReadAllText($classpathFile, $script:Utf8))
}

function Start-DemoRun([string]$RunId) {
    $paths = Get-DemoPaths $RunId
    $mutex = [Threading.Mutex]::new($false, 'Local\TicketingDemoStart18083')
    $acquired = $false
    try {
        try { $acquired = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $acquired = $true }
        if (-not $acquired) { throw '已有演示启动命令正在进行，拒绝并发构建或占用端口。' }
        $state = Get-DemoState $RunId
        if ($state.Status -eq 'READY') { Write-Host "本轮演示已经就绪：$RunId，http://127.0.0.1:18083（测试身份、模拟付款）。"; return }
        if ($state.Status -ne 'ABSENT') { throw '本运行编号已有现场，不能重用；仍在启动时请查询状态，否则换一个新编号。' }
        $mysql = Test-DemoTcp 13306
        $redis = Test-DemoTcp 16379
        if (-not $mysql -or -not $redis) { throw "依赖未就绪：MySQL(13306)=$mysql，Redis(16379)=$redis；未启动 Docker、修改数据库或创建运行目录。" }
        if (Test-DemoTcp 18083) { throw '18083 端口已被占用，拒绝接管、停止或更换端口。' }
        Assert-DemoNoLinks $paths.Java
        if (-not (Test-Path -LiteralPath $paths.Java -PathType Leaf)) { throw '已配置的 JDK 不存在；不会自动安装。' }
        Assert-DemoNoLinks $paths.Base
        if (-not (Test-Path -LiteralPath $paths.Base)) { New-DemoPrivateDirectory $paths.Base }
        New-DemoPrivateDirectory $paths.Run
        $launch = @{ runId = $RunId; nonce = New-DemoNonce }
        Write-DemoJson (Join-Path $paths.Run 'launch.json') $launch
        Write-Host '正在使用已有依赖离线编译演示程序，不会运行数据库测试。'
        $classpath = Invoke-DemoCompile $paths
        if (Test-DemoTcp 18083) { throw '编译期间 18083 被占用，已停止；未启动 Java 演示进程。' }
        $javaArgs = @('-Xmx512m', '-Dfile.encoding=UTF-8', '-Dbiz.evidenceTask=BIZ-04-I-001', '-cp', $classpath, 'com.ticketing.booking.demo.TicketingDemoProcess', $RunId)
        $argumentFile = Join-Path $paths.Run 'java.args'
        [IO.File]::WriteAllLines($argumentFile, [string[]]@($javaArgs | ForEach-Object { ConvertTo-DemoJavaArg $_ }), $script:Utf8)
        Assert-DemoPrivateAcl $argumentFile
        $process = Start-Process -FilePath $paths.Java -ArgumentList ('@"' + $argumentFile + '"') -WorkingDirectory $paths.Root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $paths.Run 'java.stdout.log') -RedirectStandardError (Join-Path $paths.Run 'java.stderr.log')
        try {
            $live = Get-DemoLiveProcess $process.Id
            if ($null -eq $live) { throw '演示程序启动后立即退出，私有日志已保留。' }
            Write-DemoJson (Join-Path $paths.Run 'process.json') @{ runId = $RunId; nonce = $launch.nonce; pid = $live.pid; startedAt = $live.startedAt; javaPath = $live.javaPath }
            $deadline = [DateTime]::UtcNow.AddSeconds(90)
            while ([DateTime]::UtcNow -lt $deadline) {
                $state = Get-DemoState $RunId
                if ($state.Status -eq 'READY') { Write-Host "演示已就绪：$RunId，http://127.0.0.1:18083。只提供测试接口，不是网页前端；无真实资金。"; return }
                if ($state.Status -eq 'STOPPED') { throw '演示启动失败，未报告就绪；本轮私有日志和现场已保留。' }
                Start-Sleep -Milliseconds 500
            }
            throw '演示启动超时，未强停进程；请先查询本运行编号的状态，勿重复启动。'
        } finally { $process.Dispose() }
    } finally { if ($acquired) { $mutex.ReleaseMutex() }; $mutex.Dispose() }
}

function Show-DemoStatus([string]$RunId) {
    $mysql = Test-DemoTcp 13306
    $redis = Test-DemoTcp 16379
    $state = Get-DemoState $RunId
    Write-Host "依赖连接：MySQL(13306)=$mysql，Redis(16379)=$redis。未对 Docker 引擎健康作推断。"
    switch ($state.Status) {
        'READY' { Write-Host "本轮应用已就绪：$RunId，http://127.0.0.1:18083（测试身份、模拟付款）。" }
        'UNHEALTHY' { Write-Host '本轮进程身份已确认，但 18083 无法连接；应用不健康，未触发修复。可发送本轮正常停止命令。' }
        'STARTING' { Write-Host '本轮进程正在启动，尚未就绪。' }
        'STOPPED' { Write-Host '本轮进程已退出，历史运行目录和数据保留。' }
        'INCOMPLETE' { Write-Host '本轮启动现场不完整；未确认应用运行，不会接管未知进程。' }
        default { Write-Host '该运行编号尚未启动。' }
    }
    if (-not $mysql -or -not $redis -or $state.Status -ne 'READY') { return 2 }
    return 0
}

function Stop-DemoRun([string]$RunId) {
    $state = Get-DemoState $RunId
    if ($state.Status -in @('ABSENT', 'STOPPED')) { Write-Host '本轮演示未运行，无需停止；未改动其他进程或数据。'; return }
    if ($state.Status -notin @('READY', 'UNHEALTHY')) { throw '本轮进程尚未通过就绪身份核对；请等待或检查私有启动日志，不会强停。' }
    $control = Enter-DemoControl $state.Paths.Run
    try {
        $state = Get-DemoState $RunId
        if ($state.Status -eq 'STOPPED') { Write-Host '本轮进程已退出。'; return }
        if ($state.Status -notin @('READY', 'UNHEALTHY')) { throw '应用状态已变化，未发出停止命令。' }
        $commandId = Write-DemoCommand $state 'STOP'
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        while ([DateTime]::UtcNow -lt $deadline) {
            $now = Get-DemoState $RunId
            if ($now.Status -eq 'STOPPED') {
                $stoppedFile = Join-Path $state.Paths.Run 'stopped.json'
                if (-not (Test-Path -LiteralPath $stoppedFile)) { throw '进程已经退出，但没有正常关闭确认；现场保留，不宣称优雅停止通过。' }
                $stopped = Read-DemoJson $stoppedFile
                Assert-DemoStopped $stopped $state.Launch $RunId $commandId
                Write-Host '本轮演示已正常停止；数据与证据保留，未停止 Docker、容器或其他 Java 进程。'
                return
            }
            Start-Sleep -Milliseconds 500
        }
        throw '正常停止超时，未强停进程；命令和现场保留，请先查询状态。'
    } finally { $control.Dispose() }
}

function Invoke-DemoScenarios([string]$RunId) {
    $state = Get-DemoState $RunId
    if ($state.Status -ne 'READY') { throw '本轮演示尚未就绪，请先启动并确认状态。' }
    $control = Enter-DemoControl $state.Paths.Run
    try {
        $state = Get-DemoState $RunId
        if ($state.Status -ne 'READY') { throw '应用状态已变化，未发送场景命令。' }
        $commandId = Write-DemoCommand $state 'SCENARIOS'
        $resultFile = Join-Path $state.Paths.Run "result-$commandId.json"
        Write-Host '正在通过本轮真实接口验证交易场景；最多等待 180 秒，测试数据默认保留。'
        $deadline = [DateTime]::UtcNow.AddSeconds(180)
        while ([DateTime]::UtcNow -lt $deadline) {
            if (Test-Path -LiteralPath $resultFile) {
                $result = Read-DemoJson $resultFile
                if ([string]$result.runId -cne $RunId -or [string]$result.commandId -cne $commandId -or $result.success -isnot [bool]) { throw '场景结果与本次命令不匹配。' }
                Write-Host ([string]$result.summary)
                if ($result.evidencePath) { Write-Host ('证据位置：' + [string]$result.evidencePath) }
                if (-not $result.success) { throw '至少一个真实场景未通过；失败证据保留，不能记为完成。' }
                return
            }
            if ((Get-DemoState $RunId).Status -ne 'READY') { throw '场景运行期间应用退出或失去可核对身份，未记录成功。' }
            Start-Sleep -Seconds 1
        }
        throw '场景运行超时；命令与现场保留，未强停进程或删除测试数据。'
    } finally { $control.Dispose() }
}

Export-ModuleMember -Function *-Demo*
