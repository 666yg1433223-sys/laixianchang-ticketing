param([Parameter(ValueFromRemainingArguments=$true)][string[]]$MavenArgs=@('test'))
$ErrorActionPreference='Stop'
$taskRoot='D:\Codex\ticketing'
if($PSScriptRoot -ne "$taskRoot\scripts"){throw 'Unexpected project root'}
$taskJava="C:\Users\l'z\.jdks\temurin-17.0.20.1\bin\java.exe"
$taskMaven='D:\software\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3'
$taskOptions=@('-Xmx512m',"-Dmaven.home=$taskMaven","-Dclassworlds.conf=$taskMaven\bin\m2.conf","-Dmaven.multiModuleProjectDirectory=$taskRoot",'-Dfile.encoding=UTF-8','-cp',"$taskMaven\boot\plexus-classworlds-2.11.0.jar",'org.codehaus.plexus.classworlds.launcher.Launcher','--batch-mode','--no-transfer-progress','--strict-checksums','-s',"$taskRoot\scripts\maven-settings.xml",'-gs',"$taskRoot\scripts\maven-settings.xml","-Dmaven.repo.local=$taskRoot\.m2",'-Daether.connector.connectTimeout=15000','-Daether.connector.requestTimeout=30000','-f',"$taskRoot\pom.xml")
Push-Location $taskRoot
try { & $taskJava @taskOptions @MavenArgs; $taskExit=$LASTEXITCODE } finally { Pop-Location }
exit $taskExit
