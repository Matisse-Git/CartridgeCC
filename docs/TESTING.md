# Tests and actual results

## M0 host results — 2026-10-09

Validated on Windows with Microsoft OpenJDK 21.0.8, Gradle 8.13, SDK 35, NDK 27.2.12479018 and
SDK CMake 3.22.1. Initially no Android device was attached; physical-device results follow below.

| Check | Actual result |
| --- | --- |
| `:app:assembleDebug` | Passed, real arm64 APK and source-built mGBA core |
| `:app:assembleDebugAndroidTest` | Passed; device-test APK compiled and subsequently executed on RP5 |
| `:app:testDebugUnitTest` | 5 tests, 0 failures/errors |
| `:app:lintDebug` | Passed, 0 errors, 13 warnings; dependency-update/orientation/arm64-only and API-style warnings remain |
| `python tools/test_assets.py` | 4 structural GLB tests passed |
| `tools/Build-Probe.ps1` | Original ARM diagnostic assembled and exported successfully |
| mGBA ELF inspection | AArch64 shared library, Libretro symbols exported, LOAD alignment 0x4000 |
| Default CMake FetchContent path | Passed; pinned source downloaded and SHA-256 verified |
| Device controller/render/audio/handoff | See the dated RP5 results below |

The first compile error (ClearOptions colour array type) and AndroidX dispatch-hook lint error were
corrected. The narrowly scoped RestrictedApi suppression is explained at that public Activity hook.
Upstream mGBA compiler warnings are recorded in build logs; they did not prevent linking. Older shell
dependencies are intentionally pinned, not silently upgraded to the current SceneView toolchain.

Unit tests cover illegal/duplicate lifecycle operations, error recovery to a retry, selection clamping,
spring retargeting, held-axis repeat without new events, save isolation, overwrite and path rejection.
Asset tests check binary chunk/range/index validity, physical metre-scale depth, normals/winding and
the embedded, separable label texture. They do **not** prove GPU decoding or visual quality.

Reproduce:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
python tools/test_assets.py
```

Reports: `app/build/test-results/testDebugUnitTest/`, `app/build/reports/tests/testDebugUnitTest/`,
`app/build/reports/lint-results-debug.html`. Local build logs are ignored under `.tools/`.

## Physical RP5 preparation

Enable Developer Options and USB debugging, connect USB, and authorize this computer on the device.
Do not claim the hardware is an RP5 based on an emulator or a product-page assumption.

```powershell
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
adb shell getprop ro.product.cpu.abilist
adb shell wm size
adb shell dumpsys input
adb shell cat /proc/meminfo
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.cartridge/.MainActivity
```

Record firmware, button layout, controller ID/source/key codes, display size/refresh rate, ABI, and
available RAM in a dated result entry. Keep personal device logs out of Git.

## Physical RP5 results — 2026-10-09

The owner enabled USB debugging and authorized this PC. ADB identified Retroid_Pocket_5/kona,
Android 13/API 33, firmware `RP5_V1.0.0.189_20251128_174732_user`. ABI includes arm64-v8a. Filament
identified Adreno 650 and GLES 3.2. The physical panel reports 1080×1920 and 60.000004 Hz; the app's
landscape screenshot is 1920×1080. `/proc/meminfo` reports 7,885,048 KiB total RAM (~7.5 GiB).

The built-in Retroid Pocket Controller reports vendor/product 2022:3001 and gamepad/joystick sources.
The device key-layout file maps D-pad, A/B, L1/R1, Start/Select and L3/R3 to standard Android keys;
left-stick X/Y and HAT_X/HAT_Y are present. Physical face-button naming still depends on the selected
Retroid layout mode; do not assume every device uses the same preference.

| Device check | Actual result |
| --- | --- |
| Install app + instrumentation APKs | Passed on physical RP5 |
| Genuine cartridge GLB | Visible in an ADB screenshot, with textured label, sides and neighbouring instance |
| Physical D-pad and left stick | Owner confirmed both work smoothly, including held/reversed/rapid navigation |
| Initial three-cycle real surface test | Passed, 1 test, 2.170 s |
| Final 50 real Filament ↔ mGBA cycles | Passed, 1 test, 20.490 s; pause/save/load/resume/stop each cycle |
| Reproducible Test-Device.ps1 helper | Executed successfully with 3 cycles, 1 test, 2.698 s |
| Actual emulator framebuffer and input | PixelCopy verified gray → green on A, gray on B; first-buffer NO_DATA retried within a bounded wait |
| Visible save-state restoration | Saved green, changed to gray, loaded state and observed green from the GPU surface |
| Missing/invalid ROM recovery | Missing private path and 192-byte zero-filled file reported Error, stopped to Idle, then all 50 valid sessions ran |
| Audio/ordinary Compose pause menu | Owner confirmed gameplay, audio, pause/resume, eject and state save/load through the ordinary app |
| Collection memory snapshot | 177,901 KiB PSS (~174 MiB); a single snapshot is not a leak or performance verdict |
| Frame pacing, battery, latency | Not profiled; the device's FPS overlay is not a measured frame-time distribution |

Final fixture post-teardown PSS: cycle 1 = 144,711 KiB (~141 MiB), cycle 10 = 171,732 KiB (~168 MiB),
cycle 50 = 184,809 KiB (~180 MiB). These snapshots increase during the short run; they do not establish
a plateau or prove a leak. Follow up with longer runs, managed/native allocation and GPU profiling.
The restored ordinary library process was 170,488 KiB PSS (~166 MiB). No app-specific Java fatal
exception or native fatal signal was observed in the captured regression logs.

The first test attempt timed out while the device was Dozing. After `KEYCODE_WAKEUP`, the three-cycle
test passed. The debug fixture now requests KEEP_SCREEN_ON. The stronger pixel test initially queried
before EGL's first swap and received ERROR_SOURCE_NO_DATA; only that status is retried. This was a test
synchronization fix, not a mocked rendering fallback. Personal screenshots/logs remain ignored in `.tools/`.

## Real surface instrumentation probe

The debug-only `SurfaceProbeActivity` alternates a rendered Filament cartridge and the real packaged
mGBA view three times by default (or up to 50). Each cycle waits for a 3D frame, tears Filament down,
waits for a core frame,
pauses, saves/loads, resumes, persists RAM/stops, and detaches. `SurfaceHandoffTest` checks completion
and captures failures. The first cycle samples real pixels, routes A/B and checks visible state restoration.
The fixture also probes missing/invalid ROM recovery before valid sessions. It requires a foreground
arm64 device with GLES 3; compilation alone is not a pass. Post-teardown PSS at cycles 1/10/50 is reported
for comparison, without claiming that short-lived samples establish long-term memory stability.

The original diagnostic assembly has no proprietary logo/BIOS/data. It fills GBA VRAM, latches green
on A and resets gray on B, and enables a square-wave tone. It needs mGBA's HLE BIOS; no retail hardware
boot claim is made. It is generated only in ignored `.tools/probe/`, never bundled in the app.

On Windows:

Build both APKs first, unlock/authorize the device, then use the reproducible helper:

```powershell
.\tools\Test-Device.ps1 -Suite All -Cycles 50
```

Pass `-Sdk C:/path/to/Android/Sdk` and optionally `-Serial <serial>` when needed. It generates the original
probe, installs both APKs, pushes the fixture, wakes the screen, runs instrumentation and checks the
JUnit result (ADB's process exit code alone is insufficient). `-Suite Surface` is the default and runs
one test; `-Suite Collection` runs two ordinary UI tests; `-Suite Scrape` runs five parser/migration/chooser
tests; `-Suite Library` runs two folder/durable-queue tests; `-Suite Battery` runs two battery-save tests;
`-Suite Render` runs two GPU orientation/settings/presentation tests; `-Suite All` runs all fourteen tests.
`-Suite Live` opts into one real API test using
a fresh candidate cache, requiring device internet. It retrieves no commercial image or ROM. Logs stay in
ignored `.tools/device-test.log`. Collection tests stub only document-picker results, then use the real
ContentResolver, Room database, Filament and mGBA. Their database/cache is isolated from the owner's library.
Equivalent individual commands:

```powershell
.\tools\Build-Probe.ps1 -Sdk "$env:ANDROID_HOME"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push .tools/probe/cartridge-probe.gba /data/local/tmp/cartridge-probe.gba
adb shell run-as dev.cartridge cp /data/local/tmp/cartridge-probe.gba files/cartridge-probe.gba
adb shell input keyevent KEYCODE_WAKEUP
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

