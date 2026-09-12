#requires -Version 7.2
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9][a-z0-9-]{7,14}$')][string]$RunId,
    [switch]$UseExistingIntegrationEnvironment
)
$ErrorActionPreference='Stop'
if(-not $UseExistingIntegrationEnvironment){throw '请明确选择既有隔离测试环境；本脚本不会启动或修复 Docker。'}
$ticketRoot='D:\Codex\ticketing'
if($PSScriptRoot -ne "$ticketRoot\scripts"){throw 'Wrong project root'}
Import-Module "$PSScriptRoot\demo\Demo.Common.psm1" -Force -DisableNameChecking
if(-not(Test-DemoTcp 13306) -or -not(Test-DemoTcp 16379)){throw '隔离 MySQL/Redis 未就绪；未开始测试或改动数据。'}
if(Test-DemoTcp 18083){throw '18083 已占用；不会抢占其他运行。'}
$ticketEvidence=Join-Path $ticketRoot "evidence/DEL-02-I-001/$RunId"
if(Test-Path -LiteralPath $ticketEvidence){throw '运行编号已使用，保留旧证据。'}
$null=New-Item -ItemType Directory -Path $ticketEvidence
$ticketPwsh=(Get-Process -Id $PID).Path
$ticketPassed=@()
$ticketExit=1
try {
    & $ticketPwsh -NoProfile -File "$PSScriptRoot\verify-biz04.ps1" -RunId "$RunId-core" -Stage Full -UseExistingIntegrationEnvironment
    if($LASTEXITCODE -ne 0){throw '业务完整回归未通过，保留失败，不启动演示。'}
    $ticketPassed+='business-regression'
    & "$PSScriptRoot\invoke-maven.ps1" -MavenArgs @('-o',"-Dbiz.runId=$RunId-is",'-Dbiz.evidenceTask=BIZ-04-I-001',"-Dbiz01.test.reportsDirectory=$ticketEvidence/isolation-surefire",'-Dtest=DemoIsolationIntegrationTest','test')
    if($LASTEXITCODE -ne 0){throw '跨运行隔离测试未通过，保留现场。'}
    $ticketPassed+='cross-run-isolation'
    foreach($ticketSuffix in @('a','b')) {
        $ticketDemoRun="$RunId-$ticketSuffix"
        try {
            & $ticketPwsh -NoProfile -File "$PSScriptRoot\demo\Invoke-TicketingDemo.ps1" -Action Start -RunId $ticketDemoRun
            if($LASTEXITCODE -ne 0){throw '演示未就绪。'}
            & $ticketPwsh -NoProfile -File "$PSScriptRoot\demo\Invoke-TicketingDemo.ps1" -Action Start -RunId $ticketDemoRun
            if($LASTEXITCODE -ne 0){throw '重复启动检查失败。'}
            & $ticketPwsh -NoProfile -File "$PSScriptRoot\demo\Invoke-TicketingDemo.ps1" -Action Status -RunId $ticketDemoRun
            if($LASTEXITCODE -ne 0){throw '实际就绪检查失败。'}
            & $ticketPwsh -NoProfile -File "$PSScriptRoot\demo\Invoke-TicketingScenarios.ps1" -RunId $ticketDemoRun
            if($LASTEXITCODE -ne 0){throw '真实 HTTP 场景失败。'}
            $ticketPassed+="$ticketDemoRun-http"
        } finally {
            & $ticketPwsh -NoProfile -File "$PSScriptRoot\demo\Invoke-TicketingDemo.ps1" -Action Stop -RunId $ticketDemoRun
            if($LASTEXITCODE -ne 0){throw '演示未能正常关闭；不强停，不继续另一运行。'}
        }
        & $ticketPwsh -NoProfile -File "$PSScriptRoot\demo\Invoke-TicketingDemo.ps1" -Action Stop -RunId $ticketDemoRun
        if($LASTEXITCODE -ne 0){throw '重复停止检查失败。'}
        $ticketPassed+="$ticketDemoRun-lifecycle"
    }
    & "$PSScriptRoot\invoke-maven.ps1" -MavenArgs @('-o','-DskipTests','package')
    if($LASTEXITCODE -ne 0){throw '最终打包失败。'}
    $ticketPassed+='package'
    $ticketExit=0
} finally {
    [IO.File]::WriteAllText((Join-Path $ticketEvidence 'execution.json'),(@{runId=$RunId;exitCode=$ticketExit;passed=$ticketPassed;finishedAt=(Get-Date -Format o);realFunds=$false;dataRetained=$true} | ConvertTo-Json -Depth 4))
}
exit $ticketExit
