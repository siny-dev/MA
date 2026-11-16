param([switch]$Remove)
$ErrorActionPreference='Stop'

$startupDirectory=[Environment]::GetFolderPath('Startup')
$shortcutPath=Join-Path $startupDirectory 'MA Local AutoUpdate.lnk'
if($Remove){
    Remove-Item -LiteralPath $shortcutPath -Force -ErrorAction SilentlyContinue
    Write-Output 'MA local AutoUpdate will no longer start at sign-in.'
    exit 0
}

$powershell=Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$serverScript=Join-Path $PSScriptRoot 'start-local-update-server.ps1'
if(-not(Test-Path -LiteralPath $powershell) -or -not(Test-Path -LiteralPath $serverScript)){
    throw 'The local AutoUpdate startup files are missing.'
}

[void][IO.Directory]::CreateDirectory($startupDirectory)
$shell=New-Object -ComObject WScript.Shell
$shortcut=$shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath=$powershell
$shortcut.Arguments='-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$serverScript+'"'
$shortcut.WorkingDirectory=$PSScriptRoot
$shortcut.Description='Start the local MA AutoUpdate feed at Windows sign-in.'
$shortcut.WindowStyle=7
$shortcut.Save()
[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shortcut)
[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell)
Write-Output 'MA local AutoUpdate will start hidden at this Windows account sign-in.'
