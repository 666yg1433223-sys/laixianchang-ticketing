param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]{8,24}$')][string]$RunId)
$ErrorActionPreference='Stop'
# Compatibility entry: current source includes V3. Preserve BIZ01/02 evidence.
& "$PSScriptRoot\verify-biz03.ps1" -RunId $RunId -Stage Full
exit $LASTEXITCODE
