# Project Cartridge

Native Android, controller-first physical-media collection with **embedded** GBA emulation.
This repository implements an M0 feasibility slice, not the finished product. No RetroArch
installation, external emulator intents, browser renderer, commercial ROM, BIOS, or proprietary
artwork is used.

**Status:** debug APK builds; unit tests and asset checks pass. Runtime rendering, audio,
physical controller input, and GPU handoff have **not** been tested on an Android device.
M0 is not accepted until the device checks in [docs/TESTING.md](docs/TESTING.md) pass.

## Prerequisites

- Windows, Linux, or macOS with JDK 17 or 21 (validated here with Microsoft OpenJDK 21.0.8).
- Android SDK: platform 35, build tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1,
  and platform-tools. Android Studio can install these, or use `sdkmanager` below.
- First build requires access to Google Maven, Maven Central, JitPack, and GitHub codeload.
  After dependencies and the source archive are cached, the application runs offline.
- Physical arm64 Android device with OpenGL ES 3.0; RP5's Android 13 is the target.
- Python 3.10+ is optional for asset regeneration and structural validation. Blender is optional.

## Build and tests

Set `JAVA_HOME`, and set `ANDROID_HOME` or put `sdk.dir=C:/path/to/Android/Sdk` in
the ignored `local.properties`. Use forward slashes on Windows. Review SDK licenses with
`sdkmanager --licenses` when installing the SDK.

```powershell
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools" "ndk;27.2.12479018" "cmake;3.22.1"
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
python tools/test_assets.py
```

On macOS/Linux use `./gradlew` in place of `.\gradlew.bat`. The wrapper pins Gradle 8.13
and verifies its distribution SHA-256. The CMake build fetches the **hash-pinned mGBA 0.10.5
source**, builds the arm64 Libretro core, and packages `libmgba_libretro.so` in the APK.
There is no separately downloaded core binary. An existing source checkout can be used with
`"-PmgbaSource=C:/absolute/path/to/mgba-0.10.5"`.

Output: `app/build/outputs/apk/debug/app-debug.apk`.

This machine has local tools under ignored `.tools/`; to reproduce its environment:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools/gradle-home'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
```

## Run

```powershell
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.cartridge/.MainActivity
```

The collection opens with three clearly named cartridge studies, all using one instanced GLB.
Browse with D-pad left/right, left stick, or the touch arrows. Choose an uncompressed `.gba`
document for a slot, then press A or Insert. The file picker uses SAF and requests a persistable
read grant. A bounded, SHA-256-addressed working copy lives in app-private storage; the original
document is unchanged. Slot assignments are **session-only** in M0; reselect a file after relaunch.
SaveRAM and one save-state slot per content hash persist across sessions.

L3 + R3 opens the app-owned pause menu; Start + Select is an alternate configurable combination.
Back also pauses. D-pad up/down selects menu actions; A activates and B resumes. Android gameplay
A/B go directly to the corresponding Libretro A/B; verify Retroid's selected button-layout mode.
The first button of a two-button shortcut may reach the game before the second is pressed.

The lift is an M0 surface-transition cue. Final insertion/ejection choreography, label assignment,
Room indexing, details, other layouts, and HOME registration are not implemented.
Backgrounding ends the current session after saving RAM; full session sleep/resume comes later.

## Engineering record

- [Technical plan and feasibility blockers](docs/TECHNICAL_PLAN.md)
- [Product and UX scope](docs/PRODUCT.md)
- [Architecture and lifecycle](docs/ARCHITECTURE.md)
- [Milestones](docs/ROADMAP.md)
- [Asset pipeline](docs/ASSET_PIPELINE.md)
- [Tests and actual results](docs/TESTING.md)
- [License audit and distribution obligations](docs/LICENSING.md)
- [M0 report and created files](docs/M0_REPORT.md)

LibretroDroid is GPL-3.0-or-later. Shipping this integration requires a GPL-compatible distribution
plan and complete corresponding source. See the audit before distributing; no project-wide
commercial licensing decision has been made by this feasibility work.
