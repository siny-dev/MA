$ErrorActionPreference = 'Stop'
$stateRoot = Join-Path $env:LOCALAPPDATA 'MA-gemini-proxy'
$pidFile = Join-Path $stateRoot 'server.pid'
if (-not (Test-Path -LiteralPath $pidFile)) {
    Write-Output 'MA Gemini proxy is not recorded as running.'
    exit 0
}
$serverId = 0
if (-not [int]::TryParse((Get-Content -Raw -LiteralPath $pidFile), [ref]$serverId)) {
    Remove-Item -LiteralPath $pidFile -Force
    throw 'The proxy process record is invalid.'
}
$processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $serverId" -ErrorAction SilentlyContinue
if ($processInfo -and $processInfo.Name -ieq 'powershell.exe' -and
    $processInfo.CommandLine -like '*server.ps1* -Port 18765*') {
    Stop-Process -Id $serverId -Force
    Write-Output 'MA Gemini proxy stopped.'
} else {
    Write-Output 'The recorded process is no longer the MA Gemini proxy.'
}
Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
