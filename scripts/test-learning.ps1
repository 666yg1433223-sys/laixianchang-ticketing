#requires -Version 7.2
[CmdletBinding()]
param(
    [string]$MavenCommand = 'mvn',
    [string]$MavenLocalRepository,
    [switch]$Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskEnvFile = Join-Path $taskRoot '.env'
$taskAllowedKeys = @('TICKETING_MYSQL_ROOT_PASSWORD', 'TICKETING_DB_PASSWORD',
    'TICKETING_MIGRATOR_PASSWORD', 'TICKETING_REDIS_PASSWORD')
$taskApplicationKeys = @('TICKETING_DB_PASSWORD', 'TICKETING_MIGRATOR_PASSWORD', 'TICKETING_REDIS_PASSWORD')

if (-not (Test-Path -LiteralPath $taskEnvFile -PathType Leaf)) {
    throw 'Missing .env. Run scripts/run-learning.ps1 -Action Init and start the learning MySQL/Redis first.'
}
$taskSettings = @{}
foreach ($taskLine in [IO.File]::ReadAllLines($taskEnvFile, [Text.Encoding]::UTF8)) {
    $taskValue = $taskLine.Trim()
    if (-not $taskValue -or $taskValue.StartsWith('#')) { continue }
    if ($taskValue -cnotmatch '^([A-Z_]+)=([a-f0-9]{64})$') {
        throw 'Invalid .env format: expected unquoted KEY=value with 64 lowercase hexadecimal characters.'
    }
    $taskKey = $Matches[1]
    $taskSecret = $Matches[2]
    if ($taskKey -notin $taskAllowedKeys -or $taskSettings.ContainsKey($taskKey)) {
        throw 'Unexpected or duplicate setting in .env.'
    }
    $taskSettings[$taskKey] = $taskSecret
}
foreach ($taskKey in $taskApplicationKeys) {
    if (-not $taskSettings.ContainsKey($taskKey)) { throw "Missing $taskKey in .env." }
}

$taskMaven = (Get-Command $MavenCommand -CommandType Application,ExternalScript -ErrorAction Stop).Source
$taskArguments = @('--batch-mode', '--no-transfer-progress',
    '-f', (Join-Path $taskRoot 'pom.xml'), '-s', (Join-Path $taskRoot 'scripts/maven-settings.xml'),
    '-Dproduct.live=true', '-Dproduct.learning=true',
    '-Dtest=ProductCatalogueIntegrationTest,ProductFlowIntegrationTest,ProductRefundIntegrationTest,ProductRecoveryIntegrationTest')
if ($MavenLocalRepository) {
    $taskRepository = [IO.Path]::GetFullPath($MavenLocalRepository, $taskRoot)
    $taskArguments += "-Dmaven.repo.local=$taskRepository"
}
if ($Offline) { $taskArguments += '--offline' }
$taskArguments += 'test'

$taskPrevious = @{}
Push-Location $taskRoot
try {
    foreach ($taskKey in $taskApplicationKeys) {
        $taskPrevious[$taskKey] = [Environment]::GetEnvironmentVariable($taskKey, 'Process')
        [Environment]::SetEnvironmentVariable($taskKey, $taskSettings[$taskKey], 'Process')
    }
    Write-Host 'Running real integration tests only against ticketing_learning on 127.0.0.1:13316 and Redis on 127.0.0.1:16389.'
    Write-Host 'Keep port 18082 free. Tests create learning records; this script does not delete or reset data.'
    & $taskMaven @taskArguments
    $taskExit = $LASTEXITCODE
} finally {
    foreach ($taskKey in $taskPrevious.Keys) {
        [Environment]::SetEnvironmentVariable($taskKey, $taskPrevious[$taskKey], 'Process')
    }
    Pop-Location
}
exit $taskExit
