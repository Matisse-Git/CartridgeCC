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
was resolved from JitPack until 2026-10-16, when the same tag's source became the `libretrodroid`
module (ADR 0027, `libretrodroid/UPSTREAM.md`). `GLRetroView` is a GLSurfaceView and initializes the game on its GL thread.
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
  can block compilation. M0 requested no runtime network permission; the later scraper adds INTERNET.

## M1 implementation pins

Room runtime/compiler/Gradle schema plugin 2.7.2 and KSP 2.1.20-1.0.32 match the existing Kotlin 2.1.20
toolchain. Their resolved POMs identify Apache-2.0. Compose UI tests use the existing 2025.04.01 BOM;
Espresso Intents 3.6.1 supplies picker-result stubbing only. The genuine renderer/core still execute.
Filament 1.72.1 MaterialInstance.duplicate, RenderableManager material binding and Texture.Builder.usage
were checked against that tag's Java source. Its default glTF ubershader exposes baseColorMap; runtime
generated mipmaps need explicit GEN_MIPMAPPABLE usage. See ADR 0004 and M1_PROGRESS for scope/results.

## Online proof brought forward

The owner prioritized API scraping over custom-image import. The M1 scraper now uses RetroBase's public
v1 API with Android's HTTPS/JSON facilities, adding no HTTP SDK dependency or app credential. Room v2
persists independently accepted selections through an explicit migration. Existing UI/Filament preview
and private original caching are reused; native surfaces/emulation remain isolated. See ADR 0005,
PROVIDER_RESEARCH, SCRAPING_REPORT and TESTING for contract inspection and actual RP5 results.

## Larger library and saved review queues

Room schema 4 keeps the ordered-slot/game model, adds granted directory URIs and persisted removal
exclusions, and explicitly migrates v1→v2→v3→v4. Legacy repeated hashes collapse; distinct hashes remain.
DocumentsContract tree traversal supplies read-only nested imports on IO without a new storage
dependency. Three Filament instances serve a variable-length collection via a visible window and global
focus; no GLB per-ROM allocation. AtomicFile-based ScrapeJournal persists minimal target/progress state
and individual candidate files, restoring only current candidates to RAM. No new dependencies or pins.
See ADR 0006 and LIBRARY_PROGRESS for API inspection, physical proofs and actual scaling limits.

## Save reliability

Version 6 keeps the lifecycle STARTED until SurfaceCreated and battery restoration/readback complete,
then starts emulation and waits for FrameRendered. An original C11/JNI SaveBridge reads/restores public
Libretro memory and captures states from the existing loaded core, avoiding the pinned frontend's
capture-buffer leak/ignored SRAM result. No new dependency or second core; changed RAM checkpoints
on GL/IO with explicit bounds and visible errors. See ADR 0007, SAVE_RELIABILITY and TESTING.

## M2 authorized refinement

The owner requested M2 now, retaining unfinished M1 stability validation. Refine the original GLB
generator/PBR maps and add an independent physical receiving-slot GLB. Generate original HDR
softboxes and prefilter offline with cmgen 1.72.1; pin filament-utils 1.72.1 for the official KTX loader.
Keep three cartridge instances, bounded per-game label textures and exclusive GPU handoff unchanged.
Centralize phase poses/durations, apply reduced motion to the carousel, add optional original contact
sound and move touch controls onto a quiet temporary shelf. Measure actual SurfaceFlinger presentation
and separately report CPU submission; do not infer sustained performance from a short sample.

The regression also exposed a pinned-frontend startup race: SurfaceCreated can follow a failed core
load before its async error is received. Require the successful render-lifecycle observer registration
before battery access; always test invalid ROM with existing SRAM. See ADR 0007's correction and
ADR 0008/M2_PROGRESS for API evidence, physical results and remaining art/performance limits.


## M3 continuation — 2026-10-09

Owner requested M3 after the verified M2 increment. Keep all native/3D/dependency pins and exclusive
surface barriers. Add Room v5 migration, canonical identity-preserving view projection, optional
metadata/details, favorites/order/search, conservative SAF size/mtime indexing and Running-only history.
The implementation/validation is in M3_PROGRESS/ADR 0009/TESTING. Provider stamps cannot establish
content identity, so full verification remains explicit; incomplete traversal cannot imply deletion.
Large-library scaling and physical reboot/revoked-grant tests remain gates, alongside earlier sustained
M1/M2 checks. API scraping remains the primary artwork path; its deferred review redesign and
subset/region/pack extensions remain open. No new core/system/layout dependency is adopted.
