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
tests; `-Suite Library` runs two folder/durable-queue tests; `-Suite All` runs all ten deterministic tests.
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
- [ ] Eject/relaunch confirms SaveRAM continuity with a game that writes RAM. Missing state leaves
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
