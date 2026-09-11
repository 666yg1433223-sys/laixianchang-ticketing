param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]{8,24}$')][string]$RunId,
    [ValidateSet('Migration','Smoke','Regression','Full')][string]$Stage='Full'
)
# Compatibility entry: never add results to accepted BIZ02 history.
& "$PSScriptRoot\verify-biz03.ps1" -RunId $RunId -Stage $Stage
exit $LASTEXITCODE
