param(
    [string]$Sdk = (Join-Path $PSScriptRoot '../.tools/sdk'),
    [string]$Serial,
    [ValidateRange(1, 50)][int]$Cycles = 3,
    [ValidateSet('Surface', 'Collection', 'Scrape', 'Library', 'Battery', 'Render', 'Manage', 'Review', 'Pack', 'Layout', 'Systems', 'PS1', 'Environment', 'Recovery', 'Backup', 'Live', 'Options', 'Tour', 'Browse', 'Launch', 'Pacing', 'All')][string]$Suite = 'Surface'
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
    if ($Suite -in @('Battery', 'All')) {
        & (Join-Path $PSScriptRoot 'Build-Probe.ps1') -Sdk $Sdk -Probe Battery
        Invoke-DeviceAdb -Arguments @('push', '.tools/probe/battery-probe.gba', '/data/local/tmp/battery-probe.gba')
        Invoke-DeviceAdb -Arguments @('shell', 'run-as', 'dev.cartridge', 'cp', '/data/local/tmp/battery-probe.gba', 'files/battery-probe.gba')
    }
    if ($Suite -in @('Systems', 'Environment', 'Recovery', 'Tour', 'All')) {
        python (Join-Path $PSScriptRoot 'probe/generate_gb_probe.py')
        if ($LASTEXITCODE -ne 0) { throw 'GB probe generation failed' }
        foreach ($extension in @('gb', 'gbc')) {
            Invoke-DeviceAdb -Arguments @('push', ".tools/probe/cartridge-probe.$extension", "/data/local/tmp/cartridge-probe.$extension")
            Invoke-DeviceAdb -Arguments @('shell', 'run-as', 'dev.cartridge', 'cp', "/data/local/tmp/cartridge-probe.$extension", "files/cartridge-probe.$extension")
        }
    }
    Invoke-DeviceAdb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    if ($Suite -in @('PS1', 'Options', 'All')) {
        python (Join-Path $PSScriptRoot 'probe/generate_ps1_probe.py')
        if ($LASTEXITCODE -ne 0) { throw 'PS1 probe generation failed' }
        foreach ($extension in @('exe', 'iso', 'chd', 'cue', 'bin')) {
            Invoke-DeviceAdb -Arguments @('push', ".tools/probe/ps1/probe.$extension", "/data/local/tmp/cartridge-probe.$extension")
            Invoke-DeviceAdb -Arguments @('shell', 'run-as', 'dev.cartridge', 'cp', "/data/local/tmp/cartridge-probe.$extension", "files/cartridge-probe.$extension")
        }
    }
    $testClass = switch ($Suite) {
        'Surface' { 'dev.cartridge.SurfaceHandoffTest' }
        'Collection' { 'dev.cartridge.CollectionUiTest' }
        'Scrape' { 'dev.cartridge.ProviderContractTest,dev.cartridge.ScrapeUiTest' }
        'Library' { 'dev.cartridge.LibraryGrowthTest,dev.cartridge.ScrapeResumeTest,dev.cartridge.LibraryManagementTest' }
        'Battery' { 'dev.cartridge.BatterySaveTest' }
        'Render' { 'dev.cartridge.RenderRefinementTest,dev.cartridge.RenderPacingTest' }
        'Manage' { 'dev.cartridge.LibraryManagementTest' }
        'Review' { 'dev.cartridge.ScrapeReviewTest,dev.cartridge.ScrapeResumeTest' }
        'Pack' { 'dev.cartridge.ScrapePackTest' }
        'Layout' { 'dev.cartridge.CollectionLayoutDeviceTest' }
        'Systems' { 'dev.cartridge.AdditionalSystemsTest' }
        'PS1' { 'dev.cartridge.Ps1DeviceTest' }
        'Environment' { 'dev.cartridge.ConsoleEnvironmentTest' }
        'Recovery' { 'dev.cartridge.SessionRecoveryTest' }
        'Backup' { 'dev.cartridge.BackupDeviceTest' }
        'Live' { 'dev.cartridge.ProviderLiveTest' }
        'Options' { 'dev.cartridge.GameOptionsDeviceTest' }
        # Walks the console with a controller and saves real screenshots to files/ux-tour (see docs/ux).
        'Tour' { 'dev.cartridge.UxTourTest' }
        # Steps along a mixed shelf and checks every presented frame for an object without its own label.
        'Browse' { 'dev.cartridge.BrowseStabilityTest' }
        # The closed screen around a game, its setting, the right-stick turn and where a shelf opens.
        'Launch' { 'dev.cartridge.LaunchExperienceTest' }
        # The production library with decoded labels and a held D-pad. For a large library add: -e games 3000
        'Pacing' { 'dev.cartridge.LibraryPacingTest' }
        'All' { 'dev.cartridge.SurfaceHandoffTest,dev.cartridge.CollectionUiTest,dev.cartridge.ProviderContractTest,dev.cartridge.ScrapeUiTest,dev.cartridge.LibraryGrowthTest,dev.cartridge.ScrapeResumeTest,dev.cartridge.BatterySaveTest,dev.cartridge.RenderRefinementTest,dev.cartridge.RenderPacingTest,dev.cartridge.LibraryManagementTest,dev.cartridge.ScrapeReviewTest,dev.cartridge.ScrapePackTest,dev.cartridge.CollectionLayoutDeviceTest,dev.cartridge.AdditionalSystemsTest,dev.cartridge.ConsoleEnvironmentTest,dev.cartridge.SessionRecoveryTest,dev.cartridge.BackupDeviceTest,dev.cartridge.Ps1DeviceTest,dev.cartridge.GameOptionsDeviceTest,dev.cartridge.UxTourTest,dev.cartridge.BrowseStabilityTest,dev.cartridge.LaunchExperienceTest,dev.cartridge.LibraryPacingTest' }
    }
    $expectedTests = switch ($Suite) { 'Surface' { 1 }; 'Collection' { 2 }; 'Scrape' { 5 }; 'Library' { 4 }; 'Battery' { 2 }; 'Render' { 2 }; 'Manage' { 2 }; 'Review' { 4 }; 'Pack' { 2 }; 'Layout' { 2 }; 'Systems' { 2 }; 'PS1' { 3 }; 'Environment' { 3 }; 'Recovery' { 1 }; 'Backup' { 3 }; 'Live' { 1 }; 'Options' { 1 }; 'Tour' { 1 }; 'Browse' { 1 }; 'Launch' { 4 }; 'Pacing' { 1 }; 'All' { 43 } }
    $result = Invoke-DeviceAdb -Arguments @('shell', 'am', 'instrument', '-w', '-r', '-e', 'cycles', "$Cycles", '-e', 'liveProvider', "$($Suite -eq 'Live')".ToLowerInvariant(), '-e', 'class',
        $testClass, 'dev.cartridge.test/androidx.test.runner.AndroidJUnitRunner')
    $result | Set-Content -LiteralPath '.tools/device-test.log'
    $result
    # am instrument can exit zero even when a JUnit assertion fails. Check its result explicitly.
    if ($result -notmatch "(?m)^OK \($expectedTests tests?\)\s*$") { throw 'Device test failed; see .tools/device-test.log.' }
    Invoke-DeviceAdb -Arguments @('shell', 'am', 'start', '-n', 'dev.cartridge/.MainActivity')
} finally { Pop-Location }
