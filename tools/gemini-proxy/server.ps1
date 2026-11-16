param(
    [ValidateRange(1024, 65535)][int]$Port = 18765,
    [switch]$TestMode
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'DestinationProxy.psm1') -Force
$apiKey = if ($TestMode) { '' } else { $env:GEMINI_API_KEY }
$mockInvoker = $null
if ($TestMode) {
    $mockInvoker = {
        param($utterance)
        if ($utterance -match '^큰 병원') {
            return @{ searchQuery = ''; needsClarification = $true; clarificationQuestion = '어느 동네에 있는 병원인가요?' }
        }
        return @{ searchQuery = '수원 아주대학교병원'; needsClarification = $false; clarificationQuestion = '' }
    }.GetNewClosure()
}
if (-not $TestMode -and [string]::IsNullOrWhiteSpace($apiKey)) {
    throw 'GEMINI_API_KEY is required. Run run.ps1 to enter it without saving it to disk.'
}
$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://localhost:$Port/")
$listener.Start()
Write-Output "MA destination AI proxy listening on localhost:$Port (Ctrl+C to stop)"
try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $request = $context.Request
        $response = $context.Response
        try {
            if ($request.ContentLength64 -gt 4096) {
                $result = [pscustomobject]@{ Status = 413; Json = '{"error":"body-too-large"}' }
                $request.InputStream.Close()
            } else {
                $reader = [System.IO.StreamReader]::new($request.InputStream, $request.ContentEncoding)
                $bodyText = $reader.ReadToEnd()
                $reader.Dispose()
                $result = Invoke-DestinationProxyRequest $request.HttpMethod $request.Url.AbsolutePath `
                    $request.ContentType $bodyText $apiKey $mockInvoker
            }
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($result.Json)
            $response.StatusCode = $result.Status
            $response.ContentType = 'application/json; charset=utf-8'
            $response.ContentLength64 = $bytes.Length
            $response.Headers['Cache-Control'] = 'no-store'
            $response.Headers['X-Content-Type-Options'] = 'nosniff'
            $response.OutputStream.Write($bytes, 0, $bytes.Length)
        } catch {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes('{"error":"destination-ai-unavailable"}')
            $response.StatusCode = 502
            $response.ContentType = 'application/json; charset=utf-8'
            $response.ContentLength64 = $bytes.Length
            $response.OutputStream.Write($bytes, 0, $bytes.Length)
        } finally {
            $response.Close()
        }
    }
} finally {
    if ($listener.IsListening) { $listener.Stop() }
    $listener.Close()
}
