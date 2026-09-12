#requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Start', 'Status', 'Stop')][string]$Action,
    [Parameter(Mandatory = $true)][string]$RunId
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Demo.Common.psm1') -Force -DisableNameChecking
try {
    Assert-DemoRunId $RunId
    switch ($Action) {
        'Start' { Start-DemoRun $RunId }
        'Status' { $statusExit = Show-DemoStatus $RunId; exit $statusExit }
        'Stop' { Stop-DemoRun $RunId }
    }
    exit 0
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 1
}
