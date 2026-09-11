param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]{8,24}$')][string]$RunId)
$ErrorActionPreference='Stop'
$taskRoot='D:\Codex\ticketing'
if($PSScriptRoot -ne "$taskRoot\scripts"){throw 'Wrong project root'}
$evidence=Join-Path $taskRoot "evidence/BIZ-01-I-001/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Used runId: choose a new ID, preserve existing evidence'}
if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq 18083).Count){throw '18083 occupied; do not stop unknown process'}
$null=New-Item -ItemType Directory -Path $evidence
$started=Get-Date
$mavenArgs=@('-Dtest=MigrationTest,StartupBoundaryTest,SmokeTest,BookingRegressionTest',"-Dbiz.runId=$RunId","-Dbiz01.test.reportsDirectory=$evidence/surefire",'test')
& "$PSScriptRoot\invoke-maven.ps1" @mavenArgs 2>&1 | Tee-Object -FilePath "$evidence\maven.log"
$runExit=$LASTEXITCODE
$result=[ordered]@{task='BIZ-01-I-001';runId=$RunId;startedAt=$started.ToString('o');finishedAt=(Get-Date -Format o);exitCode=$runExit;reports="$evidence/surefire";expectedBusinessCases=19;expectedJUnitTests=23;commandGoals=$mavenArgs;scope='MySQL ticketing_biz01_it and this run Redis prefix only; formal profile disabled'}
[IO.File]::WriteAllText("$evidence\execution.json",($result | ConvertTo-Json -Depth 6))
exit $runExit
