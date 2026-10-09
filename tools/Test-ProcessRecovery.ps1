param([string]$Serial, [ValidateSet('ForceStop','Reboot')][string]$Mode = 'ForceStop', [string]$Sdk = (Join-Path $PSScriptRoot '../.tools/sdk'))
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adbPath = Join-Path $Sdk 'platform-tools/adb.exe'
$deviceArgs = @(); if ($Serial) { $deviceArgs = @('-s', $Serial) }
function Invoke-ProofAdb([string[]]$Arguments) {
    $result = & $adbPath @deviceArgs @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return ($result -join "`n")
}
Push-Location $projectRoot
$holder = $null
try {
    Invoke-ProofAdb @('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
    Invoke-ProofAdb @('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    python (Join-Path $PSScriptRoot 'probe/generate_gb_probe.py'); if ($LASTEXITCODE -ne 0) { throw 'Probe generation failed' }
    Invoke-ProofAdb @('push', '.tools/probe/cartridge-probe.gb', '/data/local/tmp/cartridge-probe.gb')
    Invoke-ProofAdb @('shell', 'run-as', 'dev.cartridge', 'cp', '/data/local/tmp/cartridge-probe.gb', 'files/cartridge-probe.gb')
    Invoke-ProofAdb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    $token = [guid]::NewGuid().ToString()
    $arguments = $deviceArgs + @('shell', 'am', 'instrument', '-w', '-r', '-e', 'proofToken', $token, '-e', 'class',
        'dev.cartridge.ProcessRecoveryTest#prepareCompletedCheckpointThenHoldUncheckpointedRunningCoreForHostKill', 'dev.cartridge.test/androidx.test.runner.AndroidJUnitRunner')
    $holder = Start-Process -FilePath $adbPath -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot '.tools/process-prepare.log') -RedirectStandardError (Join-Path $projectRoot '.tools/process-prepare-error.log')
    $deadline = [datetime]::UtcNow.AddSeconds(60)
    $ready = ''
    while ([datetime]::UtcNow -lt $deadline) {
        $holder.Refresh(); if ($holder.HasExited) { throw 'Preparation exited early; inspect .tools/process-prepare.log.' }
        $candidate = & $adbPath @deviceArgs shell run-as dev.cartridge cat files/m6-process-ready.properties 2>$null
        if ($LASTEXITCODE -eq 0 -and ($candidate -join "`n") -match "(?m)^token=$token\s*$") { $ready = $candidate -join "`n"; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'The running core never published the expected checkpoint marker.' }
    $ready | Set-Content -LiteralPath '.tools/process-ready.log'
    if ($ready -notmatch '(?m)^pid=(\d+)\s*$') { throw 'Missing target PID' }
    $targetPid = $Matches[1]
    if ($Mode -eq 'Reboot') {
        $previousBoot = Invoke-ProofAdb @('shell', 'cat', '/proc/sys/kernel/random/boot_id')
        # Android can disconnect before adb receives the reboot shell's exit status. Completion
        # is proved by a changed boot ID and sys.boot_completed, not the transport exit code.
        & $adbPath @deviceArgs shell reboot 2>&1 | Out-Null
        $bootDeadline = [datetime]::UtcNow.AddSeconds(180)
        $booted = $false
        while ([datetime]::UtcNow -lt $bootDeadline) {
            Start-Sleep -Seconds 1
            $status = & $adbPath @deviceArgs get-state 2>$null
            if ($LASTEXITCODE -eq 0 -and ($status -join '').Trim() -eq 'device') {
                $completed = Invoke-ProofAdb @('shell', 'getprop', 'sys.boot_completed')
                if ($completed.Trim() -eq '1') { $booted = $true; break }
            }
        }
        if (-not $booted) { throw 'Device did not finish booting with authorized ADB within 180 seconds.' }
        $currentBoot = Invoke-ProofAdb @('shell', 'cat', '/proc/sys/kernel/random/boot_id')
        if ($currentBoot -eq $previousBoot) { throw 'Device boot ID did not change' }
        Invoke-ProofAdb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    } else { Invoke-ProofAdb @('shell', 'am', 'force-stop', 'dev.cartridge') }
    $holder.WaitForExit(10000) | Out-Null
    $currentPid = & $adbPath @deviceArgs shell pidof dev.cartridge 2>$null
    if ($currentPid -and ($currentPid -join ' ').Split(' ') -contains $targetPid) { throw 'The original target process survived force-stop' }
    "Original running target process terminated ($Mode); testing fresh-process restore."
    $result = Invoke-ProofAdb @('shell', 'am', 'instrument', '-w', '-r', '-e', 'proofToken', $token, '-e', 'class',
        'dev.cartridge.ProcessRecoveryTest#freshProcessRestoresLastCompletedCheckpointPausedWithRealPixelsAndBattery', 'dev.cartridge.test/androidx.test.runner.AndroidJUnitRunner')
    $result | Set-Content -LiteralPath ".tools/process-recovery-$Mode.log"; $result
    if ($result -notmatch '(?m)^OK \(1 test\)\s*$') { throw 'Process-death recovery test failed.' }
    Invoke-ProofAdb @('shell', 'am', 'start', '-n', 'dev.cartridge/.MainActivity')
} finally {
    if ($holder) { $holder.Refresh(); if (-not $holder.HasExited) { Stop-Process -Id $holder.Id }; $holder.Dispose() }
    Pop-Location
}