After installing both APKs, the stress variant can be run directly (add `-s <serial>` to `adb` when
multiple devices are attached; keep actual serials out of committed scripts):

```powershell
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e cycles 50 -e class dev.cartridge.SurfaceHandoffTest dev.cartridge.test/androidx.test.runner.AndroidJUnitRunner
```

If this workspace's local SDK is used, omit `-Sdk` to use `.tools/sdk`. On another OS, assemble the
same `.S` using its NDK clang `--target=arm-none-eabi -march=armv4t -nostdlib -fuse-ld=lld
-Wl,-Ttext=0x08000000`, then use `llvm-objcopy -O binary`. Do not use an unknown downloaded ROM.

For manual testing, copy the generated probe to a user-visible location via ADB and choose it through
the ordinary SAF picker. A passing instrumentation probe still needs manual audio/input/pacing checks.

## Manual acceptance checklist

- [x] Actual original GLB visible under warm lighting, correct label orientation and thickness; 3D
  rotation exposes sides/contacts. Focused cartridge larger, neighbours visible.
- [x] D-pad/left-stick navigation, holding/reversing and rapid scrolling confirmed by owner.
- [ ] Quantified neutral/dead-zone/long-hold tests, 20 seconds of rapid scrolling and retargeting at edges.
- [ ] Touch arrows/Insert/picker work. Cancelled SAF selection leaves the same cartridge focused.
- [x] Assign original probe/user-owned .gba; A starts real mGBA frames in the same Activity/application.
  Probe A/B input and owner gameplay confirmed. No external emulator intent or menu.
- [ ] Verify every gameplay A/B/L/R/Start/Select mapping in each supported Retroid layout mode.
- [x] Owner confirmed audible game audio. Long-duration crackle/timing/input latency measurement remains open.
- [x] Owner confirmed L3+R3 pause/resume and eject in the ordinary app.
- [ ] Check alternate Start+Select, Back, held-input release, every menu D-pad/A/B mapping, overlay
  readability/z-order during resizing and stopped audio/frame progression while paused.
- [x] GPU probe proved visibly changing save/load; owner also confirmed ordinary app state save/load.
- [x] Original SRAM diagnostic proves battery continuity across eject/relaunch and backgrounding,
  restoration before the first frame, automatic checkpoints and failed-write retry. No state file used.
- [ ] Owner-game battery continuity, real reboot/process death and sleep/suspend. Missing state leaves
  gameplay paused with an error; corrupt/incompatible data reports failure.
- [x] 50 native handoffs passed, with missing/invalid ROM recovery and no crash.
- [ ] Ordinary UI 50-cycle insertion/ejection stress: no duplicate session/thread or stale surface, same
  selected cartridge restored, no crash. Inspect logs for native/GL errors.
- [ ] Deleted/unreadable ROM, too-large/wrong-extension file and invalid core build: truthful loading
  failure, safe cleanup and selection preserved; subsequent valid game still launches.
