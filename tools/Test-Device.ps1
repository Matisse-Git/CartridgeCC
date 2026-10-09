param(
    [string]$Sdk = (Join-Path $PSScriptRoot '../.tools/sdk'),
    [string]$Serial,
    [ValidateRange(1, 50)][int]$Cycles = 3,
    [ValidateSet('Surface', 'Collection', 'All')][string]$Suite = 'Surface'
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adbPath = Join-Path $Sdk 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adbPath)) { throw 'ADB not found; pass -Sdk with the Android SDK directory.' }
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }

function Invoke-DeviceAdb([string[]]$Arguments) {
    $output = & $adbPath @deviceArgs @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($output -join "`n") }
    return ($output -join "`n")
}

Push-Location $projectRoot
try {
    $appApk = 'app/build/outputs/apk/debug/app-debug.apk'
    $testApk = 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    if (-not (Test-Path $appApk) -or -not (Test-Path $testApk)) {
        throw 'Build :app:assembleDebug and :app:assembleDebugAndroidTest first.'
    }
    if ((Invoke-DeviceAdb -Arguments @('get-state')) -ne 'device') { throw 'Connect and authorize an unlocked Android device.' }
    & (Join-Path $PSScriptRoot 'Build-Probe.ps1') -Sdk $Sdk
    Invoke-DeviceAdb -Arguments @('install', '-r', $appApk)
    Invoke-DeviceAdb -Arguments @('install', '-r', $testApk)
    Invoke-DeviceAdb -Arguments @('push', '.tools/probe/cartridge-probe.gba', '/data/local/tmp/cartridge-probe.gba')
    Invoke-DeviceAdb -Arguments @('shell', 'run-as', 'dev.cartridge', 'cp', '/data/local/tmp/cartridge-probe.gba', 'files/cartridge-probe.gba')
    Invoke-DeviceAdb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    $testClass = switch ($Suite) {
        'Surface' { 'dev.cartridge.SurfaceHandoffTest' }
        'Collection' { 'dev.cartridge.CollectionUiTest' }
        'All' { 'dev.cartridge.SurfaceHandoffTest,dev.cartridge.CollectionUiTest' }
    }
    $expectedTests = switch ($Suite) { 'Surface' { 1 }; 'Collection' { 2 }; 'All' { 3 } }
    $result = Invoke-DeviceAdb -Arguments @('shell', 'am', 'instrument', '-w', '-r', '-e', 'cycles', "$Cycles", '-e', 'class',
        $testClass, 'dev.cartridge.test/androidx.test.runner.AndroidJUnitRunner')
    $result | Set-Content -LiteralPath '.tools/device-test.log'
    $result
    # am instrument can exit zero even when a JUnit assertion fails. Check its result explicitly.
    if ($result -notmatch "(?m)^OK \($expectedTests tests?\)\s*$") { throw 'Device test failed; see .tools/device-test.log.' }
    Invoke-DeviceAdb -Arguments @('shell', 'am', 'start', '-n', 'dev.cartridge/.MainActivity')
} finally { Pop-Location }
