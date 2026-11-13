param(
    [switch]$Smoke, [switch]$GroupMI, [string]$Replay, [switch]$Preview, [switch]$Original, [string]$JavaHome,
    [switch]$DisableListenerGaze, [switch]$DisableListenerNods, [switch]$DisableTabletopArms,
    [switch]$MeaningMiner, [switch]$DisableHandSafety,
    [switch]$Muted, [switch]$RecordVideo,
    [string]$AzurePython,
    [string]$ModiffDirectory,
    [ValidateRange(0,15)][int]$StartDelaySeconds = 0,
    [ValidateRange(1,5)][int]$TurnGapSeconds = 1,
    [ValidateRange(0,15)][int]$EndHoldSeconds = 0
)
$ErrorActionPreference = 'Stop'
$modes = @($Smoke, $GroupMI, [bool]$Replay, $Original) | Where-Object { $_ }
if ($modes.Count -gt 1) {
    throw 'Choose only one of -Smoke, -GroupMI, -Replay, or -Original.'
}
$automatic = $Smoke -or $GroupMI -or [bool]$Replay
if ($RecordVideo -and ((-not ($GroupMI -or $Replay)) -or $Preview)) {
    throw '-RecordVideo requires a GroupMI/Replay run with speech.'
}
if ($Muted -and -not $automatic) { throw '-Muted requires an automatic Smoke/GroupMI/Replay run.' }
if ($AzurePython) {
    if ((-not ($GroupMI -or $Replay)) -or $Preview) { throw '-AzurePython requires a GroupMI/Replay run with speech.' }
    if (-not [IO.Path]::IsPathRooted($AzurePython) -or -not (Test-Path -LiteralPath $AzurePython -PathType Leaf)) {
        throw '-AzurePython must point to an absolute Windows Python executable with the Azure Speech SDK installed.'
    }
}
if ($MeaningMiner -and ((-not ($GroupMI -or $Replay)) -or $DisableTabletopArms)) {
    throw 'MeaningMiner requires a seated GroupMI/Replay run with tabletop arms enabled.'
}
if ($ModiffDirectory) {
    if ((-not ($GroupMI -or $Replay)) -or $DisableListenerGaze) {
        throw 'MODIFF requires a seated GroupMI/Replay run with gaze enabled.'
    }
    if (-not [IO.Path]::IsPathRooted($ModiffDirectory)) { throw '-ModiffDirectory must be absolute.' }
    if (-not (Test-Path -LiteralPath (Join-Path $ModiffDirectory 'ready.json'))) {
        throw 'The MODIFF worker is not ready. Use integrations/gmit/Start-GMIT-Modiff.ps1.'
    }
}
if ($Preview -and -not $GroupMI) { throw '-Preview requires -GroupMI and renders the seated scene without speech.' }
if ($GroupMI) {
    # Give observers time to locate the avatars and read each caption.
    if (-not $PSBoundParameters.ContainsKey('StartDelaySeconds')) { $StartDelaySeconds = 10 }
    if (-not $PSBoundParameters.ContainsKey('TurnGapSeconds')) { $TurnGapSeconds = 3 }
    if (-not $PSBoundParameters.ContainsKey('EndHoldSeconds')) { $EndHoldSeconds = 10 }
}
if ($Replay -and -not [IO.Path]::IsPathRooted($Replay)) { throw '-Replay must be an absolute scenario XML path.' }
$bin = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) {
    $jdk = Get-ChildItem 'C:\Program Files\Java' -Directory -Filter 'jdk1.8*' | Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $JavaHome = $jdk.FullName }
}
$java = Join-Path $JavaHome 'bin\java.exe'
$javac = Join-Path $JavaHome 'bin\javac.exe'
if (-not (Test-Path $java)) { throw 'Set -JavaHome to the existing 64-bit JDK 8 directory.' }
if (Get-Process java,javaw,Greta_No_Facial_expressions,Greta_Facial_expressions -ErrorAction SilentlyContinue) {
    throw 'A Java/Greta process is already running. Close the other instance before this single-instance test.'
}
$config = 'Configurations\Greta\GMIT\GMIT - 3 characters - CereProc.xml'
if ($Original) { $config = 'Configurations\Greta\Basics\Greta - 2 characters - CereProc.xml' }
$turnCount = 4
$scenario = $null
if ($GroupMI) {
    $scenario = Join-Path $bin 'Examples\GMIT\group-mi-red-meat.xml'
    $settings = New-Object System.Xml.XmlReaderSettings
    $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [System.Xml.XmlReader]::Create($scenario, $settings)
    try {
        $document = New-Object System.Xml.XmlDocument
        $document.XmlResolver = $null
        $document.Load($reader)
        $turnCount = $document.SelectNodes('/gmit-session/turn').Count
    } finally { $reader.Dispose() }
    if ($turnCount -lt 4 -or $turnCount -gt 16) { throw 'The synthetic group MI scenario must contain 4 to 16 turns.' }
}
if ($Replay) {
    $scenario = (Resolve-Path -LiteralPath $Replay -ErrorAction Stop).Path
    $settings = New-Object System.Xml.XmlReaderSettings
    $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit; $settings.XmlResolver = $null
    $reader = [System.Xml.XmlReader]::Create($scenario, $settings)
    try { $document = New-Object System.Xml.XmlDocument; $document.XmlResolver = $null; $document.Load($reader); $turnCount = $document.SelectNodes('/gmit-session/turn').Count } finally { $reader.Dispose() }
    if ($turnCount -lt 1 -or $turnCount -gt 256) { throw 'A recorded replay scenario must contain 1 to 256 chunks.' }
}
# 120 seconds for graph/camera setup and cleanup, plus bounded per-turn
# scheduling (10), audio (15), the chosen gap, and a 2-second capture margin.
# Smoke remains capped at 278 seconds; a 16-turn scenario is capped at 662.
$runTimeoutSeconds = 120 + $StartDelaySeconds + $EndHoldSeconds + $turnCount * (10 + 15 + $TurnGapSeconds + 2)
# Three bounded extractor initializations, one bounded analysis per turn,
# and a conservative 9-second seated gesture return budget per turn. This
# covers the 8.240-second bounded restore plus observation margin.
if ($MeaningMiner) { $runTimeoutSeconds += 90 + $turnCount * (30 + 9) }
if ($AzurePython) { $runTimeoutSeconds += 25 * $turnCount }
$runPrefix = if ($GroupMI) { 'group-mi-' } elseif ($Replay) { 'replay-' } else { 'run-' }
$run = Join-Path $PSScriptRoot ('local\' + $runPrefix + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $run | Out-Null
# These existing runtime scratch files are restored even when startup/playback fails.
$mutable = @('ModularSave.xml', 'fml_to_bml.xml', 'fml_output_mm.xml', 'fml_output.xml', 'test_fml.xml', 'Log\Ogre.log', 'Player\Lib\External\Win64\Plugins_OpenGL.cfg')
$present = @{}
foreach ($name in $mutable) {
    $path = Join-Path $bin $name
    $present[$name] = Test-Path $path
    if ($present[$name]) { Copy-Item -LiteralPath $path -Destination (Join-Path $run (($name -replace '\\','_') + '.before')) }
}
Push-Location $bin
$process = $null
try {
    if ($automatic) {
        $compileArgs = @('-encoding', 'UTF-8', '-classpath', ('"' + (Join-Path $bin 'Modular.jar') + '"'), '-d', ('"' + $run + '"'), ('"' + (Join-Path $PSScriptRoot 'SmokeProbe.java') + '"'), ('"' + (Join-Path $PSScriptRoot 'SeatedScene.java') + '"'), ('"' + (Join-Path $PSScriptRoot 'ListenerGaze.java') + '"'), ('"' + (Join-Path $PSScriptRoot 'GazeModiffAdapter.java') + '"'))
        $compileArgs += @(('"' + (Join-Path $PSScriptRoot 'MeaningMinerBridge.java') + '"'), ('"' + (Join-Path $PSScriptRoot 'MeaningGesture.java') + '"'), ('"' + (Join-Path $PSScriptRoot 'HandCollision.java') + '"'))
        $compileArgs += ('"' + (Join-Path $PSScriptRoot 'ReplaySubtitles.java') + '"')
        $compileArgs += ('"' + (Join-Path $PSScriptRoot 'ReplayVideo.java') + '"')
        $compileArgs += ('"' + (Join-Path $PSScriptRoot 'AzurePatientSpeech.java') + '"')
        # Own the handle before starting: very short-lived javac processes can
        # otherwise lose their exit status in Windows PowerShell Start-Process.
        $compiler = New-Object System.Diagnostics.Process
        $compiler.StartInfo.FileName = $javac
        $compiler.StartInfo.Arguments = $compileArgs -join ' '
        $compiler.StartInfo.WorkingDirectory = $bin
        $compiler.StartInfo.UseShellExecute = $false
        $compiler.StartInfo.RedirectStandardOutput = $true
        $compiler.StartInfo.RedirectStandardError = $true
        $null = $compiler.Start()
        $compileOut = $compiler.StandardOutput.ReadToEndAsync()
        $compileErr = $compiler.StandardError.ReadToEndAsync()
        if (-not $compiler.WaitForExit(30000)) { $compiler.Kill(); $compiler.WaitForExit(); throw 'SmokeProbe compilation timed out.' }
        [IO.File]::WriteAllText((Join-Path $run 'compile.stdout.log'), $compileOut.Result)
        [IO.File]::WriteAllText((Join-Path $run 'compile.stderr.log'), $compileErr.Result)
        if ($compiler.ExitCode -ne 0 -or -not (Test-Path (Join-Path $run 'SmokeProbe.class'))) { throw ('SmokeProbe compilation failed (exit ' + $compiler.ExitCode + '); see ' + $run) }
        $arguments = @('-cp', ('"' + $run + ';Modular.jar"'), 'SmokeProbe', ('"' + $config + '"'), ('"' + $run + '"'), "$StartDelaySeconds", "$TurnGapSeconds", "$EndHoldSeconds")
        if ($Preview) { $arguments = @('-Dgmit.preview=true') + $arguments }
        if ($DisableListenerGaze) { $arguments = @('-Dgmit.gaze.enabled=false') + $arguments }
        if ($DisableListenerNods) { $arguments = @('-Dgmit.gaze.backchannel=false') + $arguments }
        if ($DisableTabletopArms) { $arguments = @('-Dgmit.pose.arms=false') + $arguments }
        if ($MeaningMiner) { $arguments = @('-Dgmit.meaningminer.enabled=true') + $arguments }
        if ($DisableHandSafety) { $arguments = @('-Dgmit.meaningminer.handSafety=false') + $arguments }
        if ($Muted) { $arguments = @('-Dgmit.audio.muted=true') + $arguments }
        if ($RecordVideo) { $arguments = @('-Dgmit.video.enabled=true') + $arguments }
        if ($AzurePython) { $arguments = @(('"-Dgmit.azure.python=' + $AzurePython + '"')) + $arguments }
        if ($ModiffDirectory) { $arguments = @(('"-Dgmit.modiff.dir=' + $ModiffDirectory + '"')) + $arguments }
        if ($GroupMI -or $Replay) { $arguments += ('"' + $scenario + '"') }
    } else { $arguments = @('-jar', 'Modular.jar', ('"' + $config + '"')) }
    Write-Output ('GMIT run directory: ' + $run)
    if ($GroupMI) { Write-Output ('Synthetic authored group MI demo: ' + $turnCount + ' turns; no live GMIT or dialogue generation.') }
    if ($Replay) { Write-Output ('Offline recorded replay: ' + $turnCount + ' chunks; no live GMIT or dialogue generation.') }
    if ($ModiffDirectory) { Write-Output 'Experimental facilitator eyebrow adaptation uses the external local MODIFF worker.' }
    if ($MeaningMiner) { Write-Output 'MeaningMiner realizes original semantic gesture lexicons for all three speakers through one speech path per turn.' }
    if ($Muted) { Write-Output 'Audio output is muted; TTS, lip animation, and speech scheduling remain active.' }
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $bin -RedirectStandardOutput (Join-Path $run 'stdout.log') -RedirectStandardError (Join-Path $run 'stderr.log') -PassThru
    $null = $process.Handle
    if ($automatic) {
        if (-not $process.WaitForExit($runTimeoutSeconds * 1000)) { $process.Kill(); $process.WaitForExit(); throw ('Synthetic playback timed out after ' + $runTimeoutSeconds + ' seconds; only its own Java process was stopped.') }
    } else { $process.WaitForExit() }
    if ($process.ExitCode -ne 0) { throw ('Greta exited with code ' + $process.ExitCode + '; inspect logs in ' + $run) }
} finally {
    if ($process -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
    foreach ($name in $mutable) {
        $path = Join-Path $bin $name
        if (Test-Path $path) { Copy-Item -LiteralPath $path -Destination (Join-Path $run (($name -replace '\\','_') + '.after')) }
        if ($present[$name]) { Copy-Item -LiteralPath (Join-Path $run (($name -replace '\\','_') + '.before')) -Destination $path -Force }
        elseif (Test-Path $path) { Remove-Item -LiteralPath $path }
    }
    Pop-Location
}
