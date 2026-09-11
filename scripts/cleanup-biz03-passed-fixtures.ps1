param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]{8,24}$')][string]$RunId)
$ErrorActionPreference='Stop'
$taskRoot='D:\Codex\ticketing'
if($PSScriptRoot -ne "$taskRoot\scripts"){throw 'Wrong root'}
# Deliberately restricted to this known failed BIZ03 run, never old BIZ01/02 failure scenes.
$sourceRun='biz03-reg-20260912b-payments'
$source=Join-Path $taskRoot "evidence/BIZ-03-I-001/$sourceRun"
$evidence=Join-Path $taskRoot "evidence/BIZ-03-I-001/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Used runId'}
if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq 18083).Count){throw 'Application must be stopped'}
$completion=Get-Content -LiteralPath "$source/completion.json" -Raw | ConvertFrom-Json
$manifest=@(Get-Content -LiteralPath "$source/manifest.json" -Raw | ConvertFrom-Json)
if($completion.runId -ne $sourceRun -or $completion.cleaned -or $completion.fixtureCount -ne 53){throw 'Unexpected source proof'}
$passed=@($completion.passedCases)
if(($passed -join ',') -ne 'Q01,Q02,Q03,Q04,Q05,Q06,Q07,Q08,Q09,Q10,Q11,Q12,Q13'){throw 'Unexpected passed cases'}
$null=New-Item -ItemType Directory -Path $evidence
$secrets=Get-Content -LiteralPath "$taskRoot/.local/credentials.json" -Raw | ConvertFrom-Json
if($secrets.itApp -notmatch '^[a-f0-9]{64}$'){throw 'Private credential shape mismatch'}
function Invoke-FixtureSql([string]$Sql){
    $start=[Diagnostics.ProcessStartInfo]::new('D:\software\MySQL\Server\bin\mysql.exe')
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true
    $start.RedirectStandardInput=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    foreach($argument in @('--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13306','--user=ticketing_biz01_it_app','--database=ticketing_biz01_it','--ssl-mode=VERIFY_CA','--ssl-ca=D:\Codex\ticketing-e0\evidence\E0-I-001\mysql-ca.pem','--batch','--skip-column-names','--connect-timeout=3')){$start.ArgumentList.Add($argument)}
    $start.Environment['MYSQL_PWD']=$secrets.itApp
    $process=[Diagnostics.Process]::Start($start)
    $process.StandardInput.WriteLine($Sql);$process.StandardInput.Close()
    $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
    if(!$process.WaitForExit(15000)){$process.Kill();throw 'Own cleanup client timeout'}
    $output=$stdout.GetAwaiter().GetResult();$null=$stderr.GetAwaiter().GetResult()
    if($process.ExitCode -ne 0){throw 'Cleanup SQL failed; transaction disconnected, preserve evidence'}
    $process.Dispose();return $output.TrimEnd()
}
$selected=@();$retained=@()
foreach($f in $manifest){
    if($f.name -notmatch '^Q(0[1-9]|1[0-4])(?:-[A-Za-z0-9_-]+)?$'){throw 'Unexpected fixture name'}
    $code=$f.name.Substring(0,3)
    if($code -notin $passed){$retained+=@($f);continue}
    if(!(Test-Path -LiteralPath "$source/$($f.name)-facts.json")){throw 'Successful fixture facts absent'}
    foreach($field in @('userA','userB','event','session','tier','otherTier')){if([string]$f.$field -notmatch '^[1-9][0-9]*$' -or [long]$f.$field -le 0){throw 'Invalid exact ID'}}
    $selected+=@($f)
}
if($selected.Count -ne 32 -or $retained.Count -ne 21){throw 'Unexpected cleanup boundary'}
[IO.File]::WriteAllText("$evidence/cleanup-manifest.json",(@{sourceRun=$sourceRun;selected=$selected;retainedFailedQ14=$retained;sourceEvidenceUnchanged=$true}|ConvertTo-Json -Depth 8))
$completed=@()
foreach($f in $selected){
    $a=[long]$f.userA;$b=[long]$f.userB;$eventId=[long]$f.event;$sessionId=[long]$f.session;$tierId=[long]$f.tier;$otherTier=[long]$f.otherTier
    $prefix=$sourceRun+':'+$f.name
    $identity="SELECT CONCAT_WS(',',(SELECT COUNT(*) FROM app_user WHERE (id=$a AND subject_key='$($prefix):a') OR (id=$b AND subject_key='$($prefix):b')),(SELECT COUNT(*) FROM event WHERE id=$eventId AND title='$prefix'),(SELECT COUNT(*) FROM event_session WHERE id=$sessionId AND event_id=$eventId),(SELECT COUNT(*) FROM ticket_tier WHERE id IN ($tierId,$otherTier) AND session_id=$sessionId));"
    if((Invoke-FixtureSql $identity) -ne '2,1,1,2'){throw 'Ownership changed; stop before deleting this fixture'}
    $sql=@"
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;
DELETE e FROM outbox_event e JOIN payment_attempt p ON p.id=e.payment_attempt_id WHERE p.session_id=$sessionId AND p.user_id IN ($a,$b) AND e.event_type='PAYMENT_RESULT_RECORDED';
DELETE FROM payment_attempt WHERE session_id=$sessionId AND user_id IN ($a,$b);
DELETE l FROM inventory_ledger l JOIN booking_order o ON o.id=l.order_id WHERE o.session_id=$sessionId AND o.user_id IN ($a,$b) AND l.tier_id IN ($tierId,$otherTier);
DELETE FROM user_session_quota WHERE session_id=$sessionId AND user_id IN ($a,$b);
DELETE e FROM outbox_event e JOIN booking_request r ON r.id=e.booking_request_id WHERE r.session_id=$sessionId AND r.user_id IN ($a,$b) AND e.event_type='BOOKING_REQUESTED';
DELETE FROM booking_order WHERE session_id=$sessionId AND user_id IN ($a,$b);
DELETE FROM booking_request WHERE session_id=$sessionId AND user_id IN ($a,$b);
DELETE FROM ticket_tier WHERE id IN ($tierId,$otherTier) AND session_id=$sessionId;
DELETE FROM event_session WHERE id=$sessionId AND event_id=$eventId;
DELETE FROM event WHERE id=$eventId AND title='$prefix';
DELETE FROM app_user WHERE (id=$a AND subject_key='$($prefix):a') OR (id=$b AND subject_key='$($prefix):b');
COMMIT;
SELECT CONCAT_WS(',',(SELECT COUNT(*) FROM app_user WHERE id IN ($a,$b)),(SELECT COUNT(*) FROM event WHERE id=$eventId),(SELECT COUNT(*) FROM booking_request WHERE session_id=$sessionId),(SELECT COUNT(*) FROM booking_order WHERE session_id=$sessionId),(SELECT COUNT(*) FROM payment_attempt WHERE session_id=$sessionId));
"@
    if((Invoke-FixtureSql $sql) -ne '0,0,0,0,0'){throw 'Residual fixture rows; stop'}
    $completed+=@($f.name)
    [IO.File]::WriteAllText("$evidence/cleanup-progress.json",(@{sourceRun=$sourceRun;cleanedFixtures=$completed;retainedFailureFixtures=21;notARefund=$true}|ConvertTo-Json -Depth 6))
}
Write-Output "Cleaned only 32 proven-passed fixtures; retained 21 Q14 failure-scene fixtures and all original evidence. This is test-data cleanup, not a refund."