- [ ] Backgrounding, sleep/suspend and focus loss while Preparing, Running, Paused and saving. M0
  intentionally stops sessions and persists RAM on background. No assertion of seamless resume.
- [ ] Activity recreation and surface destruction/recreation; pause overlay resizing; switch back
  repeatedly. Core context-loss recovery must be fixed before advancing if it is observed.
- [ ] External controller unplug/disconnect while buttons held releases all input and pauses.
- [ ] Ten-minute collection/gameplay runs: measure CPU/GPU frame time, audio timing, PSS/native/GL
  memory, thermals and battery. Compare memory after 1/10/50 handoffs; no continuing Filament loop
  behind gameplay. RP5 60 FPS is a measured target, not a host-build assertion.

Use Android Studio profiler/Perfetto for pacing/CPU/GPU and `adb shell dumpsys meminfo dev.cartridge`
for snapshots. SurfaceView frames are not fully characterized by Compose frame metrics alone.
Record median/p95/p99 frame times and observed jank, not just an average FPS.

## M0 acceptance

Buildability, genuine Android GLB rendering, physical controller navigation, real internal GBA frames
and repeated native surface handoff pass on the RP5. The owner confirmed ordinary gameplay/audio/menu/
save-load/eject. **M0's five technical feasibility criteria passed.** The broader lifecycle/performance
checklist above remains M1 hardening work; this is not production-readiness acceptance.

## First M1 increment results — 2026-10-09

Same physical RP5/firmware as above. Version 0.1.0-m1-dev, versionCode 2. This adds Room 2.7.2,
KSP 2.1.20-1.0.32 and the matching Room Gradle schema plugin. Both APK builds, unit tests and lint
pass. Eleven unit tests (five domain, six coordinator) have zero failures/errors; four GLB checks pass.
Lint has zero errors and 20 warnings, including pinned-dependency updates, orientation, arm64-only,
debug-fixture backup and API-style suggestions. The Room plugin emits Gradle 9 deprecation notices;
the supported/pinned build uses Gradle 8.13.

Executed `Test-Device.ps1 -Suite All -Cycles 50`: **OK (3 tests)** in **30.628 s**. The native test
retains framebuffer/input/state/error recovery and 50 surface cycles. Two new tests run ordinary
production UI in DebugCollectionActivity with isolated on-disk Room databases:

- Choose/import an original probe; insert, pause, save/load, resume, background to CREATED, return
  and relaunch. Recreate while Running, restore the collection, cancel insertion on background,
  retry, pause and eject. This proves stop-and-return lifecycle recovery, not seamless game restoration.
- Assign two original diagnostic ROM identities and red/blue label images. PixelCopy of the actual
  SurfaceView proves independent labels; Fit/Crop/rotation recipe, Activity recreation, cancel,
  identical-ROM reimport and invalid-image recovery preserve choices. Close the database and reopen
  it with a new Activity; stored slots, focus and recipe restore and the real red label renders.

The picker response alone is stubbed with Espresso Intents; the file provider is debug-only and
nonexported. Native emulation and rendering are never stubbed. UI tests wait for Compose before polling
the independently clocked SurfaceView. The initial texture upload failed until GEN_MIPMAPPABLE usage
was added, and the initial selection-pixel assertion read a stale Compose update; both were fixed and
the final full suite rerun. Local logs/fixtures remain ignored. No new long-term PSS/pacing result is claimed.

The owner manually confirmed physical X/Y label selection, controller navigation, Fit/Crop/rotation,
label placement/application, switching cartridges and reopening persistence. Manual asymmetric scan
orientation/fidelity across multiple images and broader controller/input/performance checks remain.
The checklist above records M0 scope; these new tests cover a subset of its previously open lifecycle
items, not every listed suspend/save/context-loss combination. M1 remains in progress.

## API scraper increment results — 2026-10-09

Version 0.1.0-m1-scrape, versionCode 3, same RP5/firmware. Both APK builds, unit tests and lint pass;
13 host tests (five domain, six coordinator, two scrape-queue) have zero failures/errors. The queue
tests cover scope deduplication, no automatic writes, cancel/retry and checked-field-only acceptance.
Four structural GLB checks pass. Lint has zero errors and 20 existing warnings.

`Test-Device.ps1 -Suite All -Cycles 3`: **OK (6 tests)** in **15.824 s** on the final reviewed build.
The earlier 50-cycle native test remains the recorded stress result; this increment repeats three
native handoffs plus ordinary collection/session tests and three new scraper tests:

- Provider parsing preserves regional aliases/descriptions, rejects unsupported image hosts and
  keeps flat labels separate from box art. Fixture data/artwork is original.
- Create the actual exported v1 SQLite schema, seed ROM/label/focus, then open through production
  Room migration v2. Independently merge metadata and extra media; close/reopen and preserve identity,
  original title, label, focus and accepted choices. The fixture initially assumed every entity had an
  indices array; optJSONArray now follows the exported schema. No destructive fallback is used.
- Ordinary Compose scraper UI selects a year from record A, previews/applies A's label on real
  Filament, then accepts a developer and box art from record B. Activity recreation retains separate
  provenance and original ROM title. Only provider responses and SAF picker results are stubbed;
  database, files, UI, 3D upload and emulator regression are real. Isolated test data avoids owner writes.

`Test-Device.ps1 -Suite Live`: **OK (1 test)** in **1.851 s**, with a fresh cache on the final build.
The earlier live probe also passed in 2.116 s. Real RP5 internet/API returned the GBA
record and EU/JP/US support-texture URLs. This probe downloads no artwork and is separate from the
deterministic suite; service failures are reported rather than hidden with a fixture fallback.

