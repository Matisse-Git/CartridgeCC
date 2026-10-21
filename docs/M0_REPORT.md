# M0 delivery report — 2026-10-09

This records the M0 snapshot. Later persistent-library/local-label work is in M1_PROGRESS; the installed
real API scraping increment and its validation/limits are in SCRAPING_REPORT and TESTING.

## Implemented

Compilable native Kotlin/Compose Android app; immersive landscape shell; direct Filament/gltfio
viewport; original genuine GBA GLB with embedded fictional label and PBR regions; three shared
instances, controller/touch focus and critically damped motion; SAF document input with bounded
content-hash storage; short lift and exclusive surface teardown; packaged source-built mGBA 0.10.5
with LibretroDroid 0.14.0, readiness/error flows and serialized lifecycle; app-owned pause, state
save/load and SaveRAM persistence; ejection restoring selection; motion/shortcut preference toggles.

Runtime initialization and rendering now succeeded on the owner's physical RP5. The debug-only fixture
supports 3–50 surface cycles, actual GPU framebuffer sampling, A/B routing, visible save/load restoration,
missing/invalid ROM cleanup and post-teardown memory snapshots. The original no-logo GBA diagnostic
is generated locally, not shipped as a ROM in the APK.

## Files created

This was an empty directory; no existing subsystem was rewritten.

- Root: `.gitignore`, `.gitattributes`, Gradle settings/build/properties, wrapper scripts/JAR/properties,
  `README.md`. Local SDK/NDK/Gradle caches and `local.properties` are ignored.
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

USB-validation follow-up modified the debug probe, SurfaceHandoffTest and original diagnostic assembly;
added `tools/Test-Device.ps1`; updated README and product/architecture/roadmap/pipeline/testing/
licensing/technical-plan/report documentation; added `docs/LABEL_SCRAPING.md`. Production renderer and
emulator code passed the device proof without a rewrite. Personal logs/screenshots, ROM working copies
and tool/build outputs remain ignored.

## Decisions and validation

Direct Filament gives explicit surface/engine ownership without SceneView's larger current toolchain.
One active renderer, separate contexts and explicit flush/detach barriers favor reliability over crossfade.
mGBA is source-built and hash-pinned rather than a mutable core download. LibretroDroid is used inside
the app, with no external emulator launch. Backgrounding deliberately stops/persists sessions in M0.

Debug app and instrumentation APK compilation, five JVM tests, four GLB structural tests, lint and
the probe assembly passed. Default core source-fetch/build path passed. Exact commands and report
locations are in TESTING. Native mGBA exports the Libretro ABI and uses AArch64 with 16 KiB LOAD alignment.

Physical RP5 firmware `RP5_V1.0.0.189_20251128_174732_user`, Android 13/API 33, Adreno 650/GLES 3.2,
arm64 and 1920×1080 landscape/60 Hz were inspected through ADB. The original GLB was visibly rendered.
The final 50-cycle device test passed in 20.490 seconds, including missing/invalid ROM error recovery
before real sessions. PixelCopy proved genuine gray/green output, A/B input and visible saved-state
restoration. The owner confirmed D-pad/stick navigation and ordinary gameplay/audio/pause/resume/eject/
save-load. The awake-screen and first-EGL-buffer timing failures were diagnosed and fixed in the fixture;
no fake emulator output was used. See TESTING for commands, exact scope and memory samples.

The requested label/media/metadata workflow is now a first-class documented requirement: local import,
single-title/selected-title/console/all-library scraping, thumbnail candidate review, independent choices,
3D preview and protection of manual selections. It is not implemented in M0. ScreenScraper was researched
as a candidate against official API documentation; developer credentials/terms and asset availability
remain unresolved. Per-game texture assignment is the next M1 artwork proof, full enrichment is M3.

## Limitations and acceptance

The brief native test and owner checks are feasibility evidence, not sustained performance/lifecycle
validation. Engineering cartridge needs Blender polish and real studio lighting. Final insertion/ejection,
directory/Room library, persistent slot assignments, metadata/artwork, full touch gamepad, full remapping
and seamless sleep/resume are deferred. Only one GBA core/arm64 target is built. GPL/MPL/LGPL
distribution obligations remain a release gate. Force-stop cannot guarantee a final save.

| M0 criterion | Result |
| --- | --- |
| Android app builds | **Passed** |
| Genuine GLB renders on Android | **Passed** on RP5; real mesh/texture visible |
| Physical controller navigates state | **Passed**; owner confirmed D-pad and left stick |
| Internal Libretro GBA initialization and rendering | **Passed**; packaged mGBA, GPU pixel proof and owner gameplay/audio check |
| Crash-free 3D ↔ emulator surface transition | **Passed**; 50 native cycles plus owner ordinary insertion/eject check |

**M0 technical feasibility: passed, 2026-10-09.** Still required on RP5: sustained ordinary UI insertion/
ejection, full button/layout/shortcut checks, actual SaveRAM continuity with a saving game, corrupt state/
invalid core cases, focus/controller disconnection, background/suspend/recreation and longer memory/
frame-pacing/audio/battery profiling. Short-run post-teardown PSS increased from ~141 to ~180 MiB;
its long-run plateau is unproven. Recommended next task: extract/test the M1 shell coordinator and
lifecycle recovery, then persist the small library and prove independent local cartridge-label textures.
