param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]{8,24}$')][string]$RunId,
    [ValidateSet('Build','Offline','Management','Full')][string]$Stage='Build',
    [switch]$UseExistingIntegrationEnvironment
)
$ErrorActionPreference='Stop'
$taskRoot='D:\Codex\ticketing'
if($PSScriptRoot -ne "$taskRoot\scripts"){throw 'Wrong project root'}
$evidence=Join-Path $taskRoot "evidence/BIZ-04-I-001/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Used runId: preserve prior evidence'}
$integration=$Stage -in @('Management','Full')
if($integration -and -not $UseExistingIntegrationEnvironment){throw 'Integration stages require -UseExistingIntegrationEnvironment; no dependency service is started by this script'}
if($integration){
    if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq 18083).Count){throw '18083 occupied; leave existing process untouched'}
    foreach($dependencyPort in @(13306,16379)){
        $probe=[Net.Sockets.TcpClient]::new()
        try {
            $pending=$probe.ConnectAsync('127.0.0.1',$dependencyPort)
            if(-not $pending.Wait(2000) -or -not $probe.Connected){throw 'Required existing integration dependency is unavailable'}
        } finally {$probe.Dispose()}
    }
}
$null=New-Item -ItemType Directory -Path $evidence
$mavenArgs=@('-o',"-Dbiz.runId=$RunId",'-Dbiz.evidenceTask=BIZ-04-I-001',"-Dbiz01.test.reportsDirectory=$evidence/surefire")
switch($Stage){
    Build {$mavenArgs+=@('-DskipTests','package')}
    Offline {$mavenArgs+=@('-Dtest=*OfflineTest','test')}
    Management {$mavenArgs+=@('-Dtest=PaymentManagementIntegrationTest','test')}
    Full {$mavenArgs+=@('-Dtest=PaymentMigrationTest,MigrationTest,StartupBoundaryTest,SmokeTest,BookingRegressionTest,OrderCloseSmokeTest,OrderCloseRegressionTest,OrderContractTest,PaymentSmokeTest,PaymentRegressionTest,PaymentManagementIntegrationTest,*OfflineTest','test')}
}
$started=Get-Date
$runExit=1
try {
    & "$PSScriptRoot\invoke-maven.ps1" -MavenArgs $mavenArgs 2>&1 | Tee-Object -FilePath "$evidence\maven.log"
    $runExit=$LASTEXITCODE
    if($null -eq $runExit){$runExit=1}
} finally {
    $result=[ordered]@{task='BIZ-04-I-001';runId=$RunId;stage=$Stage;startedAt=$started.ToString('o');finishedAt=(Get-Date -Format o);exitCode=$runExit;reports="$evidence/surefire";commandGoals=$mavenArgs;integrationExplicitlySelected=[bool]$UseExistingIntegrationEnvironment;scope='Existing ticketing_biz01_it only; no environment repair, new database, global cleanup, Docker or WSL action'}
    [IO.File]::WriteAllText("$evidence\execution.json",($result | ConvertTo-Json -Depth 6))
}
exit $runExit
