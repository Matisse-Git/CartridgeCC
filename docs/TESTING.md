# Tests and actual results

## Host results — 2026-10-09

Validated on Windows with Microsoft OpenJDK 21.0.8, Gradle 8.13, SDK 35, NDK 27.2.12479018 and
SDK CMake 3.22.1. No Android device was attached (`adb devices -l` returned an empty list).

| Check | Actual result |
| --- | --- |
| `:app:assembleDebug` | Passed, real arm64 APK and source-built mGBA core |
| `:app:assembleDebugAndroidTest` | Passed; device-test APK compiled, test not executed |
| `:app:testDebugUnitTest` | 5 tests, 0 failures/errors |
| `:app:lintDebug` | Passed, 0 errors; dependency-update/orientation/arm64-only warnings remain |
| `python tools/test_assets.py` | 4 structural GLB tests passed |
| `tools/Build-Probe.ps1` | Original ARM diagnostic assembled and exported successfully |
| mGBA ELF inspection | AArch64 shared library, Libretro symbols exported, LOAD alignment 0x4000 |
| Default CMake FetchContent path | Passed; pinned source downloaded and SHA-256 verified |
| Device controller/render/audio/handoff | Not run; no RP5 or other device available |

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

## Real surface instrumentation probe

The debug-only `SurfaceProbeActivity` alternates a rendered Filament cartridge and the real packaged
mGBA view three times. Each cycle waits for a 3D frame, tears Filament down, waits for a core frame,
pauses, saves/loads, resumes, persists RAM/stops, and detaches. `SurfaceHandoffTest` checks completion
and captures failures. It requires a foreground arm64 device with GLES 3; compilation alone is not a pass.

The original diagnostic assembly has no proprietary logo/BIOS/data. It fills GBA VRAM, changes colour
while A is held, and enables a simple square-wave tone. It needs mGBA's HLE BIOS; no retail hardware
boot claim is made. It is generated only in ignored `.tools/probe/`, never bundled in the app.

On Windows:

```powershell
.\tools\Build-Probe.ps1 -Sdk "$env:ANDROID_HOME"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push .tools/probe/cartridge-probe.gba /data/local/tmp/cartridge-probe.gba
adb shell run-as dev.cartridge cp /data/local/tmp/cartridge-probe.gba files/cartridge-probe.gba
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

If this workspace's local SDK is used, omit `-Sdk` to use `.tools/sdk`. On another OS, assemble the
same `.S` using its NDK clang `--target=arm-none-eabi -march=armv4t -nostdlib -fuse-ld=lld
-Wl,-Ttext=0x08000000`, then use `llvm-objcopy -O binary`. Do not use an unknown downloaded ROM.

For manual testing, copy the generated probe to a user-visible location via ADB and choose it through
the ordinary SAF picker. A passing instrumentation probe still needs manual audio/input/pacing checks.

## Manual acceptance checklist

- [ ] Actual original GLB visible under warm lighting, correct label orientation and thickness; 3D
  rotation exposes sides/contacts. Focused cartridge larger, neighbours visible.
- [ ] D-pad taps/holds and analog/HAT navigation; neutral/dead zone; direction reversal; 20 seconds
  of rapid scrolling; no input queue or focus loss. Check retargeted motion at each library edge.
- [ ] Touch arrows/Insert/picker work. Cancelled SAF selection leaves the same cartridge focused.
- [ ] Assign original probe/user-owned .gba; A starts real mGBA frames in the same Activity/application.
  No external emulator intent, menu or fake screen. Gameplay A/B/L/R/Start/Select mappings verified.
- [ ] Audible probe tone/game audio; no crackle under normal speed; measure timing/input latency.
- [ ] L3+R3 and alternate Start+Select pause; Back pauses; held gameplay inputs released; menu D-pad/A/B
  works; overlay is above the surface and legible; emulator audio/frame progression stops.
- [ ] Save/load with a game state that visibly changes; eject/relaunch confirms SaveRAM continuity.
  Missing state shows an error and leaves gameplay paused. Corrupt/incompatible data reports failures.
- [ ] Repeated insertion/ejection (>=50 cycles), no duplicate session/thread or stale surface, same
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

Only **buildability** is passed. Real GLB rendering, physical controller navigation, core initialization/
video/audio, and crash-free surface handoff remain unverified. **M0 has not passed overall.**