The owner then confirmed **real remote thumbnails, applying a scraped label and persistence after
reopening all work**. This closes the initial actual-provider artwork proof; it does not establish
complete Socket coverage, disc support or directory-scale scraping.

Reproduce the scraper regression and optional live contract independently after building:

```powershell
.\tools\Test-Device.ps1 -Suite All -Cycles 3
.\tools\Test-Device.ps1 -Suite Live
```

Remaining physical scraper checks: controller-only bulk review/skip/variant selection, interrupted
network and rate-limit retry, cancel during download/write, apply label+metadata then disable Wi-Fi
and cold-launch/play, asymmetric regional-label fidelity, and accepting/replacing each media category.
Queue restoration after process death and accepted-version undo are not implemented. Longer collection/
gameplay profiling, SaveRAM continuity, sleep/context loss and held-controller disconnection remain
the M1 checklist above. M0 remains passed; M1 is not declared complete.

## Larger library / durable queue results — 2026-10-09

Version 0.1.0-m1-library, versionCode 4, same physical RP5 and firmware. Both APKs compile, all
16 host tests pass (eight coordinator, five domain, three queue), four GLB structural checks pass,
and lint has zero errors/21 warnings. The extra warning is a String.toUri style suggestion; remaining
warnings are the existing pinned-version/orientation/ABI/backup/API-style items. No broad suppressions.

`Test-Device.ps1 -Suite All -Cycles 50`: **OK (9 tests)** in **41.485 s**. The final reviewed build,
including linear snapshot mapping and a bounded journal reader, then passed the same nine tests with
three native cycles in **23.428 s**. The provider API adapter/dependency pins are unchanged; the separate
actual API/artwork evidence is in the preceding entry.

New physical tests:

- A debug DocumentsProvider presents nested original diagnostic files, a duplicate, an invalid short
  .gba and ignored text. Choose the folder through the ordinary Compose action (picker/grant alone
  stubbed). Five new content identities plus one existing game become six slots; duplicate/invalid
  counts are correct. Repeat Rescan and reopen the database with zero added duplicates.
- Apply blue original artwork to game six. PixelCopy reads real GPU label pixels beyond the original
  three slots, across Activity recreation and database closing/reopening. Insert that sixth ROM into
  actual mGBA, pause/eject, restore focus and blue pixels. Renderer/emulator are never mocked.
- Cancel a scrape during its second provider request, recreate the Activity and reopen the on-disk
  journal. Resume retries only pending work; completed candidates reload from disk. Next-title review
  index and independently accepted year metadata survive another recreation. The provider responses
  are original fixtures, not live service calls.
- Seed the exact exported v2 schema with scraped label/metadata/media provenance and focus; upgrade
  through v2→v3, append/deduplicate games and register a directory without changing previous choices.
  The existing v1 migration test now upgrades through v1→v2→v3. All schema JSON is versioned.

The first compile caught a nullable-Unit return from journal reset. The first device run crashed before
tests because DocumentsProvider requires exported=true and MANAGE_DOCUMENTS protection; its debug
manifest was corrected and the full suite rerun. It has no DOCUMENTS_PROVIDER discovery filter, so it
doesn't appear in the system picker. Only the old single-document fixture is nonexported; the new
tree fixture is protected by the system-only permission, not generally accessible. The production
app requests no such permission and release builds contain neither fixture.

Reproduce after the normal build:

```powershell
.\tools\Test-Device.ps1 -Suite Library
.\tools\Test-Device.ps1 -Suite All -Cycles 50
```

Manual owner folder/grant/bulk-resume validation was requested on the installed build. Automated results
substitute picker/grant/provider responses as stated; physical reboot and full process-kill recovery
have not been measured. Still required: persistable folder grants after device reboot, revoked/missing
roots, read-only/cloud provider behavior, disk-full and cancellation during large copies/journal writes,
hundreds of real games with rapid navigation, offline cold-launch/play, held inputs/disconnection,
sustained CPU/GPU/audio/frame pacing/memory/battery, and suspend. One durable batch is implemented;
directory scan cursors and unapplied previews/checkbox drafts do not survive recreation. M1 remains open.

## Legacy duplicates and reversible removal — 2026-10-09

Owner validation confirmed folder import and saved batch resume work. The owner reported three copies
of one game and no removal control, and called the scraping review flow tedious. Its UX redesign is
explicitly deferred to a later version. Aggregate inspection confirmed seven assigned entries but five
distinct ROM hashes; two assignments were redundant. No personal titles, art, ROMs or database copies
are committed.

VersionCode 5 compacts entries and collapses legacy hashes without merging different-content versions
with the same title. Room v4 migration adds removal exclusions. B / Collection offers Remove and Undo;
X / Add .gba restores exclusions after reopening. All source files, working ROMs, saves and accepted
enrichment are retained. Undo's previous position is Activity state, so it is single-step/session-only.

Both APKs compile; **17 host tests** (nine coordinator, five domain, three queue), four GLB checks and
lint pass, with zero lint errors/21 warnings. **All 10 RP5 tests passed in 24.810 s** with three native
handoffs. New/extended checks:

- Upgrade the exact v3 SQLite schema containing three assignments of one hash and a second hash with
  the same title. Collapse to two, preserve focused identity and label, remove/reopen, suppress rescan
  re-addition, restore original order, prevent duplicate legacy assignment, empty the collection, and
  explicitly re-add with its label. Existing v1/v2 tests now migrate through v4.
- On game six, save a real mGBA state, eject, remove through the production UI, rescan, and Undo.
  PixelCopy proves the original blue GPU label returns at the original position. Repeat removal,
  recreate, rescan, then Add restores the same identity/label. Save-state bytes and original/private
  ROM files remain intact. All test data uses isolated databases and original diagnostic files.
- The saved-batch test also asserts the target manifest exists before the first provider request.
  Manifest/candidate/reset writes reject older IO operations across Activity instances.

