# Tests and actual results

## Host results — 2026-10-09

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
.\tools\Test-Device.ps1 -Cycles 50
```

Pass `-Sdk C:/path/to/Android/Sdk` and optionally `-Serial <serial>` when needed. It generates the original
probe, installs both APKs, pushes the fixture, wakes the screen, runs instrumentation and checks the
JUnit result (ADB's process exit code alone is insufficient). Logs stay in ignored `.tools/device-test.log`.
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
