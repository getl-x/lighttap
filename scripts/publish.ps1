param(
    [string]$Repository = 'getl-x/lighttap',
    [switch]$UseSystemProxy,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot
Push-Location $project
$javaBefore = $env:JAVA_HOME
try {
    if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid repository name' }
    $ghCommand = Get-Command gh -ErrorAction SilentlyContinue
    $gh = if ($ghCommand) { $ghCommand.Source } else { Join-Path $env:ProgramFiles 'GitHub CLI\gh.exe' }
    if (!(Test-Path -LiteralPath $gh)) { throw 'Install GitHub CLI and run gh auth login first' }
    $gradleText = Get-Content -LiteralPath 'app/build.gradle.kts' -Raw
    $versionMatch = [regex]::Match($gradleText, 'versionName\s*=\s*"(?<version>[^"]+)"')
    $version = $versionMatch.Groups['version'].Value
    if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Set a valid semantic versionName before publishing' }
    $tag = "v$version"
    $commit = git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'A committed Git checkout is required' }
    $changes = git status --porcelain
    if ($changes) { throw 'Commit and push source changes before publishing' }
    $remoteCommit = & $gh api "repos/$Repository/commits/$commit" --jq .sha
    if ($LASTEXITCODE -ne 0 -or $remoteCommit -ne $commit) { throw 'Push this commit to the target repository before publishing' }
    $local = @{}
    if (Test-Path -LiteralPath 'build.local.json') { $local = Get-Content -LiteralPath 'build.local.json' -Raw | ConvertFrom-Json -AsHashtable }
    if ($local['JAVA_HOME']) { $env:JAVA_HOME = [string]$local['JAVA_HOME'] }
    $sdk = if ($local['ANDROID_HOME']) { [string]$local['ANDROID_HOME'] } else { $env:ANDROID_HOME }
    if (!$sdk) { throw 'Configure ANDROID_HOME or build.local.json for local signing verification' }
    $tools = Join-Path $sdk 'build-tools/36.0.0'
    foreach ($tool in @('apksigner.bat', 'aapt2.exe')) {
        if (!(Test-Path -LiteralPath (Join-Path $tools $tool))) { throw "Missing Android Build Tools executable: $tool" }
    }
    if (!(Test-Path -LiteralPath 'signing.properties') -and !$env:SIGNING_STORE_FILE) { throw 'Configure local release signing first; no signing keys are uploaded by this script' }
    if ($CheckOnly) {
        Write-Output "Publish configuration verified: $Repository $tag at $commit. No build or remote writes performed."
        return
    }
    & $gh release view $tag --repo $Repository --json tagName *> $null
    if ($LASTEXITCODE -eq 0) { throw "Release $tag already exists; increase versionName and versionCode for the next release" }
    & (Join-Path $PSScriptRoot 'build.ps1') -UseSystemProxy:$UseSystemProxy
    if (!$?) { throw 'Build or verification failed' }
    $builtApk = Join-Path $project 'app/build/outputs/apk/release/app-release.apk'
    $signature = & (Join-Path $tools 'apksigner.bat') verify --verbose --print-certs $builtApk 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'Release APK signature verification failed' }
    $badging = & (Join-Path $tools 'aapt2.exe') dump badging $builtApk | Out-String
    if ($LASTEXITCODE -ne 0 -or !$badging.Contains("name='cn.lighttap.app'") -or !$badging.Contains("versionName='$version'") -or $badging.Contains('application-debuggable')) { throw 'Unexpected package, version or debug APK' }
    $output = Join-Path $project "deliverables/release-$tag"
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $assetName = "lighttap-$version-release.apk"
    $apk = Join-Path $output $assetName
    Copy-Item -LiteralPath $builtApk -Destination $apk -Force
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    $checksum = Join-Path $output 'SHA256SUMS.txt'
    [IO.File]::WriteAllText($checksum, "$hash  $assetName`n", [Text.UTF8Encoding]::new($false))
    $notesFile = Join-Path $output 'release-notes.md'
    $notes = "轻点 $version`n`n下载 **$assetName** 安装。最低 Android 8.0，需手动开启点击无障碍服务。`n`n发布前已运行单元测试、Android Lint 和签名验证。正式签名密钥保留在本机，不会作为 Release 附件上传。`n`nSHA256SUMS.txt 提供 APK 校验值；源码对应提交 $commit。真机兼容性请按文档验收。`n"
    [IO.File]::WriteAllText($notesFile, $notes, [Text.UTF8Encoding]::new($false))
    # Only these two explicit assets are allowed; never upload a directory or wildcard.
    & $gh release create $tag $apk $checksum --repo $Repository --target $commit --title "轻点 $tag" --notes-file $notesFile --draft
    if ($LASTEXITCODE -ne 0) { throw 'Draft release creation failed' }
    $releases = & $gh api "repos/$Repository/releases" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify draft assets; draft remains unpublished' }
    $matches = @($releases | Where-Object tag_name -eq $tag)
    if ($matches.Count -ne 1) { throw 'Cannot uniquely identify draft release' }
    $release = $matches[0]
    $apkAsset = @($release.assets | Where-Object name -eq $assetName)
    $sumAsset = @($release.assets | Where-Object name -eq 'SHA256SUMS.txt')
    if (@($release.assets).Count -ne 2 -or $apkAsset.Count -ne 1 -or $sumAsset.Count -ne 1 -or $apkAsset[0].digest -ne "sha256:$hash") { throw 'Draft asset verification failed; draft remains unpublished' }
    $sumHash = (Get-FileHash -LiteralPath $checksum -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sumAsset[0].digest -ne "sha256:$sumHash") { throw 'Checksum asset verification failed; draft remains unpublished' }
    & $gh release edit $tag --repo $Repository --draft=false --latest
    if ($LASTEXITCODE -ne 0) { throw 'Release publication failed' }
    & $gh release view $tag --repo $Repository --json url --jq .url
    if ($LASTEXITCODE -ne 0) { throw 'Published release URL unavailable' }
} finally {
    $env:JAVA_HOME = $javaBefore
    Pop-Location
}