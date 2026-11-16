$ErrorActionPreference = 'Stop'
$scriptDir = $PSScriptRoot
$stateRoot = Join-Path $env:LOCALAPPDATA 'MA-gemini-proxy'
[void][System.IO.Directory]::CreateDirectory($stateRoot)
$pidFile = Join-Path $stateRoot 'server.pid'
if (Test-Path -LiteralPath $pidFile) {
    $oldPid = 0
    if ([int]::TryParse((Get-Content -Raw -LiteralPath $pidFile), [ref]$oldPid)) {
        try {
            $oldProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $oldPid" -ErrorAction Stop
            if ($oldProcess.CommandLine -like "*server.ps1* -Port 18765*") {
                $health = Invoke-RestMethod -Uri 'http://localhost:18765/health' -TimeoutSec 1 -ErrorAction Stop
                if ($health.ok) { exit 0 }
            }
        } catch { }
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

$hadExistingKey = -not [string]::IsNullOrWhiteSpace($env:GEMINI_API_KEY)
if (-not $hadExistingKey) {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    $form = New-Object System.Windows.Forms.Form
    $form.Text = 'MA · Gemini 장소 찾기'
    $form.StartPosition = 'CenterScreen'
    $form.Size = New-Object System.Drawing.Size(500, 190)
    $form.FormBorderStyle = 'FixedDialog'
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.TopMost = $true
    $label = New-Object System.Windows.Forms.Label
    $label.Text = 'Google AI Studio에서 만든 Gemini API 키를 입력하세요. 키는 파일이나 앱에 저장하지 않습니다.'
    $label.AutoSize = $false
    $label.SetBounds(16, 16, 450, 42)
    $box = New-Object System.Windows.Forms.TextBox
    $box.UseSystemPasswordChar = $true
    $box.SetBounds(16, 64, 450, 28)
    $ok = New-Object System.Windows.Forms.Button
    $ok.Text = '프록시 시작'
    $ok.DialogResult = [System.Windows.Forms.DialogResult]::OK
    $ok.SetBounds(264, 108, 96, 30)
    $cancel = New-Object System.Windows.Forms.Button
    $cancel.Text = '취소'
    $cancel.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
    $cancel.SetBounds(370, 108, 96, 30)
    $form.Controls.AddRange(@($label, $box, $ok, $cancel))
    $form.AcceptButton = $ok
    $form.CancelButton = $cancel
    $dialogResult = $form.ShowDialog()
    $typedKey = $box.Text.Trim()
    $form.Dispose()
    if ($dialogResult -ne [System.Windows.Forms.DialogResult]::OK) { exit 0 }
    if ([string]::IsNullOrWhiteSpace($typedKey)) { throw 'A Gemini API key is required.' }
    $env:GEMINI_API_KEY = $typedKey
    $typedKey = $null
}

if ([string]::IsNullOrWhiteSpace($env:GEMINI_API_KEY)) { throw 'A Gemini API key is required.' }
try {
    $serverScript = Join-Path $scriptDir 'server.ps1'
    $stdoutFile = Join-Path $stateRoot 'server.log'
    $stderrFile = Join-Path $stateRoot 'server-error.log'
    $arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$serverScript`" -Port 18765"
    $child = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
    Set-Content -LiteralPath $pidFile -Value $child.Id -Encoding ASCII
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($child.HasExited) { break }
        try {
            $health = Invoke-RestMethod -Uri 'http://localhost:18765/health' -TimeoutSec 1 -ErrorAction Stop
            if ($health.ok -and $health.configured) { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 200 }
    }
    if (-not $ready) {
        if (-not $child.HasExited) { Stop-Process -Id $child.Id -Force -ErrorAction SilentlyContinue }
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
        throw 'Gemini proxy could not start. See server-error.log under LocalAppData\MA-gemini-proxy.'
    }
    Write-Output 'MA Gemini proxy is running in the background on localhost:18765.'
    Write-Output 'Connect an Android device and run: adb reverse tcp:18765 tcp:18765'
} finally {
    if (-not $hadExistingKey) { Remove-Item Env:\GEMINI_API_KEY -ErrorAction SilentlyContinue }
}
