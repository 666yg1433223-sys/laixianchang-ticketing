param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]{8,24}$')][string]$RunId,
    [ValidateSet('Migration','Smoke','Regression','Full')][string]$Stage='Full'
)
$ErrorActionPreference='Stop'
$taskRoot='D:\Codex\ticketing'
if($PSScriptRoot -ne "$taskRoot\scripts"){throw 'Wrong project root'}
$evidence=Join-Path $taskRoot "evidence/BIZ-03-I-001/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Used runId: preserve prior evidence'}
if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq 18083).Count){throw '18083 occupied; do not stop unknown process'}
$null=New-Item -ItemType Directory -Path $evidence
$selected=@{Migration='PaymentMigrationTest,MigrationTest';Smoke='PaymentSmokeTest';Regression='PaymentRegressionTest';Full='PaymentMigrationTest,MigrationTest,StartupBoundaryTest,SmokeTest,BookingRegressionTest,OrderCloseSmokeTest,OrderCloseRegressionTest,OrderContractTest,PaymentSmokeTest,PaymentRegressionTest'}[$Stage]
$started=Get-Date
$mavenArgs=@('-o',"-Dtest=$selected","-Dbiz.runId=$RunId","-Dbiz01.test.reportsDirectory=$evidence/surefire",'test')
& "$PSScriptRoot\invoke-maven.ps1" @mavenArgs 2>&1 | Tee-Object -FilePath "$evidence\maven.log"
$runExit=$LASTEXITCODE
$result=[ordered]@{task='BIZ-03-I-001';runId=$RunId;stage=$Stage;startedAt=$started.ToString('o');finishedAt=(Get-Date -Format o);exitCode=$runExit;reports="$evidence/surefire";commandGoals=$mavenArgs;scope='Existing ticketing_biz01_it only, own Redis prefix, formal profile disabled'}
[IO.File]::WriteAllText("$evidence\execution.json",($result | ConvertTo-Json -Depth 6))
exit $runExit