Final review added the same stale-operation guard to journal resets. Both APKs/17 host tests/lint
were rerun successfully; the two focused LibraryGrowth/ScrapeResume RP5 tests passed in **9.379 s**
on that final source, and the helper reopened the production application. The full ten-test result
above covers the unchanged collection/migration/rendering code.

The first removal device run caught an exact-three-label requirement when the collection contained
one game. CartridgeSurface now pads its fixed label pool and places inactive instances outside the
far clip; the full suite then passed. No failure was masked or acceptance gate relaxed.

The installed owner's database upgraded to v4: five assigned entries, five unique hashes, zero
duplicate assignments, and unchanged game/enrichment counts. Focus preservation is proved by the
isolated migration tests; the earlier owner snapshot and later inspection were not simultaneous.
Manual Remove/Rescan/Undo validation is requested separately. Reboot, disk-full/process-kill timing,
large-library profiling, source grant recovery, SaveRAM continuity and sustained suspend/audio/memory
checks remain as listed above. M0 remains passed; M1 is in progress.

## Battery boot barrier and checkpoints — 2026-10-09

Version 0.1.0-m1-saves, versionCode 6, installed on the same physical RP5: Android 13/API 33,
arm64-v8a, Adreno 650/GLES 3.2, landscape 1920×1080 at 60 Hz, firmware
`RP5_V1.0.0.189_20251128_174732_user`. Both APKs compile; **19 host tests** (nine coordinator,
five domain, three queue, two SaveStore), four GLB structural tests and lint pass, with zero lint
errors/21 existing warnings. The original C11 JNI bridge exports its three SaveBridge methods and
has ELF LOAD alignment 0x4000. No dependency pins changed.

`Test-Device.ps1 -Suite Battery`: **OK (2 tests)** in **28.462 s**.
`Test-Device.ps1 -Suite All -Cycles 50`: **OK (12 tests)** in **70.337 s** on the final source.
The helper reopened the ordinary application after installing and testing version 6.

New physical checks use an isolated Room database and unique ROM hash. The original 296-byte ARM
diagnostic latches SRAM byte zero once at boot, turns green on A and gray on B. It is generated in
ignored `.tools/probe/`, never bundled. The ordinary Compose UI, engine and mGBA core are exercised;
GPU PixelCopy observes the actual framebuffer. Owner ROMs, library choices, labels and saves are untouched.

- Save a real 32 KiB battery file, eject and reinsert: the boot latch reads the restored value before
  retro_run. Repeat with changed RAM, then background/reopen and insert. No save-state file exists.
- Oversized native restoration is rejected without changing memory. An oversized stored battery file
  fails preparation, retains its original bytes and permits a fresh session after fixing the fixture.
- Observe the production 15-second checkpoint while Running. A directory at only the fixture's unique
  save destination forces a write failure; the app shows a warning, keeps RAM and succeeds after retry.
  This simulates an unwritable destination, not physical disk exhaustion.
- Deliberately skip graceful shutdown, release fixture native resources and recreate the Activity;
  the latest checkpoint boots green. This is abrupt fixture teardown, **not actual OS process death,
  force-stop or reboot**. Ordinary background shutdown is tested separately.
- Host tests reject oversize and empty-state writes/reads without replacing previous data. Competing
  SaveStore instances publish only complete byte arrays and leave no temporary files.

The first native build exposed missing C++ runtime linkage; this small public-C-ABI bridge now uses
C11 without another C++ runtime. The Windows competing-write test exposed AccessDenied during
replacement; shared-directory serialization fixed it. All required checks were rerun successfully;
no test was skipped or acceptance gate relaxed.

