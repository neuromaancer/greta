param(
    [string]$Runtime,
    [string]$Replay,
    [switch]$Preview,
    [switch]$MeaningMiner,
    [switch]$Muted,
    [string]$Distribution = 'Ubuntu',
    [string]$PythonPath = $env:GRETA_MODIFF_PYTHON,
    [string]$JavaHome,
    [ValidateRange(0,15)][int]$StartDelaySeconds = 15,
    [ValidateRange(1,5)][int]$TurnGapSeconds = 3,
    [ValidateRange(0,15)][int]$EndHoldSeconds = 10
)
# Opt-in virtual-AU engineering prototype. No dialogue generation or microphone.
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $Runtime) { $Runtime = Join-Path (Split-Path $source -Parent) 'greta_2.1' }
$Runtime = (Resolve-Path -LiteralPath $Runtime).Path
$launcher = Join-Path $Runtime 'bin\GMIT\Start-GMIT.ps1'
$modelRoot = Join-Path $Runtime 'bin\Common\Data\MODIFFVQ'
$adapter = Join-Path $PSScriptRoot 'modiff_vq.py'
foreach ($required in @($launcher, $adapter, (Join-Path $modelRoot 'model\norm_stats.npy'),
    (Join-Path $modelRoot 'model\vqvae_model.pt'), (Join-Path $modelRoot 'model\predictor_model.pt'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing required file: $required" }
}
if ($Replay) {
    if (-not [IO.Path]::IsPathRooted($Replay)) { throw '-Replay must be an absolute Windows path.' }
    $Replay = (Resolve-Path -LiteralPath $Replay).Path
}
if ($Preview -and $Replay) { throw '-Preview uses the authored GroupMI scene; omit -Replay.' }
$scenario = if ($Replay) { $Replay } else { Join-Path $Runtime 'bin\Examples\GMIT\group-mi-red-meat.xml' }
$xmlSettings = New-Object System.Xml.XmlReaderSettings
$xmlSettings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
$xmlSettings.XmlResolver = $null
$xmlReader = [System.Xml.XmlReader]::Create($scenario, $xmlSettings)
try {
    $document = New-Object System.Xml.XmlDocument
    $document.XmlResolver = $null
    $document.Load($xmlReader)
    $turnCount = $document.SelectNodes('/gmit-session/turn').Count
} finally { $xmlReader.Dispose() }
if ($turnCount -lt 1 -or $turnCount -gt 256) { throw 'Expected a bounded replay with 1 to 256 chunks.' }
$workerSeconds = 180 + $StartDelaySeconds + $EndHoldSeconds + $turnCount * (27 + $TurnGapSeconds)
if ($Preview) { $workerSeconds = 240 }
if ($MeaningMiner) { $workerSeconds += 90 + $turnCount * (30 + 6) }
if ($workerSeconds -gt 3600) { throw 'This experimental mode supports a replay budget of at most one hour. Use a shorter replay.' }
if (Get-Process java,javaw,Greta_No_Facial_expressions,Greta_Facial_expressions -ErrorAction SilentlyContinue) {
    throw 'Close the other Java/Greta instance before starting this bounded run.'
}
function Convert-ToWslPath([string]$Path) {
    return Invoke-WslRead @('-d', $Distribution, '--exec', 'wslpath', '-a', '-u', $Path)
}
function Quote-ProcessArgument([string]$Value) {
    # Arguments below are paths or validated values, never executable shell text.
    if ($Value.Contains('"') -or $Value.Contains("`r") -or $Value.Contains("`n")) {
        throw 'Quotes and newlines are unsupported in process arguments.'
    }
    if ($Value -match '\s') { return '"' + $Value + '"' }
    return $Value
}
function Invoke-WslRead([string[]]$Arguments) {
    # Capture redirected handles explicitly, including PowerShell invoked from WSL.
    $probe = New-Object System.Diagnostics.Process
    $probe.StartInfo.FileName = 'wsl.exe'
    $probe.StartInfo.Arguments = ($Arguments | ForEach-Object { Quote-ProcessArgument $_ }) -join ' '
    $probe.StartInfo.UseShellExecute = $false
    $probe.StartInfo.CreateNoWindow = $true
    $probe.StartInfo.RedirectStandardOutput = $true
    $probe.StartInfo.RedirectStandardError = $true
    try {
        $null = $probe.Start()
        $readOut = $probe.StandardOutput.ReadToEndAsync()
        $readErr = $probe.StandardError.ReadToEndAsync()
        if (-not $probe.WaitForExit(30000)) { $probe.Kill(); $probe.WaitForExit(); throw 'WSL path lookup timed out.' }
        if ($probe.ExitCode -ne 0 -or -not $readOut.Result.Trim()) { throw ('WSL lookup failed: ' + $readErr.Result) }
        return $readOut.Result.Trim()
    } finally { $probe.Dispose() }
}
if (-not $PythonPath) {
    $linuxUserHome = Invoke-WslRead @('-d', $Distribution, '--exec', 'printenv', 'HOME')
    $PythonPath = $linuxUserHome + '/.local/share/greta-gmit/venv-rl/bin/python'
}
$run = Join-Path $source ('bin\GMIT\local\modiff-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $run | Out-Null
$linuxAdapter = Convert-ToWslPath $adapter
$linuxModels = Convert-ToWslPath $modelRoot
$linuxRun = Convert-ToWslPath $run
$arguments = @('-d', $Distribution, '--exec', 'env', 'PYTHONDONTWRITEBYTECODE=1',
    $PythonPath, $linuxAdapter, '--model-root', $linuxModels, '--serve-dir', $linuxRun,
    '--max-seconds', [string]$workerSeconds)
$worker = New-Object System.Diagnostics.Process
$worker.StartInfo.FileName = 'wsl.exe'
$worker.StartInfo.Arguments = ($arguments | ForEach-Object { Quote-ProcessArgument $_ }) -join ' '
$worker.StartInfo.WorkingDirectory = $source
$worker.StartInfo.UseShellExecute = $false
$worker.StartInfo.CreateNoWindow = $true
$worker.StartInfo.RedirectStandardOutput = $true
$worker.StartInfo.RedirectStandardError = $true
$workerStarted = $false
try {
    Write-Output "MODIFF local evidence: $run"
    Write-Output 'Loading CPU model; virtual-AU adaptation is an engineering test, not validated human-expression inference.'
    $null = $worker.Start(); $workerStarted = $true
    $stdout = $worker.StandardOutput.ReadToEndAsync()
    $stderr = $worker.StandardError.ReadToEndAsync()
    $ready = Join-Path $run 'ready.json'
    $deadline = (Get-Date).AddSeconds(90)
    while (-not (Test-Path -LiteralPath $ready)) {
        if ($worker.HasExited) { throw ('MODIFF worker exited before readiness: ' + $stderr.Result) }
        if ((Get-Date) -gt $deadline) { throw 'MODIFF checkpoint loading exceeded 90 seconds.' }
        Start-Sleep -Milliseconds 250
    }
    $readiness = Get-Content -LiteralPath $ready -Raw | ConvertFrom-Json
    if ($readiness.protocol -ne 'gmit-modiff-vq/1' -or $readiness.status -ne 'ready' -or $worker.HasExited) {
        throw 'MODIFF did not report an active, compatible ready state.'
    }
    Write-Output 'MODIFF worker ready. Opening the seated scene.'
    $launch = @{ ModiffDirectory=$run; StartDelaySeconds=$StartDelaySeconds;
        TurnGapSeconds=$TurnGapSeconds; EndHoldSeconds=$EndHoldSeconds }
    if ($JavaHome) { $launch.JavaHome = $JavaHome }
    if ($Replay) { $launch.Replay = $Replay } else { $launch.GroupMI = $true }
    if ($Preview) { $launch.Preview = $true }
    if ($MeaningMiner) { $launch.MeaningMiner = $true }
    if ($Muted) { $launch.Muted = $true }
    & $launcher @launch
    if (Test-Path -LiteralPath (Join-Path $run 'error.json')) {
        throw ('MODIFF failed; inspect ' + (Join-Path $run 'error.json'))
    }
} finally {
    if ($workerStarted) {
        [IO.File]::WriteAllText((Join-Path $run 'stop'), 'stop')
        if (-not $worker.WaitForExit(15000)) {
            # Only the owned WSL client is stopped; the worker also has a finite lifetime.
            $worker.Kill(); $worker.WaitForExit()
            Write-Warning 'MODIFF worker did not acknowledge shutdown; inspect its local logs.'
        }
        [IO.File]::WriteAllText((Join-Path $run 'worker.stdout.log'), $stdout.Result)
        [IO.File]::WriteAllText((Join-Path $run 'worker.stderr.log'), $stderr.Result)
        Write-Output ('MODIFF worker exit: ' + $worker.ExitCode)
        $worker.Dispose()
    }
}
