$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'DestinationProxy.psm1') -Force
$passed = 0
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL: $Message" }
    $script:passed++
    Write-Output "PASS: $Message"
}

$prompt = New-DestinationPrompt '수원에 있는 큰 병원으로 가자'
Assert-True ($prompt.Contains('도로명 주소, 위도·경도')) 'prompt forbids fabricated addresses and coordinates'
Assert-True ($prompt.Contains('수원에 있는 큰 병원으로 가자')) 'prompt includes the exact user phrase'
$clarificationPrompt = New-DestinationPrompt '수원' '처음 발화: 큰 병원에 가자'
Assert-True ($clarificationPrompt.Contains('처음 발화: 큰 병원에 가자')) 'follow-up answer retains its original destination context'

$named = ConvertTo-ValidatedInterpretation ([pscustomobject]@{
    searchQuery = '수원 아주대학교병원'; needsClarification = $false; clarificationQuestion = ''
})
Assert-True ($named.searchQuery -eq '수원 아주대학교병원') 'valid place query is accepted'

$clarify = ConvertTo-ValidatedInterpretation ([pscustomobject]@{
    searchQuery = ''; needsClarification = $true; clarificationQuestion = '어느 동네에 있는 병원인가요?'
})
Assert-True ($clarify.needsClarification -and $clarify.clarificationQuestion.Length -gt 2) 'ambiguous phrase becomes a clarification question'

$fake = { param($text, $context) @{ searchQuery = '수원 아주대학교병원'; needsClarification = $false; clarificationQuestion = '' } }.GetNewClosure()
$validRequest = Invoke-DestinationProxyRequest 'POST' '/v1/destination/interpret' 'application/json' `
    '{"utterance":"수원에 있는 큰 병원으로 가자"}' '' $fake
Assert-True ($validRequest.Status -eq 200 -and $validRequest.Json.Contains('아주대학교병원')) 'request passes through model boundary and returns normalized query'
$contextRequest = Invoke-DestinationProxyRequest 'POST' '/v1/destination/interpret' 'application/json' `
    '{"utterance":"수원","clarificationContext":"처음 발화: 큰 병원에 가자"}' '' $fake
Assert-True ($contextRequest.Status -eq 200) 'follow-up information and original destination share the model request'

$badRequest = Invoke-DestinationProxyRequest 'POST' '/v1/destination/interpret' 'application/json' `
    '{"utterance":"x"}' '' $fake
Assert-True ($badRequest.Status -eq 400) 'short input is rejected before model invocation'

$missingKey = Invoke-DestinationProxyRequest 'POST' '/v1/destination/interpret' 'application/json' `
    '{"utterance":"서울역에 가자"}' '' $null
Assert-True ($missingKey.Status -eq 503) 'missing API key returns a configuration error without calling Google'

$fabricated = $false
try {
    ConvertTo-ValidatedInterpretation ([pscustomobject]@{
        searchQuery = ''; needsClarification = $false; clarificationQuestion = ''; address = 'invented'
    }) | Out-Null
} catch { $fabricated = $true }
Assert-True $fabricated 'invalid model result is rejected instead of inventing a location'

$port = 18766
$serverScript = Join-Path $PSScriptRoot 'server.ps1'
$tempBase = Join-Path ([System.IO.Path]::GetTempPath()) ('ma-proxy-test-' + [guid]::NewGuid().ToString('N'))
$stdoutPath = "$tempBase.out"
$stderrPath = "$tempBase.err"
$child = $null
try {
    $child = Start-Process -FilePath 'powershell.exe' -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$serverScript`"", '-Port', $port, '-TestMode'
    ) -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($child.HasExited) { break }
        try {
            $health = Invoke-RestMethod -Uri "http://localhost:$port/health" -TimeoutSec 1 -ErrorAction Stop
            if ($health.ok -and $health.configured) { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 200 }
    }
    Assert-True $ready 'loopback HTTP listener starts and reports health'
    $utf8 = [System.Text.Encoding]::UTF8.GetBytes('{"utterance":"큰 병원에 가자"}')
    $reply = Invoke-RestMethod -Method Post -Uri "http://localhost:$port/v1/destination/interpret" `
        -ContentType 'application/json; charset=utf-8' -Body $utf8 -TimeoutSec 3 -ErrorAction Stop
    Assert-True ($reply.needsClarification -and $reply.clarificationQuestion -eq '어느 동네에 있는 병원인가요?') 'HTTP request returns a Korean clarification question'
    $utf8 = [System.Text.Encoding]::UTF8.GetBytes('{"utterance":"수원","clarificationContext":"처음 발화: 큰 병원에 가자"}')
    $reply = Invoke-RestMethod -Method Post -Uri "http://localhost:$port/v1/destination/interpret" `
        -ContentType 'application/json; charset=utf-8' -Body $utf8 -TimeoutSec 3 -ErrorAction Stop
    Assert-True ($reply.searchQuery -eq '수원 아주대학교병원') 'clarification follow-up yields a normalized Korean place query'
} finally {
    if ($child -and -not $child.HasExited) { Stop-Process -Id $child.Id -Force -ErrorAction SilentlyContinue; [void]$child.WaitForExit(5000) }
    Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
}

Write-Output "Proxy tests: $passed passed, 0 failed"