Reproduce with the configured JDK and local SDK:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools/gradle-home'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
python tools/test_assets.py
.\tools\Test-Device.ps1 -Serial <serial> -Suite Battery
.\tools\Test-Device.ps1 -Serial <serial> -Suite All -Cycles 50
```

`Build-Probe.ps1 -Probe Battery` generates just the new diagnostic; `Test-Device.ps1` generates and
copies both fixtures automatically for All. Logs remain ignored under `.tools/`.

Owner-game battery saving is requested separately on the installed build. Still required: real OS
process death, reboot, physical sleep/suspend, actual disk-full/power-loss behavior, sustained memory,
audio/frame timing and controller disconnection. Sudden termination may lose progress since the latest
completed checkpoint; scheduler delays and storage errors can extend that interval. No comparative PSS,
frame-pacing or leak-free claim is made from this run. M0 remains passed; M1 is in progress.
See SAVE_RELIABILITY.md and ADR 0007 for implementation and source API reasoning.

## M2 media, lighting, motion and presentation — 2026-10-09

Version 0.2.0-m2-studio, versionCode 7, same RP5/Android 13/API 33/arm64/Adreno 650/GLES 3.2,
1920×1080 at 60 Hz and firmware `RP5_V1.0.0.189_20251128_174732_user`. M2 is active at the owner's
request, retaining M1's longer stability gates. Both APKs build; **21 host tests** (nine coordinator,
five domain, three queue, two SaveStore, two motion) and **eight asset tests** pass. Lint reports
**zero errors/22 warnings**; the additional warning is the intentionally pinned matching Filament utils
version. Both GLBs pass Khronos Validator 2.0.0-dev.3.10 with **zero errors/zero warnings**, no ignored
issues. Cartridge/receiver/audio regeneration is byte-for-byte identical on this host.

**All 14 RP5 tests pass with 50 native handoffs in 113.466 s on the final source.** The preceding full
run passed in 113.482 s before synchronous-handler hardening of the setup barrier. The targeted native
invalid-load/existing-SRAM proof passed with three cycles in 2.815 s. Version 7 is installed/open.

The new ordinary-UI test starts with Tools closed, explicitly opens it to choose original files, then
imports moderate red/green/blue/yellow quadrant print swatches onto the actual shared mesh. GPU PixelCopy
verifies all four positions (no vertical/horizontal flip), with existing label upload/apply gates.
Motion off snaps focus to the selected index. Motion and alternate shortcut both persist across
Activity recreation; the isolated debug preferences are reset afterward. The owner database/art/ROMs
are untouched. Existing independent-label/folder/removal/queue/save/native checks all remain in All.

CollectionProfileActivity is debug-only and uses three instances with original ORBIT FIELD labels.
It matches the immersive 1920×1080 viewport, waits for assets and one second of warmup, then renders
10 seconds at rest, 20 seconds of retargeting every 150 ms, and six seconds of repeated insertion/ejection.
The test polls SurfaceFlinger every second, deduplicates actual presentation timestamps (column two),
rejects zero/pending fences and applies 0.5-second margins at phase boundaries. Median/p95/p99 below
measure actual presentation, rather than Compose frame callbacks or just accepted beginFrame calls.

| Original viewport phase | Frames | Median ms | p95 ms | p99 ms | Intervals >25 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Rest | 540 | 16.664 | 16.752 | 16.828 | 0 |
| Rapid navigation | 1,140 | 16.664 | 16.750 | 16.818 | 0 |
| Insertion/ejection | 300 | 16.665 | 16.739 | 16.823 | 0 |

Separately measured CPU submission p95: **1.526 ms**, **2,161/2,161 callbacks submitted**. Performance
assertions require >100 real presentation samples per phase and p95 ≤1.5 reported refresh periods;
the threshold was not changed. This is a short isolated viewport test, not all owner artwork workloads,
whole-app input latency, ten-minute sustained thermals or a memory-stability assertion.
After restoring the ordinary owner library, one aggregate snapshot was **183,874 KiB PSS (~179.6 MiB)**,
including **8,908 KiB GL mtrack**. It is not a comparative leak/plateau measurement.

Initial failures were fixed and rerun: empty-Compose-rule helper type/import mistakes; legacy chooser
helpers needed to open Tools; Compose updates needed flushing before independently clocked GPU sampling;
UiAutomation passes argv directly, so shell quotes were literal. Fully saturated green was desaturated
by the filmic highlight mapper; original mid-tone print swatches retain the same quadrant assertions.
The recreation check exposed a genuine toggle race: compute the next value once before emitting Flow,
then persist that value. No assertion was skipped or performance gate relaxed.

A repeated native run exposed SIGSEGV in mGBA save-memory access after a rejected ROM with an existing
battery file. Pinned GLRetroView can emit SurfaceCreated after initializeCore swallows a load error;
async error delivery can arrive later. The adapter now primes its private lifecycle scope observer and
checks successful RenderLifecycleObserver registration using a synchronous Main Handler queued after
upstream's synchronous post. It restores SRAM only after that success barrier, still before retro_run.
The fixture now always seeds battery bytes for the invalid-ROM recovery test, then performs valid
sessions. All final 50 cycles and original battery tests pass. ADR 0007 records the implementation
dependency that must be re-audited when changing the frontend version. No crash is hidden by deleting
the save, weakening invalid-ROM recovery or substituting mock frames.

Reproduce after the normal build:

```powershell
python tools/generate_cartridge.py
python tools/generate_sound.py
python tools/test_assets.py
node tools/validate_assets.cjs
.\tools\Test-Device.ps1 -Serial <serial> -Suite Render
.\tools\Test-Device.ps1 -Serial <serial> -Suite All -Cycles 50
adb -s <serial> logcat -d -s CartridgePacing:I '*:S'
```

See ASSET_PIPELINE for the pinned validator installation and offline studio-generation command.
Only original source/assets and aggregate results are committed; screenshots/private device logs are
ignored. [Android 13 FrameTracker](https://android.googlesource.com/platform/frameworks/native/+/android-13.0.0_r1/services/surfaceflinger/FrameTracker.cpp)
and [UiAutomationConnection](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/core/java/android/app/UiAutomationConnection.java)
were inspected for timestamp ordering and direct argv execution.

Owner review of material/slot/motion and optional sound is requested on the installed build. Still
required: speaker/headphone contact/audio timing, asymmetric real-scan fidelity, manual close-up art
direction, longer real-library/gameplay CPU/GPU/PSS/audio/thermals/battery/latency, physical sleep/reboot,
OS process death and held-controller disconnection. M0 remains passed; M1/M2 sustained acceptance stays
open. Scripted mesh refinement does not imply manual production Blender polish.

## M3 library management — 2026-10-09

Final version 0.3.0-m3-library / code 8, same RP5 Android 13 / API 33 / firmware
`RP5_V1.0.0.189_20251128_174732_user`, Adreno 650 / GLES 3.2 / 1920×1080 landscape at 60 Hz.
JDK/Gradle/SDK/NDK and dependency pins remain unchanged. No test result below implies a device reboot,
OS process-death, SD-removal or sustained thousand-game workload result.

| Check | Actual result |
| --- | --- |
| Both APK builds + host tests + lint | BUILD SUCCESSFUL, final required task group 14 s |
| Host tests | 27, zero failures/errors: coordinator 12, query/clock 3, domain 5, motion 2, saves 2, scrape queue 3 |
| Lint | 0 errors / 23 warnings; added URI KTX style warning, no broad suppressions |
| Structural assets | 8 pass; M2 production GLBs/KTX/WAV unchanged |
| Focused management suite | 2 pass / 8.046 s before final picker/history hardening |
| Final All suite | 16 pass / 119.638 s, 50 real native handoffs, final picker/history source |
| Installed app | code 8 / 0.3.0-m3-library, MainActivity launched after suite |

The controller/details test injects ordinary L1/R1/A/B keys and a D-pad hold through a display frame.
It uses the production UI/Room/Filament/native core with isolated original ROMs. It edits title/year,
sorts a second-imported title ahead of the first while preserving focused hash, filters favorites,
recreates, persists an empty search, resets, runs internal emulation, pauses/ejects and recreates to
check saved playtime/last-played. Missing metadata renders "Not set" and does not block launching.

The v4 schema fixture upgrades explicitly to v5, indexes a nested original DocumentsProvider and
checks a second scan performs **zero additional ROM reads** for seven positively stamped documents.
Verify all reads all seven. A failing nested query cannot mark unseen files missing. A subsequent
complete scan detects the deleted original, retains a playable cache and accepted metadata/favorite.
Deleting that test cache makes launch fail; restoring its original and rescanning repairs the same
hash. Changed bytes under the same document name create a new hash and retain the old enrichment.
Two overlapping roots deduplicate; forgetting both keeps seven library entries and metadata.
Existing migration tests now exercise the full v1/v2/v3→v5 chains without destructive fallback.

Host tests separately verify stable ties/same-title distinct hashes, search/favorites intersection,
canonical focus persistence, empty-view recovery, pause-time exclusion and per-game failed-history
retry across two sessions. Initial count failures came from legacy empty-slot fixtures; updated
fixtures preserve the same behavior assertions. The first device edit test used key-down/up within
one frame; an 80 ms hold matches navigation's Choreographer polling. All failures were corrected.

The unchanged isolated M2 presentation gate also passes: 540 rest / 1,140 navigation / 300 media-motion
intervals; presentation p95 16.732188 / 16.731927 / 16.726042 ms, respectively, zero >25 ms gaps.
CPU submission p95 1.411094 ms, 2,161/2,161 submitted. It samples 36 seconds after warmup with phase
margins; this is a renderer regression gate, not large-library/thermal/audio/input-latency profiling.

Reproduce with the existing ignored local toolchain (or configured SDK/JDK):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools/gradle-home'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
python tools/test_assets.py
.\tools\Test-Device.ps1 -Serial <device-serial> -Suite Manage
.\tools\Test-Device.ps1 -Serial <device-serial> -Suite All -Cycles 50
```

