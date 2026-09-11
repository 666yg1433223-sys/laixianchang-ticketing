param([string]$LogName='central-download-recovery.json')
$ErrorActionPreference='Stop'
$taskRoot='D:\Codex\ticketing'
if($PSScriptRoot -ne "$taskRoot\scripts" -or $LogName -notmatch '^[a-z0-9-]+\.json$'){throw 'Unexpected scope'}
$cacheRoot=[IO.Path]::GetFullPath("$taskRoot\.m2")
$results=@()
function Get-OfficialReply([string]$url,[string]$file=''){
 for($attempt=1;$attempt -le 3;$attempt++){
  try {
   if($file){Invoke-WebRequest -Uri $url -OutFile $file -TimeoutSec 20; return}
   return Invoke-WebRequest -Uri $url -TimeoutSec 20
  }catch{if([int]$_.Exception.Response.StatusCode -eq 404 -or $attempt -eq 3){throw}}
 }
}
foreach($marker in (Get-ChildItem -LiteralPath $cacheRoot -Filter '*.lastUpdated' -Recurse -File)){
 $target=$marker.FullName.Substring(0,$marker.FullName.Length-12)
 if(-not $target.StartsWith($cacheRoot+'\') -or $target -notmatch '\.(pom|jar)$'){continue}
 $relative=[IO.Path]::GetRelativePath($cacheRoot,$target).Replace('\','/')
 $url='https://repo.maven.apache.org/maven2/'+$relative
 $algorithm='SHA256'
 try {$checksum=(Get-OfficialReply ($url+'.sha256')).Content.Trim()}
 catch {if([int]$_.Exception.Response.StatusCode -ne 404){throw}; $algorithm='SHA1'; $checksum=(Get-OfficialReply ($url+'.sha1')).Content.Trim()}
 $expectedLength=if($algorithm -eq 'SHA256'){64}else{40}
 if($checksum -notmatch "^[a-fA-F0-9]{$expectedLength}$"){throw 'Invalid official checksum'}
 if(Test-Path -LiteralPath $target){
  $existing=(Get-FileHash -LiteralPath $target -Algorithm $algorithm).Hash
  if($existing -ne $checksum){throw 'Existing artifact differs from official checksum; preserve and stop'}
  $results += [pscustomobject]@{url=$url;algorithm=$algorithm;official=$checksum;actual=$existing;matched=$true;existingVerified=$true}
  continue
 }
 Get-OfficialReply $url $target
 $actual=(Get-FileHash -LiteralPath $target -Algorithm $algorithm).Hash
 if($actual -ne $checksum){throw 'Checksum mismatch; preserve file and stop'}
 $results += [pscustomobject]@{url=$url;algorithm=$algorithm;official=$checksum;actual=$actual;matched=$true}
}
[IO.File]::WriteAllText("$taskRoot\evidence\BIZ-01-I-001\$LogName",($results | ConvertTo-Json -Depth 5))
$results | ConvertTo-Json -Depth 5
