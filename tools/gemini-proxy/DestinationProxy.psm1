Set-StrictMode -Version Latest

$script:DestinationResponseSchema = @{
    type = 'OBJECT'
    properties = @{
        searchQuery = @{ type = 'STRING'; description = 'A concise Korean place-name search query, never an invented address.' }
        needsClarification = @{ type = 'BOOLEAN'; description = 'True if location or intended place is ambiguous.' }
        clarificationQuestion = @{ type = 'STRING'; description = 'One short, plain Korean question; empty when clarification is not needed.' }
    }
    required = @('searchQuery', 'needsClarification', 'clarificationQuestion')
    propertyOrdering = @('searchQuery', 'needsClarification', 'clarificationQuestion')
    additionalProperties = $false
}

function New-DestinationPrompt([string]$Utterance, [string]$ClarificationContext = '') {
    @(
        '당신은 한국의 고령자를 돕는 목적지 이름 해석기입니다.'
        '사용자의 말을 듣고 실제 지도 검색에 쓸 짧은 장소 이름만 정리하세요.'
        '사용자가 구어체, 별명, 불완전한 상호명으로 말해도 문맥이 분명하면 통용되는 장소 검색어로 바꾸세요.'
        '사용자가 말한 지역, 업종/장소 종류, 고유명사를 모두 보존하세요. 예: 부산 금정구 서동의 피부과는 부산 금정구 서동 피부과입니다. 서동만 반환하면 안 됩니다.'
        '사용자가 조건을 정정하면 최근에 말한 조건만 검색어에 반영하고, 이전 지역이나 업종을 함께 섞지 마세요.'
        '도로명 주소, 위도·경도, 상호를 만들어 내거나 추측하지 마세요. 실제 장소 결과는 별도 검색 API가 찾습니다.'
        '동네 정보가 빠져서 동명이 장소를 구분할 수 없거나 목적지가 모호하면 needsClarification=true로 하고 짧고 쉬운 한국어 질문을 하세요.'
        '질문이 필요 없으면 needsClarification=false, clarificationQuestion=""로 답하세요.'
        '집, 우리 집, 가족 집은 저장 주소가 제공되지 않았으므로 주소를 추측하지 말고 어느 동네/주소인지 물으세요.'
        '검색어는 120자 이내, 되묻는 질문은 160자 이내로 답하세요.'
        $(if ($ClarificationContext) { "앞서 나눈 목적지 대화 맥락: $ClarificationContext" } else { '' })
        "사용자 발화: $Utterance"
    ) -join "`n"
}

function ConvertTo-ValidatedInterpretation($Value) {
    if ($null -eq $Value -or $Value -is [array] -or
        $Value.needsClarification -isnot [bool] -or
        $Value.searchQuery -isnot [string] -or
        $Value.clarificationQuestion -isnot [string]) {
        throw 'invalid-model-response'
    }
    $query = $Value.searchQuery.Trim()
    $question = $Value.clarificationQuestion.Trim()
    if ($Value.needsClarification) {
        if ($question.Length -lt 2 -or $question.Length -gt 160) { throw 'invalid-model-response' }
        return [pscustomobject]@{ searchQuery = ''; needsClarification = $true; clarificationQuestion = $question }
    }
    if ($query.Length -lt 2 -or $query.Length -gt 120 -or $question.Length -ne 0) { throw 'invalid-model-response' }
    return [pscustomobject]@{ searchQuery = $query; needsClarification = $false; clarificationQuestion = '' }
}

function Invoke-GeminiDestination([string]$ApiKey, [string]$Utterance, [string]$ClarificationContext, [scriptblock]$MockInvoker) {
    if ($MockInvoker) { return ConvertTo-ValidatedInterpretation (& $MockInvoker $Utterance $ClarificationContext) }
    if ([string]::IsNullOrWhiteSpace($ApiKey)) { throw 'not-configured' }
    $payload = @{
        contents = @(@{ role = 'user'; parts = @(@{ text = (New-DestinationPrompt $Utterance $ClarificationContext) }) })
        generationConfig = @{
            responseMimeType = 'application/json'
            responseSchema = $script:DestinationResponseSchema
        }
    } | ConvertTo-Json -Depth 10 -Compress
    try {
        $upstream = Invoke-RestMethod -Method Post `
            -Uri 'https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent' `
            -Headers @{ 'x-goog-api-key' = $ApiKey } `
            -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($payload)) -TimeoutSec 20 -ErrorAction Stop
        $candidate = @($upstream.candidates[0].content.parts | Where-Object { $_.text -is [string] } | Select-Object -First 1)
        if ($candidate.Count -eq 0 -or $candidate[0].text.Length -gt 4096) { throw 'invalid-model-response' }
        $modelResult = ConvertFrom-Json -InputObject $candidate[0].text -ErrorAction Stop
        return ConvertTo-ValidatedInterpretation $modelResult
    } catch {
        throw 'destination-ai-unavailable'
    }
}

function ConvertTo-ProxyJson([int]$Status, $Body) {
    [pscustomobject]@{ Status = $Status; Json = ($Body | ConvertTo-Json -Depth 6 -Compress) }
}

function Invoke-DestinationProxyRequest([string]$Method, [string]$Path, [string]$ContentType,
    [string]$BodyText, [string]$ApiKey, [scriptblock]$MockInvoker) {
    if ($Method -eq 'GET' -and $Path -eq '/health') {
        return ConvertTo-ProxyJson 200 @{ ok = $true; configured = [bool]($ApiKey -or $MockInvoker) }
    }
    if ($Method -ne 'POST' -or $Path -ne '/v1/destination/interpret') {
        return ConvertTo-ProxyJson 404 @{ error = 'not-found' }
    }
    if ($ContentType -notmatch '^application/json(?:\s*;|$)') {
        return ConvertTo-ProxyJson 415 @{ error = 'json-required' }
    }
    try { $input = ConvertFrom-Json -InputObject $BodyText -ErrorAction Stop }
    catch { return ConvertTo-ProxyJson 400 @{ error = 'invalid-json' } }
    $clarificationContext = ''
    if ($input.PSObject.Properties['clarificationContext']) { $clarificationContext = $input.clarificationContext }
    if ($input.utterance -isnot [string] -or $input.utterance.Trim().Length -lt 2 -or
        $input.utterance.Trim().Length -gt 200 -or
        ($clarificationContext -and ($clarificationContext -isnot [string] -or $clarificationContext.Length -gt 400))) {
        return ConvertTo-ProxyJson 400 @{ error = 'invalid-utterance' }
    }
    if (-not $ApiKey -and -not $MockInvoker) { return ConvertTo-ProxyJson 503 @{ error = 'not-configured' } }
    try {
        $context = if ($clarificationContext -is [string]) { $clarificationContext.Trim() } else { '' }
        $result = Invoke-GeminiDestination $ApiKey $input.utterance.Trim() $context $MockInvoker
        return ConvertTo-ProxyJson 200 $result
    } catch {
        return ConvertTo-ProxyJson 502 @{ error = 'destination-ai-unavailable' }
    }
}

Export-ModuleMember -Function New-DestinationPrompt, ConvertTo-ValidatedInterpretation, Invoke-GeminiDestination, Invoke-DestinationProxyRequest
