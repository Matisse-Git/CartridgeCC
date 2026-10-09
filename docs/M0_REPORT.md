# M0 delivery report — 2026-10-09

## Implemented

Compilable native Kotlin/Compose Android app; immersive landscape shell; direct Filament/gltfio
viewport; original genuine GBA GLB with embedded fictional label and PBR regions; three shared
instances, controller/touch focus and critically damped motion; SAF document input with bounded
content-hash storage; short lift and exclusive surface teardown; packaged source-built mGBA 0.10.5
with LibretroDroid 0.14.0, readiness/error flows and serialized lifecycle; app-owned pause, state
save/load and SaveRAM persistence; ejection restoring selection; motion/shortcut preference toggles.

Implemented real emulator API calls are not a claim runtime initialization succeeded. A debug-only
three-cycle surface-handoff/save/load fixture and original no-logo GBA diagnostic are ready for a device.

## Files created

This was an empty directory; no existing subsystem was rewritten.

- Root: `.gitignore`, `.gitattributes`, Gradle settings/build/properties, wrapper scripts/JAR/properties,
  `README.md`, `AGENTS.md`. Local SDK/NDK/Gradle caches and `local.properties` are ignored.
- `app/build.gradle.kts`, manifests/resources and `app/src/main/cpp/CMakeLists.txt`.
- `app/src/main/java/dev/cartridge/MainActivity.kt`: shell/UI and surface coordinator.
- `domain/SessionState.kt`, `domain/Navigation.kt`: lifecycle contract, selection, repeat and spring.
- `input/ControllerRouter.kt`: exclusive physical input routing.
- `rendering/CartridgeSurface.kt`, `assets/models/gba_cartridge.glb`: owned real 3D renderer and asset.
- `emulation/EmulationEngine.kt`, `emulation/LibretroEngine.kt`: abstraction and real integration.
- `storage/RomImporter.kt`, `storage/SaveStore.kt`: scoped input and atomic game saves.
- `app/src/test/.../DomainTest.kt`, `app/src/androidTest/.../SurfaceHandoffTest.kt`,
  `app/src/debug/.../SurfaceProbeActivity.kt`: host tests and compiled device fixture.
- `tools/generate_cartridge.py`, `tools/test_assets.py`, `tools/export_blender.py`,
  `tools/Build-Probe.ps1`, `tools/probe/gba_probe.S`.
- Product, architecture, roadmap, pipeline, technical plan, testing, licensing docs and three ADRs;
  preserved third-party license texts under `docs/licenses/`.

## Decisions and validation

Direct Filament gives explicit surface/engine ownership without SceneView's larger current toolchain.
One active renderer, separate contexts and explicit flush/detach barriers favor reliability over crossfade.
mGBA is source-built and hash-pinned rather than a mutable core download. LibretroDroid is used inside
the app, with no external emulator launch. Backgrounding deliberately stops/persists sessions in M0.

Debug app and instrumentation APK compilation, five JVM tests, four GLB structural tests, lint and
the probe assembly passed. Default core source-fetch/build path passed. Exact commands and report
locations are in TESTING. Native mGBA exports the Libretro ABI and uses AArch64 with 16 KiB LOAD alignment.

## Limitations and acceptance

No device attached; no Android rendering, audio, controller, lifecycle or surface performance claim is
made. Engineering cartridge needs Blender polish and real studio lighting. Final insertion/ejection,
directory/Room library, persistent slot assignments, metadata/artwork, full touch gamepad, full remapping
and seamless sleep/resume are deferred. Only one GBA core/arm64 target is built. GPL/MPL/LGPL
distribution obligations remain a release gate. Force-stop cannot guarantee a final save.

| M0 criterion | Result |
| --- | --- |
| Android app builds | **Passed** |
| Genuine GLB renders on Android | Implemented/asset validated; **runtime unverified** |
| Physical controller navigates state | Implemented/logic tested; **physical input unverified** |
| Internal Libretro GBA initialization and rendering | Adapter and native core compiled/packaged; **runtime unverified** |
| Crash-free 3D ↔ emulator surface transition | Fixture compiled; **device execution unverified** |

**M0 overall: not passed yet.** Required RP5 tests: run the surface fixture, then physical button/axis,
audio, save/load, invalid/missing file/core, 50 insertion/ejection cycles, focus/disconnection,
background/suspend/recreation and memory/frame-pacing checks. Recommended next task: connect RP5,
execute those feasibility gates and fix observed native/surface issues before M1 design work.
