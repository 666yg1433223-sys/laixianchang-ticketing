param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]{8,24}$')][string]$RunId)
$ErrorActionPreference='Stop'
# Compatibility entry: current source includes V2/C17 upgrade. Never write new results into BIZ-01 history.
& "$PSScriptRoot\verify-biz02.ps1" -RunId $RunId -Stage Full
exit $LASTEXITCODE
