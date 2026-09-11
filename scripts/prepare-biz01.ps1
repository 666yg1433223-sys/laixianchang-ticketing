$ErrorActionPreference='Stop'
$taskRoot='D:\Codex\ticketing'
if($PSScriptRoot -ne "$taskRoot\scripts"){throw 'Wrong root'}
$taskDocker="C:\Users\l'z\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"
$taskContainer='ticketing-e0-i001-mysql-1'
$owned=(& $taskDocker --context desktop-linux inspect $taskContainer | ConvertFrom-Json)[0]
if($LASTEXITCODE -ne 0 -or $owned.Config.Labels.'e0.task' -ne 'E0-I-001' -or $owned.Config.Labels.'com.docker.compose.project.working_dir' -ne 'D:\Codex\ticketing-e0\infra' -or $owned.State.Status -ne 'running'){throw 'Container ownership/status mismatch'}
$binding=$owned.HostConfig.PortBindings.'3306/tcp'
if($binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or $binding[0].HostPort -ne '13306'){throw 'Wrong endpoint'}
function Invoke-Admin([string]$sql){
 $rootSecret=[IO.File]::ReadAllText('D:\Codex\ticketing-e0\infra\.local\mysql-root-password')
 try {
  $raw=@("$rootSecret`n$sql" | & $taskDocker --context desktop-linux exec -i $taskContainer sh -c 'IFS= read -r MYSQL_PWD; export MYSQL_PWD; exec mysql --protocol=socket --user=root --batch --raw --skip-column-names' 2>&1)
  if($LASTEXITCODE -ne 0){throw 'Scoped provisioning failed; preserve private state and inspect without resetting credentials'}
  return ($raw -join "`n")
 } finally {$rootSecret=$null}
}
$existing=Invoke-Admin "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ('ticketing_biz01','ticketing_biz01_it'); SELECT COUNT(*) FROM mysql.user WHERE User IN ('ticketing_biz01_app','ticketing_biz01_it_app','ticketing_biz01_migrator','ticketing_biz01_it_migrator');"
if($existing.Trim() -notmatch '^0\s+0$'){throw 'Named DB/account already exists; do not overwrite'}
$private=Join-Path $taskRoot '.local'
if(Test-Path -LiteralPath $private){throw 'Private state already exists; do not regenerate'}
$null=New-Item -ItemType Directory -Path $private
$acl=[Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true,$false)
foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'),[Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))){
 $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
}
Set-Acl -LiteralPath $private -AclObject $acl
$secrets=[ordered]@{}
foreach($name in @('bizApp','itApp','bizMigrator','itMigrator','tokenA','tokenB','trustPassword')){
 $secrets[$name]=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
}
[IO.File]::WriteAllText((Join-Path $private 'credentials.json'),($secrets | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$template=Get-Content -LiteralPath "$taskRoot\ops\provision-biz01.sql" -Raw
foreach($name in @('bizApp','itApp','bizMigrator','itMigrator')){$template=$template.Replace('{{'+$name+'}}',$secrets[$name])}
$grants=Invoke-Admin $template
$template=$null
$env:BIZ_STORE_PASSWORD=$secrets.trustPassword
try {
 & "C:\Users\l'z\.jdks\temurin-17.0.20.1\bin\keytool.exe" -importcert -noprompt -alias biz01-mysql-ca -file 'D:\Codex\ticketing-e0\evidence\E0-I-001\mysql-ca.pem' -keystore "$private\mysql-trust.p12" -storetype PKCS12 -storepass:env BIZ_STORE_PASSWORD
 if($LASTEXITCODE -ne 0){throw 'Truststore generation failed'}
} finally {Remove-Item Env:\BIZ_STORE_PASSWORD}
$evidence="$taskRoot\evidence\BIZ-01-I-001"
$null=New-Item -ItemType Directory -Path $evidence -Force
$result=[ordered]@{task='BIZ-01-I-001';at=(Get-Date -Format o);container=$taskContainer;endpoint='127.0.0.1:13306';beforeCounts=$existing;createdDatabases=@('ticketing_biz01','ticketing_biz01_it');accountHost='%';grants=$grants;privateAclProtected=(Get-Acl -LiteralPath $private).AreAccessRulesProtected;secretValuesLogged=$false;formalDatabaseMigrated=$false}
[IO.File]::WriteAllText("$evidence\provision.json",($result | ConvertTo-Json -Depth 8))
$result | ConvertTo-Json -Depth 8
