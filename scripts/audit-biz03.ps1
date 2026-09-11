param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]{8,24}$')][string]$RunId)
$ErrorActionPreference='Stop'
$taskRoot='D:\Codex\ticketing'
if($PSScriptRoot -ne "$taskRoot\scripts"){throw 'Wrong project root'}
$evidence=Join-Path $taskRoot "evidence/BIZ-03-I-001/$RunId"
if(Test-Path -LiteralPath $evidence){throw 'Audit runId already used'}
$null=New-Item -ItemType Directory -Path $evidence
$secrets=Get-Content -LiteralPath "$taskRoot\.local\credentials.json" -Raw | ConvertFrom-Json
$audits=@()
foreach($entry in @(
    @{user='ticketing_biz01_it_app';db='ticketing_biz01_it';key='itApp'},
    @{user='ticketing_biz01_it_migrator';db='ticketing_biz01_it';key='itMigrator'}
)){
    $password=$secrets.($entry.key)
    if($password -notmatch '^[a-f0-9]{64}$'){throw 'Private credential shape mismatch'}
    $sql="SELECT DATABASE(),CURRENT_USER(); SHOW STATUS LIKE 'Ssl_cipher'; SHOW GRANTS FOR CURRENT_USER(); SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();"
    if($entry.user -eq 'ticketing_biz01_it_app'){
        $sql+=" SELECT installed_rank,version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;"
        foreach($table in @('app_user','event','event_session','ticket_tier','booking_request','booking_order','user_session_quota','inventory_ledger','outbox_event','payment_attempt')){
            $sql+=" SELECT '$table',COUNT(*) FROM $table;"
        }
        $sql+=" SELECT id,title FROM event ORDER BY id; SELECT status,COUNT(*) FROM booking_order GROUP BY status; SELECT result_status,settlement_status,COUNT(*) FROM payment_attempt GROUP BY result_status,settlement_status;"
    }
    $start=[Diagnostics.ProcessStartInfo]::new('D:\software\MySQL\Server\bin\mysql.exe')
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true
    $start.RedirectStandardInput=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    foreach($argument in @('--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13306',"--user=$($entry.user)","--database=$($entry.db)",'--ssl-mode=VERIFY_CA','--ssl-ca=D:\Codex\ticketing-e0\evidence\E0-I-001\mysql-ca.pem','--batch','--skip-column-names','--connect-timeout=3')){
        $start.ArgumentList.Add($argument)
    }
    $start.Environment['MYSQL_PWD']=$password
    $process=[Diagnostics.Process]::Start($start)
    $process.StandardInput.WriteLine($sql);$process.StandardInput.Close()
    $outputTask=$process.StandardOutput.ReadToEndAsync();$errorTask=$process.StandardError.ReadToEndAsync()
    if(!$process.WaitForExit(15000)){$process.Kill();throw 'Owned audit client timed out'}
    $output=$outputTask.GetAwaiter().GetResult()
    $null=$errorTask.GetAwaiter().GetResult()
    if($process.ExitCode -ne 0){throw 'Restricted database audit failed; credentials not logged'}
    $audits+=@{user=$entry.user;database=$entry.db;exitCode=$process.ExitCode;output=@($output.TrimEnd() -split "\r?\n")}
    $process.Dispose()
}
$keys=[Collections.Generic.HashSet[string]]::new()
$taskEvidence=Join-Path $taskRoot 'evidence/BIZ-03-I-001'
foreach($manifest in (Get-ChildItem -LiteralPath $taskEvidence -Filter 'redis-key-manifest.json' -File -Recurse)){
    $sourceRun=$manifest.Directory.Name
    foreach($key in (Get-Content -LiteralPath $manifest.FullName -Raw | ConvertFrom-Json)){
        if(!$key.StartsWith("ticketing:biz01:it:$sourceRun"+':rl:') -or $key -notmatch ':rl:\{[1-9][0-9]*\}:(u:[1-9][0-9]*|t):[0-9]+$'){throw 'Foreign Redis key in manifest'}
        $null=$keys.Add($key)
    }
}
$client=[Net.Sockets.TcpClient]::new()
$connection=$client.ConnectAsync('127.0.0.1',16379)
if(!$connection.Wait(3000)){throw 'Redis audit connection timed out'}
$stream=$client.GetStream();$stream.ReadTimeout=3000;$stream.WriteTimeout=3000
$reader=[IO.StreamReader]::new($stream,[Text.Encoding]::UTF8,$false,1024,$true)
function Send-AuditRedis([string[]]$Parts){
    $builder=[Text.StringBuilder]::new()
    $null=$builder.Append('*').Append($Parts.Count).Append("`r`n")
    foreach($part in $Parts){
        $null=$builder.Append('$').Append([Text.Encoding]::UTF8.GetByteCount($part)).Append("`r`n").Append($part).Append("`r`n")
    }
    $bytes=[Text.Encoding]::UTF8.GetBytes($builder.ToString());$stream.Write($bytes,0,$bytes.Length);$stream.Flush()
    $line=$reader.ReadLine()
    if($null -eq $line -or $line.StartsWith('-')){throw 'Redis audit protocol failed'}
    return $line
}
try{
    $redisPassword=(Get-Content -LiteralPath 'D:\Codex\ticketing-e0\infra\.local\redis-password' -Raw).Trim()
    if((Send-AuditRedis @('AUTH',$redisPassword)) -ne '+OK'){throw 'Redis audit authentication failed'}
    $existing=0
    if($keys.Count -gt 0){
        $reply=Send-AuditRedis (@('EXISTS')+@($keys | Sort-Object))
        if(!$reply.StartsWith(':')){throw 'Unexpected EXISTS result'}
        $existing=[int]$reply.Substring(1)
    }
}finally{$reader.Dispose();$stream.Dispose();$client.Dispose()}
$listeners=@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object {$_.Port -in @(18082,18083)} | ForEach-Object ToString)
$result=[ordered]@{task='BIZ-03-I-001';runId=$RunId;at=(Get-Date -Format o);accountAudits=$audits;redis=@{preciseKeysChecked=$keys.Count;existingKeyCount=$existing;readOnly=$true;keys=@($keys | Sort-Object)};applicationListeners=$listeners}
[IO.File]::WriteAllText((Join-Path $evidence 'resource-audit.json'),($result | ConvertTo-Json -Depth 8))
if($existing -ne 0 -or $listeners.Count -ne 0){throw 'Audit found leftovers; retained evidence for investigation'}
Write-Output "Restricted read-only audit saved; Redis keys checked=$($keys.Count), existing=$existing, Java listeners=$($listeners.Count)"
