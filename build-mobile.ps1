param([ValidateSet('test','debug','all','release')][string]$Task='all',[string]$UpdateManifestUrl='')
$ErrorActionPreference='Stop'
$GitHubReleasePublisher=Join-Path $PSScriptRoot 'tools\autoupdate\publish-github-release.py'
if($Task -eq 'release'){
    & python $GitHubReleasePublisher
    if($LASTEXITCODE -ne 0){throw "MA GitHub release failed with exit code $LASTEXITCODE."}
    return
}
$Toolchain='C:\AI-Work\Toolchains\MobileBasicTool'
$JavaHome=Join-Path $Toolchain 'jdk17\jdk-17.0.21+9'
$AndroidHome=Join-Path $Toolchain 'android-sdk'
$env:JAVA_HOME=$JavaHome
$env:ANDROID_HOME=$AndroidHome
$env:ANDROID_SDK_ROOT=$AndroidHome
$env:PATH=(Join-Path $JavaHome 'bin')+';'+(Join-Path $AndroidHome 'platform-tools')+';'+$env:PATH
$env:GRADLE_USER_HOME=Join-Path $Toolchain 'gradle-home'
$GradleJar=Join-Path $Toolchain 'gradle-9.6.0\gradle-9.6.0\lib\gradle-gradle-cli-main-9.6.0.jar'
if(-not(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))){throw 'The bundled JDK 17 toolchain is missing.'}
if(-not(Test-Path -LiteralPath $GradleJar)){throw 'The bundled Gradle 9.6.0 toolchain is missing.'}
if(-not(Test-Path -LiteralPath (Join-Path $AndroidHome 'platforms\android-37.0\android.jar'))){throw 'Android SDK Platform 37.0 is missing.'}
$StageBase=Join-Path $Toolchain 'build-staging'
[void][IO.Directory]::CreateDirectory($StageBase)
$StageRoot=Join-Path $StageBase ('project-'+[guid]::NewGuid().ToString('N'))
$StagePrefix=[IO.Path]::GetFullPath($StageBase).TrimEnd('\')+'\'
$buildSucceeded=$false
function Copy-ProjectTree([string]$Source,[string]$Destination){
    foreach($item in (Get-ChildItem -LiteralPath $Source -Force)){
        if($item.PSIsContainer){
            if($item.Name -in @('.git','.gradle','build','.idea','.venv','__pycache__')){continue}
            $target=Join-Path $Destination $item.Name
            [void][IO.Directory]::CreateDirectory($target)
            Copy-ProjectTree $item.FullName $target
        }else{
            if($item.Name -ieq 'local.properties' -or $item.Extension -in @('.apk','.aab','.pyc','.pyo')){continue}
            Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $Destination $item.Name) -Force
        }
    }
}
try{
    [void][IO.Directory]::CreateDirectory($StageRoot)
    Copy-ProjectTree $PSScriptRoot $StageRoot
    Set-Content -LiteralPath (Join-Path $StageRoot 'local.properties') -Value ('sdk.dir='+$AndroidHome.Replace('\','/')) -Encoding ASCII
    if(-not[string]::IsNullOrWhiteSpace($UpdateManifestUrl)){
        $manifestUri=[Uri]$UpdateManifestUrl
        if($manifestUri.Scheme -ne 'http' -or $manifestUri.Host -notmatch '^(localhost|127\.0\.0\.1|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3})$'){
            throw 'The local debug update manifest must use localhost or a private IPv4 address over HTTP.'
        }
        $debugNetworkConfig=Join-Path $StageRoot 'app\src\debug\res\xml\network_security_config_debug.xml'
        $networkConfigText=[IO.File]::ReadAllText($debugNetworkConfig)
        $hostXml=[Security.SecurityElement]::Escape($manifestUri.Host)
        $networkConfigText=$networkConfigText.Replace('</network-security-config>',('    <domain-config cleartextTrafficPermitted="true"><domain includeSubdomains="false">'+$hostXml+'</domain></domain-config>'+[Environment]::NewLine+'</network-security-config>'))
        [IO.File]::WriteAllText($debugNetworkConfig,$networkConfigText,[Text.UTF8Encoding]::new($false))
    }
    $tasks=@();if($Task -in @('test','all')){$tasks+=@('testDebugUnitTest')};if($Task -in @('debug','all')){$tasks+=@('assembleDebug')}
    $StdoutLog=Join-Path $StageRoot 'gradle.stdout.log'
    $StderrLog=Join-Path $StageRoot 'gradle.stderr.log'
    $GradleArgs=@('-Xmx2g','-Dfile.encoding=UTF-8','-jar',$GradleJar)+$tasks
    if(-not[string]::IsNullOrWhiteSpace($UpdateManifestUrl)){$GradleArgs+=('-PmaUpdateManifestUrl='+$UpdateManifestUrl)}
    $GradleArgs+=@('--no-daemon')
    $GradleProcess=Start-Process -FilePath (Join-Path $JavaHome 'bin\java.exe') -ArgumentList $GradleArgs -WorkingDirectory $StageRoot -NoNewWindow -Wait -PassThru -RedirectStandardOutput $StdoutLog -RedirectStandardError $StderrLog
    if(Test-Path -LiteralPath $StdoutLog){Get-Content -LiteralPath $StdoutLog|Write-Output}
    if(Test-Path -LiteralPath $StderrLog){Get-Content -LiteralPath $StderrLog|Write-Output}
    if($GradleProcess.ExitCode -ne 0){throw "Gradle failed with exit code $($GradleProcess.ExitCode)"}
    if($Task -in @('test','all')){
        $testResults=Join-Path $StageRoot 'app\build\test-results\testDebugUnitTest'
        if(-not(Test-Path -LiteralPath $testResults)){throw 'Gradle completed without test results.'}
        $testDestination=Join-Path $PSScriptRoot 'app\build\test-results\testDebugUnitTest'
        [void][IO.Directory]::CreateDirectory($testDestination)
        Get-ChildItem -LiteralPath $testResults -Force|Copy-Item -Destination $testDestination -Recurse -Force
        $testReport=Join-Path $StageRoot 'app\build\reports\tests\testDebugUnitTest'
        if(Test-Path -LiteralPath $testReport){$reportDestination=Join-Path $PSScriptRoot 'app\build\reports\tests\testDebugUnitTest';[void][IO.Directory]::CreateDirectory($reportDestination);Get-ChildItem -LiteralPath $testReport -Force|Copy-Item -Destination $reportDestination -Recurse -Force}
    }
    if($Task -in @('debug','all')){
        $builtApk=Join-Path $StageRoot 'app\build\outputs\apk\debug\app-debug.apk'
        if(-not(Test-Path -LiteralPath $builtApk)){throw 'Gradle completed without producing the debug APK.'}
        $destination=Join-Path $PSScriptRoot 'app\build\outputs\apk\debug'
        [void][IO.Directory]::CreateDirectory($destination)
        $projectApk=Join-Path $destination 'app-debug.apk'
        Copy-Item -LiteralPath $builtApk -Destination $projectApk -Force
        $metadata=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'mobile-app.json')|ConvertFrom-Json
        if($metadata.app_id -notmatch '^[a-z0-9][a-z0-9._-]{0,62}$'){throw 'The generated app_id is invalid.'}
        $portableOutput=Join-Path $Toolchain 'outputs'
        [void][IO.Directory]::CreateDirectory($portableOutput)
        $portableApk=Join-Path $portableOutput ($metadata.app_id+'-debug.apk')
        Copy-Item -LiteralPath $builtApk -Destination $portableApk -Force
        Write-Output ('Project APK: '+$projectApk)
        Write-Output ('ASCII-path APK: '+$portableApk)
    }
    $buildSucceeded=$true
}finally{
    Set-Location -LiteralPath $Toolchain
    $resolved=[IO.Path]::GetFullPath($StageRoot)
    if((Test-Path -LiteralPath $resolved) -and $resolved.StartsWith($StagePrefix,[StringComparison]::OrdinalIgnoreCase)){
        for($attempt=0;$attempt -lt 5 -and (Test-Path -LiteralPath $resolved);$attempt++){
            try{Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction Stop}catch{Start-Sleep -Seconds 1}
        }
        if(Test-Path -LiteralPath $resolved){Write-Warning ('Build staging is still in use and will need cleanup: '+$resolved)}
    }
}
