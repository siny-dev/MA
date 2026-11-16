$ErrorActionPreference='Stop'
$PidPath=Join-Path $PSScriptRoot 'server.pid'
if(-not(Test-Path -LiteralPath $PidPath)){Write-Output 'MA local AutoUpdate is not running.';exit 0}
$processId=[int](Get-Content -Raw -LiteralPath $PidPath)
try{$process=Get-Process -Id $processId -ErrorAction Stop;if($process.ProcessName -eq 'node'){Stop-Process -Id $processId -Force}}catch{}
Remove-Item -LiteralPath $PidPath -Force -ErrorAction SilentlyContinue
Write-Output 'MA local AutoUpdate server stopped.'
