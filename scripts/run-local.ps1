#requires -Version 7.2
[CmdletBinding()]
param(
    [ValidateSet('Start', 'Status', 'Stop', 'Code', 'Logs', 'Foreground')][string]$Action = 'Start',
    [switch]$Build,
    [string]$Phone
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskRoot = 'D:\Codex\ticketing'
if ([IO.Path]::GetFullPath($PSScriptRoot) -ine "$taskRoot\scripts") { throw 'Unexpected project root' }
# Reuse private-file and process-identity helpers without launching a demo.
Import-Module (Join-Path $PSScriptRoot 'demo\Demo.Common.psm1') -Force -DisableNameChecking
$taskJava = "C:\Users\l'z\.jdks\temurin-17.0.20.1\bin\java.exe"
$taskJar = "$taskRoot\target\ticketing-booking-0.0.1-SNAPSHOT.jar"
$taskBase = "$taskRoot\.local\local-site"
$taskCurrent = Join-Path $taskBase 'current.json'
$taskUrl = 'http://127.0.0.1:18082'

function Get-LocalState {
    Assert-DemoNoLinks $taskBase
    if (-not (Test-Path -LiteralPath $taskCurrent -PathType Leaf)) { return @{ Status = 'ABSENT' } }
    $current = Read-DemoJson $taskCurrent
    if ([string]$current.runId -cnotmatch '^local-[a-z0-9-]{8,44}$') { throw 'Invalid local run record' }
    $directory = Join-Path $taskBase ([string]$current.runId)
    $launch = Read-DemoJson (Join-Path $directory 'launch.json')
    if ([string]$launch.runId -cne [string]$current.runId -or [string]$launch.nonce -cnotmatch '^[a-f0-9]{64}$') {
        throw 'Local run identity does not match'
    }
    $result = @{ Status = 'INCOMPLETE'; Directory = $directory; Launch = $launch }
    $processFile = Join-Path $directory 'process.json'
    if (-not (Test-Path -LiteralPath $processFile -PathType Leaf)) { return $result }
    $record = Read-DemoJson $processFile
    Assert-DemoBinding $record $launch ([string]$launch.runId)
    $result.Process = $record
    $live = Get-DemoLiveProcess ([int]$record.pid)
    if ($null -eq $live) { $result.Status = 'STOPPED'; return $result }
    if (-not (Test-DemoSameProcess $record $live)) { throw 'PID was reused; the unrelated process will not be controlled' }
    $result.Status = 'STARTING'
    $readyFile = Join-Path $directory 'ready.json'
    if (-not (Test-Path -LiteralPath $readyFile -PathType Leaf)) { return $result }
    $ready = Read-DemoJson $readyFile
    Assert-DemoBinding $ready $launch ([string]$launch.runId)
    if (-not (Test-DemoSameProcess $ready $live) -or $ready.status -cne 'READY' -or $ready.baseUrl -cne $taskUrl) {
        throw 'Local readiness record does not match the application'
    }
    $result.Status = if (Test-DemoTcp 18082) { 'READY' } else { 'UNHEALTHY' }
    return $result
}
function Assert-LocalDependencies {
    foreach ($file in @($taskJava, $taskJar, "$taskRoot\.local\credentials.json", "$taskRoot\.local\mysql-trust.p12",
            'D:\Codex\ticketing-e0\infra\.local\redis-password')) {
        Assert-DemoNoLinks $file
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required local file is missing: $file" }
    }
    foreach ($port in @(13306, 16379)) {
        if (-not (Test-DemoTcp $port)) { throw "Existing ticketing dependency $port is unavailable. No container or database was changed." }
    }
}
function Build-LocalJar {
    if (-not $Build) { return }
    & "$PSScriptRoot\invoke-maven.ps1" -MavenArgs @('-o', '-DskipTests', 'package')
    if ($LASTEXITCODE -ne 0) { throw 'Build failed; application was not started' }
}
function Show-LocalStatus {
    $state = Get-LocalState
    Write-Host "Local website: $($state.Status) $taskUrl"
    Write-Host "MySQL 13306: $(Test-DemoTcp 13306); Redis 16379: $(Test-DemoTcp 16379)"
    if ($state.ContainsKey('Process')) { Write-Host "Application PID: $($state.Process.pid)" }
    if ($state.ContainsKey('Directory')) { Write-Host "Private log: $($state.Directory)\java.stdout.log" }
    if ($state.Status -ne 'READY') { return 2 }
    return 0
}
function Start-LocalSite {
    $state = Get-LocalState
    if ($state.Status -eq 'READY') { Write-Host "Local website is already ready: $taskUrl"; return }
    if ($state.Status -notin @('ABSENT', 'STOPPED')) {
        throw "Local application is $($state.Status). Check Status or Logs before starting again."
    }
    if (Test-DemoTcp 18082) { throw 'Port 18082 is already in use; no process will be taken over' }
    Build-LocalJar
    Assert-LocalDependencies
    if (-not (Test-Path -LiteralPath $taskBase)) { New-DemoPrivateDirectory $taskBase }
    Assert-DemoPrivateAcl $taskBase
    $runId = 'local-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $directory = Join-Path $taskBase $runId
    New-DemoPrivateDirectory $directory
    $launch = @{ runId = $runId; nonce = New-DemoNonce }
    Write-DemoJson (Join-Path $directory 'launch.json') $launch
    # Replace only the pointer; retain earlier runs, logs and every database record.
    $pointer = Join-Path $taskBase ([Guid]::NewGuid().ToString('N') + '.json')
    Write-DemoJson $pointer @{ runId = $runId }
    Assert-DemoNoLinks $taskCurrent
    [IO.File]::Move($pointer, $taskCurrent, $true)
    $javaArguments = '-Xmx512m -Dfile.encoding=UTF-8 -jar "' + $taskJar + '" --spring.profiles.active=local-sim --biz.local.run-id=' + $runId
    $process = Start-Process -FilePath $taskJava -ArgumentList $javaArguments -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $directory 'java.stdout.log') -RedirectStandardError (Join-Path $directory 'java.stderr.log')
    try {
        $live = Get-DemoLiveProcess $process.Id
        if ($null -eq $live) { throw 'Application exited during startup; private logs were preserved' }
        Write-DemoJson (Join-Path $directory 'process.json') @{ runId = $runId; nonce = $launch.nonce; pid = $live.pid; startedAt = $live.startedAt; javaPath = $live.javaPath }
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        while ([DateTime]::UtcNow -lt $deadline) {
            $state = Get-LocalState
            if ($state.Status -eq 'READY') {
                Write-Host "Local website is ready: $taskUrl"
                Write-Host 'Local verification codes only; payments are simulated and use no real money.'
                Write-Host 'After requesting a code in the website: pwsh -File D:\Codex\ticketing\scripts\run-local.ps1 -Action Code -Phone YOUR_PHONE'
                return
            }
            if ($state.Status -eq 'STOPPED') { throw 'Application startup failed. Use -Action Logs to inspect the preserved local logs.' }
            Start-Sleep -Milliseconds 400
        }
        throw 'Startup has not finished within 45 seconds. Use -Action Status or Logs; the process was not terminated.'
    } finally { $process.Dispose() }
}
function Stop-LocalSite {
    $state = Get-LocalState
    if ($state.Status -in @('ABSENT', 'STOPPED')) { Write-Host 'Local website is already stopped. Data and logs are retained.'; return }
    if ($state.Status -notin @('READY', 'UNHEALTHY')) { throw 'Application has not established its identity and readiness; no stop signal was sent' }
    $stopFile = Join-Path $state.Directory 'stop.json'
    if (-not (Test-Path -LiteralPath $stopFile)) {
        Write-DemoJson $stopFile @{ runId = $state.Launch.runId; nonce = $state.Launch.nonce; action = 'STOP' }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        $now = Get-LocalState
        if ($now.Status -eq 'STOPPED') {
            $stopped = Read-DemoJson (Join-Path $state.Directory 'stopped.json')
            if ($stopped.runId -cne $state.Launch.runId -or $stopped.nonce -cne $state.Launch.nonce -or $stopped.status -cne 'STOPPED') {
                throw 'Normal shutdown could not be confirmed; the records were preserved'
            }
            Write-Host 'Local website stopped normally. MySQL, Redis, orders and logs were retained.'
            return
        }
        Start-Sleep -Milliseconds 400
    }
    throw 'Normal shutdown has not finished within 45 seconds. No process was forcibly terminated.'
}
function Show-LocalCode {
    if ($Phone -cnotmatch '^1[3-9][0-9]{9}$') { throw 'Use -Phone followed by the same valid phone number entered in the website' }
    $state = Get-LocalState
    if ($state.Status -ne 'READY') { throw 'The local website is not ready' }
    $log = Join-Path $state.Directory 'java.stdout.log'
    Assert-DemoPrivateAcl $log
    $reference = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Phone))).ToLowerInvariant()
    $entry = Get-Content -LiteralPath $log -Tail 2000 -Encoding UTF8 | Where-Object {
        $_ -match 'LOCAL_SIM_SMS' -and $_ -match ('phoneRef=' + $reference + '(?:\s|$)')
    } | Select-Object -Last 1
    if (-not $entry) { throw 'No code for this phone was found in the current private log. Request a code in the website first.' }
    if ($entry -notmatch '^\s*(?<at>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(?:Z|[+-]\d{2}:\d{2})).*\bcode=(?<code>\d{6})\b') {
        throw 'The local code log could not be parsed; no code was displayed'
    }
    $issued = [DateTimeOffset]::Parse($Matches.at, [Globalization.CultureInfo]::InvariantCulture)
    $remaining = [int][Math]::Floor(120 - ([DateTimeOffset]::UtcNow - $issued).TotalSeconds)
    if ($remaining -le 0 -or $remaining -gt 120) { throw 'The latest code has expired or its time cannot be confirmed. Request a new code in the website.' }
    Write-Host "Latest local code: $($Matches.code) (valid for about $remaining more seconds; 2 minutes total, one-time use)."
    Write-Host 'This reads only the local private log. No text message was sent.'
}
$taskMutex = [Threading.Mutex]::new($false, 'Local\TicketingLocalSite18082')
$taskAcquired = $false
try {
    try { $taskAcquired = $taskMutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $taskAcquired = $true }
    if (-not $taskAcquired) { throw 'Another local website command is still running; wait until it finishes' }
    if ($Build -and $Action -notin @('Start', 'Foreground')) { throw '-Build is only valid with Start or Foreground' }
    switch ($Action) {
        'Start' { Start-LocalSite }
        'Status' { exit (Show-LocalStatus) }
        'Stop' { Stop-LocalSite }
        'Code' { Show-LocalCode }
        'Logs' {
            $state = Get-LocalState
            if (-not $state.ContainsKey('Directory')) { throw 'No local website logs exist yet' }
            foreach ($name in @('java.stdout.log', 'java.stderr.log')) {
                $log = Join-Path $state.Directory $name
                if (Test-Path -LiteralPath $log -PathType Leaf) { Assert-DemoPrivateAcl $log; Get-Content -LiteralPath $log -Tail 60 -Encoding UTF8 }
            }
        }
        'Foreground' {
            if (Test-DemoTcp 18082) { throw 'Port 18082 is already in use' }
            Build-LocalJar
            Assert-LocalDependencies
            Write-Host "Local website: $taskUrl. LOCAL_SIM_SMS codes are printed below; payment is simulated. Press Ctrl+C to stop."
            Push-Location $taskRoot
            try { & $taskJava '-Xmx512m' '-Dfile.encoding=UTF-8' '-jar' $taskJar '--spring.profiles.active=local-sim'; $taskExit = $LASTEXITCODE }
            finally { Pop-Location }
            exit $taskExit
        }
    }
    exit 0
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 1
} finally {
    if ($taskAcquired) { $taskMutex.ReleaseMutex() }
    $taskMutex.Dispose()
}
