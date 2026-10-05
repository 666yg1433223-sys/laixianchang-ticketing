#requires -Version 7.2
[CmdletBinding()]
param(
    [ValidateSet('Init', 'Start')][string]$Action = 'Start',
    [switch]$Build
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskEnvFile = Join-Path $taskRoot '.env'
$taskKeys = @('TICKETING_MYSQL_ROOT_PASSWORD', 'TICKETING_DB_PASSWORD',
    'TICKETING_MIGRATOR_PASSWORD', 'TICKETING_REDIS_PASSWORD')

if ($Action -eq 'Init') {
    if (Test-Path -LiteralPath $taskEnvFile) {
        Write-Host 'The .env file already exists; it has not been overwritten.'
        return
    }
    $lines = @('# Private local learning passwords. Do not commit or share this file.')
    foreach ($key in $taskKeys) {
        $bytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
        $lines += "$key=$([Convert]::ToHexString($bytes).ToLowerInvariant())"
    }
    # CreateNew refuses to overwrite a file if another process created it meanwhile.
    $stream = [IO.File]::Open($taskEnvFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n") + "`n")
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
    if (-not $IsWindows) { & chmod 600 $taskEnvFile }
    Write-Host 'Created .env with four random passwords. Next: docker compose up -d --wait'
    return
}

if (-not (Test-Path -LiteralPath $taskEnvFile -PathType Leaf)) {
    throw 'Missing .env. Run: pwsh -File scripts/run-learning.ps1 -Action Init'
}
$settings = @{}
foreach ($line in [IO.File]::ReadAllLines($taskEnvFile, [Text.Encoding]::UTF8)) {
    $value = $line.Trim()
    if (-not $value -or $value.StartsWith('#')) { continue }
    if ($value -cnotmatch '^([A-Z_]+)=([a-f0-9]{64})$') {
        throw 'Invalid .env format: use unquoted KEY=value lines and 64 lowercase hexadecimal characters.'
    }
    $key = $Matches[1]
    $secret = $Matches[2]
    if ($key -notin $taskKeys -or $settings.ContainsKey($key) -or $secret.StartsWith('REPLACE_')) {
        throw 'Unexpected, duplicate, or placeholder setting in .env; check .env.example.'
    }
    $settings[$key] = $secret
}
foreach ($key in $taskKeys) {
    if (-not $settings.ContainsKey($key)) { throw "Missing $key in .env." }
}

$taskJava = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' })
} else { (Get-Command java -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $taskJava -PathType Leaf)) { throw 'JAVA_HOME does not point to a JDK. Install JDK 17 and set JAVA_HOME or PATH.' }
$taskJar = Join-Path $taskRoot 'target/ticketing-booking-0.0.1-SNAPSHOT.jar'

Push-Location $taskRoot
$previous = @{}
try {
    if ($Build) {
        $taskMaven = (Get-Command mvn -ErrorAction Stop).Source
        & $taskMaven '-DskipTests' 'package'
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed; the application was not started.' }
    }
    if (-not (Test-Path -LiteralPath $taskJar -PathType Leaf)) {
        throw 'Missing application JAR. Run mvn -DskipTests package, or add -Build.'
    }
    # Set secrets in this process environment, never in the command line or console output.
    foreach ($key in $taskKeys | Where-Object { $_ -ne 'TICKETING_MYSQL_ROOT_PASSWORD' }) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process')
    }
    Write-Host 'Opening the learning site at http://127.0.0.1:18082 after database migrations.'
    Write-Host 'Keep this console open. SMS codes appear as LOCAL_SIM_SMS. Stop with Ctrl+C.'
    $taskArguments = @('-Dfile.encoding=UTF-8', '-jar', $taskJar,
        '--spring.profiles.active=local-sim', '--biz.learning.enabled=true',
        '--biz.db.name=ticketing_learning', '--biz.db.user=ticketing_learning_app',
        '--biz.db.migrator=ticketing_learning_migrator', '--biz.db.port=13316', '--biz.redis.port=16389')
    & $taskJava @taskArguments
    $taskExit = $LASTEXITCODE
} finally {
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    Pop-Location
}
exit $taskExit
