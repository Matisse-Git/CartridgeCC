param([string]$Serial, [ValidateRange(2,120)][int]$Minutes = 10, [string]$Sdk = (Join-Path $PSScriptRoot '../.tools/sdk'))
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adbPath = Join-Path $Sdk 'platform-tools/adb.exe'
$deviceArgs = @(); if ($Serial) { $deviceArgs = @('-s', $Serial) }
function Invoke-SoakAdb([string[]]$Arguments) {
    $result = & $adbPath @deviceArgs @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return ($result -join "`n")
}
Push-Location $projectRoot
try {
    Invoke-SoakAdb @('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
    Invoke-SoakAdb @('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    python (Join-Path $PSScriptRoot 'probe/generate_gb_probe.py'); if ($LASTEXITCODE -ne 0) { throw 'Probe generation failed' }
    Invoke-SoakAdb @('push', '.tools/probe/cartridge-probe.gb', '/data/local/tmp/cartridge-probe.gb')
    Invoke-SoakAdb @('shell', 'run-as', 'dev.cartridge', 'cp', '/data/local/tmp/cartridge-probe.gb', 'files/cartridge-probe.gb')
    Invoke-SoakAdb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    $result = Invoke-SoakAdb @('shell', 'am', 'instrument', '-w', '-r', '-e', 'soakMinutes', "$Minutes", '-e', 'class',
        'dev.cartridge.ConsoleStabilityTest', 'dev.cartridge.test/androidx.test.runner.AndroidJUnitRunner')
    $result | Set-Content -LiteralPath '.tools/stability-test.log'; $result
    if ($result -notmatch '(?m)^OK \(1 test\)\s*$') { throw 'Sustained test failed; see .tools/stability-test.log.' }
    Invoke-SoakAdb @('shell', 'am', 'start', '-n', 'dev.cartridge/.MainActivity')
} finally { Pop-Location }
