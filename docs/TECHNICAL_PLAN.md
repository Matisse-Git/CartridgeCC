# M0 technical plan — 2026-10-09

## Inspected environment

The supplied directory was empty and had no Git repository or existing Android/emulation code.
Java, Gradle, SDK, ADB, Ninja and Blender were absent from PATH. A readable Microsoft JDK 21.0.8
was found in `C:/Program Files/Android/openjdk/jdk-21.0.8`. Git, Python 3.14 and system CMake
were present. SDK command-line tools, Gradle, Android platform/build tools, NDK and SDK CMake
were installed locally under ignored `.tools/`. Initially ADB enumerated zero devices. The owner then
connected and authorized the physical RP5; the device verification results are in TESTING.

## Selected stack

| Component | Pin | Reason |
| --- | --- | --- |
| Gradle / AGP | 8.13 / 8.9.2 | Verified combination, compile SDK 35, explicit Kotlin plugin |
| Kotlin / Compose compiler | 2.1.20 | Same compiler/plugin version; no invented upstream calls |
| Compose BOM / Activity | 2025.04.01 / 1.10.1 | Conservative, tested dependency set for the small shell |
| Lifecycle / coroutines | 2.8.7 / 1.10.2 | Flow state and scoped asynchronous operations |
| Filament + gltfio | 1.72.1, both | Direct ownership, glTF PBR and explicit GPU release |
| LibretroDroid | 0.14.0 | App-owned GLSurfaceView, audio, input, SRAM and state APIs |
| mGBA | 0.10.5 | Released source built locally as a GBA-only Libretro core |
| NDK / SDK CMake | 27.2.12479018 / 3.22.1 | Reproducible Android arm64 build, 16 KiB ELF alignment |
| SDK min / compile / target | 26 / 35 / 35 | RP5 API 33 supported; modern scoped document access |

Source inspections: [SceneView v4.53.0 version catalog](https://github.com/sceneview/sceneview/blob/v4.53.0/gradle/libs.versions.toml),
[Filament v1.72.1 ModelViewer lifecycle](https://github.com/google/filament/blob/v1.72.1/android/filament-utils-android/src/main/java/com/google/android/filament/utils/ModelViewer.kt),
[LibretroDroid 0.14.0 GLRetroView](https://github.com/Swordfish90/LibretroDroid/blob/0.14.0/libretrodroid/src/main/java/com/swordfish/libretrodroid/GLRetroView.kt),
[mGBA 0.10.5 native build](https://github.com/mgba-emu/mgba/blob/0.10.5/CMakeLists.txt).

SceneView's current tag uses a declarative `SceneView { ModelNode(...) }` API, not old `Scene`
examples. Its catalog pins AGP 9.4.1, Kotlin 2.4.20 and Compose BOM 2026.09.00. Those versions
were inspected, not adopted or tested. For this small feasibility viewport direct Filament avoids
that larger dependency/migration surface. Reconsider SceneView after the surface test if it saves
maintenance; direct integration here is about explicit ownership, not a claim SceneView is unstable.

LibretroDroid's tag compiles with SDK 33, min SDK 21, JVM 17 and CMake 3.22.1; its published AAR
was resolved from JitPack. `GLRetroView` is a GLSurfaceView and initializes the game on its GL thread.
Its private render lifecycle observer is installed after core initialization. The adapter uses a
per-session LifecycleRegistry, subscribes before initialization, and gates readiness on FrameRendered.
It avoids the upstream blocking serialization latch by catching failures inside a bounded GL queue.

## Device target

[Retroid's product specification](https://www.goretroid.com/products/retroid-pocket-5-handheld)
lists Android 13, Adreno 650, a 5.5-inch AMOLED 1080p/60fps panel, analog L2/R2 and Hall sticks.
The published CPU cluster matches the Snapdragon 865 family. The page's current RAM listing may
describe a later SKU; memory capacity is not hard-coded. Verify the actual device's ABI, resolution,
firmware, RAM and button layout with the commands in TESTING. This code packages only arm64-v8a.

Actual USB device inspection confirmed Android 13/API 33, arm64-v8a, kona board, Adreno 650/GLES 3.2,
1920×1080 landscape at 60 Hz and about 7.5 GiB reported system memory. The controller exposes D-pad
keys plus X/Y and HAT axes. Device firmware: RP5_V1.0.0.189_20251128_174732_user. No serial is stored here.

## Ordered feasibility work

1. Build the shell, generate genuine cartridge geometry and verify the GLB structure.
2. Render three instances, move the focus through state with a retargetable spring, route controller input.
3. Build/package mGBA from a hash-pinned archive and compile the LibretroDroid adapter.
4. Stop/flush Filament and detach its surface; mount only the emulator GLSurfaceView; wait for a core frame.
5. Pause/save/load/eject, detach the emulator, recreate Filament with the previous selection.
6. Run host checks, build the debug instrumentation APK, then execute the real-surface probe on RP5.

Steps 1–5 have compiled implementations. Real GLB rendering, physical navigation and a 50-cycle
native surface probe with framebuffer/input/save-load checks now pass on RP5. See TESTING for scope.

## Risks and blockers

- Long-duration audio, timing, lifecycle and controller-disconnection checks remain device work.
- GLRetroView's EGL recreation path and globally owned native frontend require strict single-session
  ownership. M0 stops on backgrounding; native context-loss recovery needs a real-device test and
  potentially a narrow upstream fix, not reliance on `preserveEGLContextOnPause` alone.
- SurfaceView/Compose overlay z-order and detach timing need repeated physical checks.
- GPL-3.0-or-later LibretroDroid affects the distribution strategy; mGBA also includes LGPL audio code.
  Audit/obligations are in LICENSING. No proprietary release is authorized by this prototype.
- Blender absent: the original GLB is usable engineering geometry, not production cartridge quality.
- Android 16 KiB compatibility is not claimed for the entire dependency chain merely because the
  source-built mGBA core has aligned ELF segments. RP5/API 33 is the initial runtime target.
- First build downloads source/dependencies; cold-build network restrictions and JitPack availability
  can block compilation. No runtime network permission is requested.
