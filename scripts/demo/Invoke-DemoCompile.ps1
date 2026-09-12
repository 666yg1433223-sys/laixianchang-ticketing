#requires -Version 7.2
param([Parameter(Mandatory = $true)][string]$RunId)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Demo.Common.psm1') -Force -DisableNameChecking
try {
    $paths = Get-DemoPaths $RunId
    Assert-DemoPrivateAcl $paths.Run
    $classpathFile = Join-Path $paths.Run 'classpath.txt'
    Assert-DemoNoLinks $classpathFile
    if (Test-Path -LiteralPath $classpathFile) { throw '拒绝覆盖既有依赖清单。' }
    & (Join-Path $paths.Root 'scripts\invoke-maven.ps1') -MavenArgs @('-o', '-Dtest=DemoClasspathOfflineTest', "-Ddemo.classpathFile=$classpathFile", 'test')
    exit $LASTEXITCODE
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 1
}
