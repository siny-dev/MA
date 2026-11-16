param([string]$HostAddress='')
$ErrorActionPreference='Stop'
$Port=18766
$ServerScript=Join-Path $PSScriptRoot 'server.mjs'
$PidPath=Join-Path $PSScriptRoot 'server.pid'
$StdoutPath=Join-Path $PSScriptRoot 'server.stdout.log'
$StderrPath=Join-Path $PSScriptRoot 'server.stderr.log'
if([string]::IsNullOrWhiteSpace($HostAddress)){$HostAddress=$env:MA_LOCAL_UPDATE_HOST}
if([string]::IsNullOrWhiteSpace($HostAddress)){
    for($attempt=0;$attempt -lt 30 -and [string]::IsNullOrWhiteSpace($HostAddress);$attempt++){
        try{
            $HostAddress=(Get-NetIPConfiguration | Where-Object {$_.NetAdapter.Status -eq 'Up' -and $_.IPv4DefaultGateway} | ForEach-Object {$_.IPv4Address.IPAddress} | Where-Object {$_ -match '^(10\.|172\.(1[6-9]|2\d|3[01])\.|192\.168\.)'} | Select-Object -First 1)
        }catch{}
        if([string]::IsNullOrWhiteSpace($HostAddress)){Start-Sleep -Seconds 2}
    }
}
if($HostAddress -notmatch '^(10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|169\.254\.\d{1,3}\.\d{1,3})$'){
    throw 'A private IPv4 address is required to run MA AutoUpdate on the local network.'
}
$HealthUrl="http://${HostAddress}:${Port}/healthz"
try{if((Invoke-WebRequest -UseBasicParsing -Uri $HealthUrl -TimeoutSec 2).Content -eq 'ok'){Write-Output "MA local AutoUpdate is already running at http://${HostAddress}:${Port}";exit 0}}catch{}

$ruleName='MA AutoUpdate local feed 18766'
try{
    $privateFirewallEnabled=[bool](Get-NetFirewallProfile -Name Private -ErrorAction Stop).Enabled
    if($privateFirewallEnabled){
        $existingRule=& (Join-Path $env:SystemRoot 'System32\netsh.exe') advfirewall firewall show rule name=$ruleName 2>$null
        if($LASTEXITCODE -ne 0){
            & (Join-Path $env:SystemRoot 'System32\netsh.exe') advfirewall firewall add rule name=$ruleName dir=in action=allow protocol=TCP localport=$Port remoteip=localsubnet profile=private | Out-Null
            if($LASTEXITCODE -ne 0){Write-Warning 'Private-network firewall rule was not created; another PC may not be able to reach this feed.'}
        }
    }
}catch{Write-Warning 'Private-network firewall status could not be checked.'}

$node=(Get-Command node.exe -ErrorAction Stop).Source
$arguments=@($ServerScript,'--host',$HostAddress,'--port',"$Port",'--root',(Join-Path $PSScriptRoot 'feed'))
$process=Start-Process -FilePath $node -ArgumentList $arguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput $StdoutPath -RedirectStandardError $StderrPath -PassThru
[IO.File]::WriteAllText($PidPath,[string]$process.Id,[Text.Encoding]::ASCII)
for($attempt=0;$attempt -lt 20;$attempt++){
    Start-Sleep -Milliseconds 250
    try{if((Invoke-WebRequest -UseBasicParsing -Uri $HealthUrl -TimeoutSec 1).Content -eq 'ok'){Write-Output "MA local AutoUpdate is running at http://${HostAddress}:${Port}";exit 0}}catch{}
    if($process.HasExited){throw "MA update server stopped during startup. See $StderrPath"}
}
throw "MA update server did not become ready. See $StderrPath"
