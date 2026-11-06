param([switch]$UseSystemProxy, [switch]$DebugApk)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot
$names=@('JAVA_HOME','GRADLE_USER_HOME','ANDROID_HOME','ANDROID_USER_HOME')
$before=@{}
foreach($name in $names) { $before[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
Push-Location $project
try {
    $local=@{}
    $localFile=Join-Path $project 'build.local.json'
    if(Test-Path -LiteralPath $localFile) { $local=Get-Content -LiteralPath $localFile -Raw | ConvertFrom-Json -AsHashtable }
    foreach($name in $names) { if($local[$name]) { [Environment]::SetEnvironmentVariable($name,[string]$local[$name],'Process') } }
    $arguments=@('--console=plain')
    if($UseSystemProxy) {
        $proxy=Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
        if($proxy.ProxyEnable -ne 1 -or $proxy.ProxyServer -notmatch '^(?<host>[^:;=]+):(?<port>\d+)$') { throw 'Unsupported or disabled system proxy. Run without -UseSystemProxy or configure Gradle locally.' }
        $arguments+=@("-Dhttp.proxyHost=$($Matches.host)","-Dhttp.proxyPort=$($Matches.port)","-Dhttps.proxyHost=$($Matches.host)","-Dhttps.proxyPort=$($Matches.port)")
    }
    $arguments+=@(':app:testReleaseUnitTest',':app:lintRelease',$(if($DebugApk) { ':app:assembleDebug' } else { ':app:assembleRelease' }))
    if(!$DebugApk -and !(Test-Path -LiteralPath 'signing.properties') -and !$env:SIGNING_STORE_FILE) { throw 'Release signing missing. See README.md or use -DebugApk.' }
    $gradle=if($local['gradleExecutable']) { [string]$local['gradleExecutable'] } else { Join-Path $project 'gradlew.bat' }
    & $gradle @arguments
    if($LASTEXITCODE -ne 0) { throw "Build failed: $LASTEXITCODE" }
} finally {
    Pop-Location
    foreach($name in $names) { [Environment]::SetEnvironmentVariable($name,$before[$name],'Process') }
}