Before calling full M3 acceptance complete, physically review a real collection with:

- R1 details/L1 favorite; rapid navigation after each sort/filter; no-match and reset recovery.
- Local metadata edits/clears and scraped label/media/metadata provenance retained after relaunch.
- Automatic discovery of a new ROM on open; opt-out; explicit Rescan/Verify all; cancel while scanning.
- Persisted grants across reboot, revoked/reselected roots, removable SD absence, overlapping roots.
- Same hash added/moved/recovered without duplicate entries or loss of saves, favorites or accepted labels.
- Missing original with/without working copy, wrong-ROM recovery rejection and reselecting the exact ROM.
- Large-library input/CPU/memory/private-storage profiling; disk-full/index/history write failures.
- Longer gameplay, controller disconnection, sleep/background/process death, thermal/battery/audio checks.

Scrape packs, selected-subset/preferred-region workflows and the deferred scraper review redesign remain
open. Existing live API proof is historical; no new provider API/schema/dependency changes were made.

## M3 scraper review — version 9, 2026-10-09

The preceding version-8 enrichment deferrals are historical. Selected/missing-label scopes, saved
region/language preferences, current-title-first review, protected fill-missing and persistent undo
are now implemented; see M3_SCRAPE_REVIEW/ADR 0010. M0 remains passed; full M3, M1 sleep/reboot and
M2 sustained/artistic/thermal gates remain open. No new dependency/native assets or thresholds changed.

On the connected **Retroid Pocket 5**, Android 13/API 33, arm64/Adreno 650, 1920×1080@60 Hz,
firmware **RP5_V1.0.0.189_20251128_174732_user**:

| Check | Actual result |
| --- | --- |
| Final required Gradle build, test APK, unit tests and lint | Build successful; 31 host tests, 0 failures; lint 0 errors / 24 warnings |
| Original asset checks | 8 pass |
| Focused Review suite | 4 pass in 7.968 s |
| Final All suite (`-Cycles 3`) | 19 pass, 0 failed/ignored, 105.499 s |
| Earlier All run (`-Cycles 50`) | Native 50-handoff assertion passed; 18/19 tests passed, one old UI test omitted scrolling to the new off-screen action |
| Fresh real provider URL/metadata/region probe | 1 pass in 1.882 s; no commercial artwork/ROM download |
| Final app installation | Version 9 installed and MainActivity launched |

New tests prove selected hashes are published before a provider request, only the current title is
searched, source-tagged ranking retains alternatives/unknown order, resumed review reads current
accepted data, fill-missing protects manually edited values and no-op filling does not erase undo.
Production Room/Filament on RP5 verify persisted Japan/French preferences, selection of a target hidden
by favorites, actual label texture readiness, draft recreation and restoration of saved query/focus,
preferred-label apply, French synopsis fill while keeping a local year, reopened undo and one search
across repeated recreation. The selected original fixtures contain no commercial labels/ROMs.

Schema 5→6 reopening tests atomically restore enrichment without rewinding source URI, favorite,
playtime/last-played, query or a save-state file. Label recipe and extra-media undo are also checked.
Existing v1/v2/v3/v4 upgrades now reach v6. File journal v1/untagged candidate compatibility and v2
tag/outcome round-trip pass. Resume cancels a gated second request after accepting first-title metadata;
reopening repeats only the unfinished request and preserves the first result/choice.

