# M2 studio-media refinement — 2026-10-09

Version 0.2.0-m2-studio (versionCode 7). The owner requested M2 now; M0 remains passed, while M1's
longer stability/sleep/reboot tests remain open. M2 visual refinement is implemented and physical
validation is recorded below. Final artistic approval and sustained workload profiling remain open.

## Implemented

- Reusable 6,866-triangle/four-material genuine GBA GLB: smooth segmented bevels, recessed rounded
  label, grip ribs, shell seam and plated contacts. Original roughness/micro-normal textures and
  explicit tangents. Existing API/custom label recipes bind to the same one named label primitive.
- Original analytic HDR studio softboxes, offline GGX prefilter and diffuse SH through matching
  Filament cmgen 1.72.1. Official filament-utils 1.72.1 KTX loader. No photographed/commercial HDR,
  always-visible skybox, bloom or runtime environment processing.
- Independent 1,180-triangle physical receiving slot appears during insertion/ejection. Its lip
  occludes the descending cartridge; it stays out of view at rest.
- Central lift/align/slide curves with a 540 ms linear insertion clock and 420 ms reverse ejection.
  Focus retargets without queued animations. Motion off also snaps the carousel and clears velocity.
- Optional original quiet 100 ms contact sound, off by default, separate from emulator audio.
- A closed-by-default touch Tools shelf replaces the permanent toolbar. Controller shortcuts,
  actual API label choices, saves and native renderer handoff remain intact. Motion/shortcut toggles
  capture their next value before Flow emission so the same value is persisted and displayed.
- Failed-core startup guard: pinned SurfaceCreated can follow rejected game loading before asynchronous
  error delivery. Require successful render-lifecycle observer registration before battery memory access.
  The physical invalid-ROM fixture now always includes existing SRAM, then proves recovery into valid play.

## Files and decisions

MediaMotion.kt and Navigation.kt define deterministic phase/snap behavior; MainActivity and
CollectionCoordinator share timings and integrate the shelf/preferences/sound. MediaSound.kt owns
SoundPool. CollectionAssets.kt loads bytes once on IO; CartridgeSurface owns receiver/IBL GPU resources
and releases them alongside the cartridge before gameplay. LibretroEngine's setup guard is documented
in ADR 0007's correction. ADR 0008 records the original-assets/offline-IBL/direct-rendering choice.

generate_cartridge.py/glb_geometry.py, generate_studio.py/Build-Studio.ps1 and generate_sound.py reproduce
the packaged GLBs, PBR PNGs, KTX and WAV. validate_assets.cjs uses the pinned official Khronos validator;
test_assets.py checks the physical/UV/material/KTX contracts. CollectionProfileActivity, RenderPacingTest
and RenderRefinementTest are debug-only/isolated original fixtures. Existing UI tests explicitly open
Tools; SurfaceProbeActivity seeds failed-load battery data. Test-Device.ps1 exposes Render and All suites.
Product/architecture/pipeline/roadmap/testing/license/README/agent notes describe the same scope.

## Verification

Both APKs build; **21 host tests**, **8 asset checks** and lint pass, with **0 errors/22 warnings**.
The extra warning is the intentionally matching pinned filament-utils version. Both GLBs pass official
Khronos validation with **0 errors and 0 warnings**. The targeted failed-ROM/existing-SRAM recovery test
passes with three native cycles in **2.815 s**. All **14 RP5 tests** pass with **50 native handoffs**
in **113.466 s** on final source, including the synchronous setup barrier. The preceding full run also
passed in 113.482 s. Version 7 is installed and open.

The isolated immersive original-artwork presentation benchmark samples rest, rapid retargeting every
150 ms, and insertion/ejection. Actual SurfaceFlinger presentation timestamps are distinct from the
separately reported CPU submission cost. The final run measured **16.74–16.75 ms presentation p95**
in all phases, **no intervals above 25 ms**, and **1.526 ms CPU submission p95** (2,161/2,161 submitted).
Rest/navigation/motion have 540/1,140/300 valid samples with 0.5-second phase margins; no pending/zero
fence timestamp is treated as a frame. Final details are in TESTING.md.
This is a short 36-second workload after shader/asset warmup, not a ten-minute thermal/battery/leak test.

Initial failures were investigated rather than skipped: the Tools shelf required real test navigation;
Compose state needed flushing before independently clocked GPU sampling; UiAutomation executes argv
directly and does not strip shell quotes. Original moderate print swatches avoid the filmic mapper's
highlight desaturation while retaining the same orientation assertions. The recreation assertion found
a real saved-toggle race. A later repeated run found the invalid-core/SRAM native race, now guarded
with a deterministic recovery fixture. No performance sample/acceptance threshold was weakened.

## Remaining work

Owner review of material/camera/insertion feel, mechanical sound on speakers/headphones, close-up manual
mesh art direction and fidelity of real regional scans. Larger libraries and sustained gameplay still
need CPU/GPU/memory/audio/thermal/battery/input-latency profiling. Reboot, OS process death, sleep and
held-controller disconnection remain the existing checklist. Context sharing/crossfade, ASTC/KTX2,
disc media, new consoles and alternative layouts are not introduced. Licensing/source-release gates
remain unchanged; scraper UX redesign is still deferred at the owner's request.

Next: use owner feedback to calibrate the M2 look/motion, then perform sustained RP5 profiling before
expanding systems/layouts. See ASSET_PIPELINE for complete regeneration commands and TESTING for repro.
