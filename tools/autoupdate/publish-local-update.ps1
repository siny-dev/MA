param([string]$HostAddress='')
$ErrorActionPreference='Stop'
$ProjectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$VersionPath=Join-Path $ProjectRoot 'version.json'
$AssetVersionPath=Join-Path $ProjectRoot 'app\src\main\assets\version.json'
$MetadataPath=Join-Path $ProjectRoot 'mobile-app.json'
$BuildScript=Join-Path $ProjectRoot 'build-mobile.ps1'
$Port=18766

function Get-PrivateHost([string]$Value){
    if($Value -notmatch '^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$'){return $false}
    $parts=$Value.Split('.')|ForEach-Object {[int]$_}
    if($parts|Where-Object {$_ -lt 0 -or $_ -gt 255}){return $false}
    return $parts[0] -eq 10 -or ($parts[0] -eq 172 -and $parts[1] -ge 16 -and $parts[1] -le 31) -or ($parts[0] -eq 192 -and $parts[1] -eq 168) -or ($parts[0] -eq 169 -and $parts[1] -eq 254)
}

if([string]::IsNullOrWhiteSpace($HostAddress)){$HostAddress=$env:MA_LOCAL_UPDATE_HOST}
if([string]::IsNullOrWhiteSpace($HostAddress)){
    $HostAddress=(Get-NetIPConfiguration | Where-Object {$_.NetAdapter.Status -eq 'Up' -and $_.IPv4DefaultGateway} | ForEach-Object {$_.IPv4Address.IPAddress} | Where-Object {Get-PrivateHost $_} | Select-Object -First 1)
}
if(-not(Get-PrivateHost $HostAddress)){throw 'Connect this PC to a private network or pass -HostAddress with its private IPv4 address.'}

$versionRaw=[IO.File]::ReadAllText($VersionPath)
$assetVersionRaw=[IO.File]::ReadAllText($AssetVersionPath)
$version=$versionRaw|ConvertFrom-Json
$metadata=Get-Content -Raw -LiteralPath $MetadataPath|ConvertFrom-Json
$nextCode=[long]$version.version_code+1
$versionParts=([string]$version.version).Split('.')
if($versionParts.Count -ne 3){throw 'version.json must contain a three-part semantic version.'}
$versionParts[2]=([int]$versionParts[2]+1).ToString()
$nextVersion=$versionParts -join '.'
$version.version=$nextVersion
$version.version_code=$nextCode
$versionJson=($version|ConvertTo-Json -Depth 8)+[Environment]::NewLine
$manifestUrl="http://${HostAddress}:${Port}/$($metadata.app_id)/manifest.json"
$feedDirectory=Join-Path $PSScriptRoot ('feed\'+$metadata.app_id)
$publishedApk=Join-Path $feedDirectory ('update-'+$nextCode+'.apk')
$temporaryApk=$publishedApk+'.part'
$temporaryManifest=Join-Path $feedDirectory 'manifest.json.part'
$feedCommitted=$false

try{
    [IO.File]::WriteAllText($VersionPath,$versionJson,[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($AssetVersionPath,$versionJson,[Text.UTF8Encoding]::new($false))
    & (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $BuildScript -Task debug -UpdateManifestUrl $manifestUrl
    if($LASTEXITCODE -ne 0){throw "Local update APK build failed with exit code $LASTEXITCODE."}
    $builtApk=Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if(-not(Test-Path -LiteralPath $builtApk)){throw 'The build completed without an APK.'}
    [void][IO.Directory]::CreateDirectory($feedDirectory)
    Copy-Item -LiteralPath $builtApk -Destination $temporaryApk -Force
    Move-Item -LiteralPath $temporaryApk -Destination $publishedApk -Force
    $sha=(Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest=[ordered]@{
        schema=1
        app_id=$metadata.app_id
        package_name=$metadata.application_id
        platform='android'
        version=$nextVersion
        version_code=$nextCode
        apk_url="http://${HostAddress}:${Port}/$($metadata.app_id)/update-${nextCode}.apk"
        sha256=$sha
    }
    [IO.File]::WriteAllText($temporaryManifest,(ConvertTo-Json $manifest -Depth 5)+"`n",[Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryManifest -Destination (Join-Path $feedDirectory 'manifest.json') -Force
    $feedCommitted=$true
    & (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'start-local-update-server.ps1') -HostAddress $HostAddress
    if($LASTEXITCODE -ne 0){throw "Local update server failed to start with exit code $LASTEXITCODE."}
    & (Join-Path $PSScriptRoot 'install-local-update-startup.ps1')
    Write-Output "Published local MA update $nextVersion ($nextCode)"
    Write-Output "Manifest: $manifestUrl"
    Write-Output "APK: $publishedApk"
}catch{
    if(Test-Path -LiteralPath $temporaryApk){Remove-Item -LiteralPath $temporaryApk -Force}
    if(Test-Path -LiteralPath $temporaryManifest){Remove-Item -LiteralPath $temporaryManifest -Force}
    if(-not $feedCommitted){
        [IO.File]::WriteAllText($VersionPath,$versionRaw,[Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText($AssetVersionPath,$assetVersionRaw,[Text.UTF8Encoding]::new($false))
    }
    throw
}