Corrected failures: host scheduling initially used actual IO outside the test scheduler; production
still defaults to IO while tests inject a deterministic dispatcher. Memory journal now agrees with the
file journal's empty-batch behavior. A new UI test now opens Tools; an older test scrolls the review list
to its action. No assertions were dropped or performance thresholds weakened. Test databases, original
provider fixtures and private preferences are isolated from the owner's collection.

The final existing 36-second original-art viewport regression sampled 540 rest / 1,140 rapid-navigation /
300 insertion-ejection intervals. Presentation p95 was **16.735937 / 16.746094 / 16.731145 ms**, with
zero gaps above 1.5 refresh periods. CPU submission p95 **1.540365 ms**, 2,161/2,161 submitted. This
remains an isolated short regression, not proof of sustained large-library/gameplay/input latency.
Opaque review pauses rendering; actual 3D draft resumes it. Gameplay/context ownership is unchanged.

Reproduce with the configured local SDK/JDK (see README):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools/gradle-home'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
python tools/test_assets.py
.\tools\Test-Device.ps1 -Serial <device-serial> -Suite Review
.\tools\Test-Device.ps1 -Serial <device-serial> -Suite All -Cycles 3
.\tools\Test-Device.ps1 -Serial <device-serial> -Suite Live
```

Live is opt-in, account-free metadata/URL probing; availability/coverage may change. Offline test suites
use only original fixture art and diagnostic ROMs, with production Room/SAF/Filament/mGBA. Tests report
JUnit results explicitly because `am instrument` may exit zero on failures. The 24th lint warning is KTX
preferences-edit style; synchronous commit stays on IO and checks its failure result, without suppression.

Still required on RP5: owner/controller review with actual titles and long batches; D-pad/analog rapid
review navigation and X selection; API ambiguity/missing flat labels/partial timeouts; cancellation at
preview/download/commit; restart/reboot/revoked SAF grants; large-library storage and input profiling;
storage-full/write-failure injection. Existing real sleep, controller disconnection, sustained memory,
audio/thermal/battery/input-latency checks remain open. Scrape packs/multi-console scopes are not shipped.

## M3 offline pack increment — version 10

Both APKs and required checks pass: 34 host tests, 8 assets, lint 0 errors/24 warnings. Two focused
RP5 Pack tests passed in 3.521 s; full suite **21/21 passed in 108.076 s** with three native handoffs.
Android 13/RP5 firmware is unchanged. Version 10 installed/launched. Original fixture PNGs and a provider
that throws on every network call prove SAF import, real GLB preview/apply, metadata/manual protection,
source provenance, offline resume and persistent single-step undo. Bad image checksum and unknown version
leave no retained pack. Host tests reject traversal/absolute/data-bearing directory paths and a small ZIP
that expands past the 12-MiB entry bound. The portable PC helper's original PNG/homebrew-hash ZIP round
trip passed and contains only pack.json plus its image, never the ROM. A test-fixture JSON parenthesis
error was corrected before hardware testing; behavior assertions were not weakened.

Reproduce with required Gradle/asset commands, then `Test-Device.ps1 -Serial <serial> -Suite Pack` or
`-Suite All -Cycles 3`. SCRAPE_PACK_FORMAT defines limits and the PC helper. M3 functional slices are
implemented; very large actual collections, revoked/reboot SAF grants, storage-failure injection and
shared sustained hardware gates remain open. Continue to M4 under the owner's through-M6 authorization.

## M4 layouts — version 11, 2026-10-10

Required Gradle tasks/build/test APK/lint pass; **37 host tests**, **eight asset checks**, lint 0 errors /
24 warnings. The final RP5 All suite passed **23/23 in 138.594 s**, `-Cycles 3`. Earlier `-Cycles 50`
passed the native fifty-handoff assertion and 22/23 tests; the remaining collection-menu overflow was
fixed before the final run. Version 11 is installed/launched on the same Android 13/RP5 firmware.

Two new RP5 cases cover twelve original homebrew games/labels and a separate 500-original-record
collection. They verify three layouts, focus/layout persistence, actual GLB label preview/pixels,
internal gameplay/pause/eject, D-pad navigation to the scrollable View action and synthetic stick
dead zone/held repeat/reversal. The scale case makes 240 rapid moves and recreates the nine-instance
pool with bounded 3/9-label windows. Its PSS sample was 236,859 → 258,807 KiB, a short process sample
that includes accumulated test/native allocations, not a memory-plateau or distinct-artwork claim.

The original-art SurfaceFlinger benchmark now runs 54 seconds. Presentation p95: rest **16.752500**,
horizontal rapid movement **16.742135**, insertion/ejection **16.741823**, vertical **16.748073**,
nine-object grid **16.744531 ms**. Samples: 540/1,140/300/420/540 frames; zero intervals exceeding
1.5 refresh periods in those windows. CPU submission p95 1.732031 ms; 3,241/3,241 submissions. The
unchanged p95 acceptance threshold remains 1.5 refresh periods. Startup, sustained gameplay, thermal
behavior, 500 distinct label textures and input-to-photon latency are not established by this test.

Failures corrected: neutral stick/key releases between frame polls now reset repeat immediately;
the collection menu scrolls within the screen, follows controller focus and orders actions consistently.
Touch tests scroll to actual off-screen actions. No behavior assertions or thresholds were removed.
Debug presentation preferences are isolated per fixture database, preserving owner settings.

Reproduce with required Gradle/asset commands, then `Test-Device.ps1 -Serial <serial> -Suite Layout`
or `-Suite All -Cycles 3`; fifty handoffs use `-Cycles 50`. See M4_PROGRESS/ADR 0012. Physical owner
layout/stick review, dense distinct artworks, sustained grid profiling and shared sleep/reboot/storage/
controller-disconnection/thermal/audio gates remain open. M5 is the next active engineering increment.
