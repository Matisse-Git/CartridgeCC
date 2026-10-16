param(
    [string]$Sdk = (Join-Path $PSScriptRoot '../.tools/sdk'),
    [string]$Serial,
    [ValidateRange(1, 50)][int]$Cycles = 3,
    [ValidateSet('Surface', 'Collection', 'Scrape', 'Library', 'Battery', 'Render', 'Manage', 'Review', 'Pack', 'Layout', 'Systems', 'PS1', 'Environment', 'Recovery', 'Backup', 'Live', 'Options', 'Tour', 'Browse', 'Launch', 'Pacing', 'Apps', 'Cores', 'Saturn', 'Dreamcast', 'PS2', 'GameCube', 'Discs', 'Shaders', 'All')][string]$Suite = 'Surface'
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
    if ($Suite -in @('Cores', 'All')) {
        python (Join-Path $PSScriptRoot 'probe/generate_console_probes.py')
        if ($LASTEXITCODE -ne 0) { throw 'Console probe generation failed' }
        foreach ($extension in @('sfc', 'nes', 'md', 'sms', 'gg')) {
            Invoke-DeviceAdb -Arguments @('push', ".tools/probe/cartridge-probe.$extension", "/data/local/tmp/cartridge-probe.$extension")
            Invoke-DeviceAdb -Arguments @('shell', 'run-as', 'dev.cartridge', 'cp', "/data/local/tmp/cartridge-probe.$extension", "files/cartridge-probe.$extension")
        }
    }
    if ($Suite -in @('Saturn', 'All')) {
        python (Join-Path $PSScriptRoot 'probe/generate_saturn_probe.py')
        if ($LASTEXITCODE -ne 0) { throw 'Saturn probe generation failed' }
        foreach ($file in @(@('boot.bin', 'cartridge-probe-saturn-boot.bin'), @('saturn.cue', 'cartridge-probe-saturn.cue'), @('saturn.bin', 'cartridge-probe-saturn.bin'))) {
            Invoke-DeviceAdb -Arguments @('push', ".tools/probe/saturn/$($file[0])", "/data/local/tmp/$($file[1])")
            Invoke-DeviceAdb -Arguments @('shell', 'run-as', 'dev.cartridge', 'cp', "/data/local/tmp/$($file[1])", "files/$($file[1])")
        }
    }
    # The three consoles whose cores draw for themselves: one original program each.
    foreach ($probe in @(@('Dreamcast', 'generate_dreamcast_probe.py', 'cartridge-probe.elf'), @('PS2', 'generate_ps2_probe.py', 'cartridge-probe-ps2.elf'),
            @('GameCube', 'generate_gamecube_probe.py', 'cartridge-probe.dol'))) {
        if ($Suite -in @($probe[0], 'All')) {
            python (Join-Path $PSScriptRoot "probe/$($probe[1])")
            if ($LASTEXITCODE -ne 0) { throw "$($probe[0]) probe generation failed" }
            Invoke-DeviceAdb -Arguments @('push', ".tools/probe/$($probe[2])", "/data/local/tmp/$($probe[2])")
            Invoke-DeviceAdb -Arguments @('shell', 'run-as', 'dev.cartridge', 'cp', "/data/local/tmp/$($probe[2])", "files/$($probe[2])")
        }
    }
    if ($Suite -in @('Dreamcast', 'All')) {
        # The same program as a GD-ROM image: a sheet and its three tracks.
        foreach ($file in @('disc.gdi', 'track01.iso', 'track02.raw', 'track03.iso')) {
            Invoke-DeviceAdb -Arguments @('push', ".tools/probe/dreamcast/$file", "/data/local/tmp/cartridge-probe-dreamcast-$file")
            Invoke-DeviceAdb -Arguments @('shell', 'run-as', 'dev.cartridge', 'cp', "/data/local/tmp/cartridge-probe-dreamcast-$file", "files/cartridge-probe-dreamcast-$file")
        }
    }
    if ($Suite -in @('PS2', 'All')) {
        # The same program on a small disc image, started the way a game is. Two of this console's three tests need
        # its BIOS, which is the owner's: they read it from files/m1-owner-firmware/pcsx2/bios/ps2-bios.bin in the
        # app's private storage and are skipped where it is missing (docs/TESTING).
        Invoke-DeviceAdb -Arguments @('push', '.tools/probe/cartridge-probe-ps2.iso', '/data/local/tmp/cartridge-probe-ps2.iso')
        Invoke-DeviceAdb -Arguments @('shell', 'run-as', 'dev.cartridge', 'cp', '/data/local/tmp/cartridge-probe-ps2.iso', 'files/cartridge-probe-ps2.iso')
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
        # The Android apps beside the library: where B leads, the device's real launcher list, Settings opened and returned from.
        'Apps' { 'dev.cartridge.AppsLibraryTest' }
        # Super Nintendo, NES, Genesis, Master System and Game Gear: each core run for real with an original program.
        'Cores' { 'dev.cartridge.ConsoleCoresTest' }
        # Saturn without Sega's BIOS: disc identification, the no-BIOS explanation, and the core run on an original stand-in image.
        'Saturn' { 'dev.cartridge.SaturnCoreTest' }
        # Dreamcast, PlayStation 2 and GameCube: each core drawing through the device's OpenGL ES with an original program.
        'Dreamcast' { 'dev.cartridge.DreamcastCoreTest' }
        'PS2' { 'dev.cartridge.Ps2CoreTest' }
        'GameCube' { 'dev.cartridge.GameCubeCoreTest' }
        # An empty 2.2 GiB image told apart by its size, copied whole and listed; the test removes its own copy.
        'Discs' { 'dev.cartridge.LargeDiscImportTest' }
        # Shader presets (ADR 0027): the library checked on the GPU, the download from GitHub (needs the network), and
        # choices made in Settings and Game options reaching the picture.
        'Shaders' { 'dev.cartridge.ShaderPresetTest,dev.cartridge.ShaderLibraryUpdateTest,dev.cartridge.ShaderSettingsTest' }
        'All' { 'dev.cartridge.SurfaceHandoffTest,dev.cartridge.CollectionUiTest,dev.cartridge.ProviderContractTest,dev.cartridge.ScrapeUiTest,dev.cartridge.LibraryGrowthTest,dev.cartridge.ScrapeResumeTest,dev.cartridge.BatterySaveTest,dev.cartridge.RenderRefinementTest,dev.cartridge.RenderPacingTest,dev.cartridge.LibraryManagementTest,dev.cartridge.ScrapeReviewTest,dev.cartridge.ScrapePackTest,dev.cartridge.CollectionLayoutDeviceTest,dev.cartridge.AdditionalSystemsTest,dev.cartridge.ConsoleEnvironmentTest,dev.cartridge.SessionRecoveryTest,dev.cartridge.BackupDeviceTest,dev.cartridge.Ps1DeviceTest,dev.cartridge.GameOptionsDeviceTest,dev.cartridge.UxTourTest,dev.cartridge.BrowseStabilityTest,dev.cartridge.LaunchExperienceTest,dev.cartridge.LibraryPacingTest,dev.cartridge.AppsLibraryTest,dev.cartridge.ConsoleCoresTest,dev.cartridge.SaturnCoreTest,dev.cartridge.DreamcastCoreTest,dev.cartridge.Ps2CoreTest,dev.cartridge.GameCubeCoreTest,dev.cartridge.LargeDiscImportTest,dev.cartridge.ShaderPresetTest,dev.cartridge.ShaderLibraryUpdateTest,dev.cartridge.ShaderSettingsTest' }
    }
    $expectedTests = switch ($Suite) { 'Surface' { 1 }; 'Collection' { 2 }; 'Scrape' { 5 }; 'Library' { 4 }; 'Battery' { 2 }; 'Render' { 2 }; 'Manage' { 2 }; 'Review' { 4 }; 'Pack' { 2 }; 'Layout' { 2 }; 'Systems' { 2 }; 'PS1' { 3 }; 'Environment' { 3 }; 'Recovery' { 1 }; 'Backup' { 3 }; 'Live' { 1 }; 'Options' { 2 }; 'Tour' { 1 }; 'Browse' { 1 }; 'Launch' { 4 }; 'Pacing' { 1 }; 'Apps' { 4 }; 'Cores' { 1 }; 'Saturn' { 2 }; 'Dreamcast' { 2 }; 'PS2' { 3 }; 'GameCube' { 1 }; 'Discs' { 1 }; 'Shaders' { 4 }; 'All' { 62 } }
    $result = Invoke-DeviceAdb -Arguments @('shell', 'am', 'instrument', '-w', '-r', '-e', 'cycles', "$Cycles", '-e', 'liveProvider', "$($Suite -eq 'Live')".ToLowerInvariant(), '-e', 'class',
        $testClass, 'dev.cartridge.test/androidx.test.runner.AndroidJUnitRunner')
    $result | Set-Content -LiteralPath '.tools/device-test.log'
    $result
    # am instrument can exit zero even when a JUnit assertion fails. Check its result explicitly.
    if ($result -notmatch "(?m)^OK \($expectedTests tests?\)\s*$") { throw 'Device test failed; see .tools/device-test.log.' }
    Invoke-DeviceAdb -Arguments @('shell', 'am', 'start', '-n', 'dev.cartridge/.MainActivity')
} finally { Pop-Location }
