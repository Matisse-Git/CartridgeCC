# Save reliability increment — 2026-10-09

Version 0.1.0-m1-saves (versionCode 6). M0 remains passed; M1 remains in progress.

## Implemented

- Battery restoration/readback completes after SurfaceCreated and before RESUMED/first retro_run.
  Games that read SRAM once at boot now receive their existing progress at that first read.
- Changed battery memory checkpoints every 15 seconds during play, on pause, after manual state
  operations and on normal ejection/background shutdown. Unchanged/empty RAM avoids extra writes.
- A failed checkpoint keeps usable gameplay paused/running and shows a warning until a successful
  retry. Capture timeout releases the session safely; stored files are not repaired or deleted.
- SaveBridge captures battery bytes, restores bounded data and captures states through the public
  ABI of the already-loaded mGBA core. All calls run on its GL thread under the engine mutex;
  RTLD_NOLOAD/close never creates a second emulator. JNI buffers are released and serialization
  failure is checked. Pinned frontend SRAM/state capture leaked buffers and SRAM JNI ignored rejection.
- SaveStore caps battery data at 128 KiB and states at 16 MiB. Oversized/empty-state input is rejected
  without changing its source. Unique temporary paths, fsync/atomic rename and a shared directory guard
  prevent competing adapter instances from publishing mixed saves. No new storage permissions.

## Files and decisions

LibretroEngine, EmulationEngine/EngineStatus and MainActivity implement the boot barrier, periodic
job, changed-byte cache and visible failure warning. SaveBridge.kt/save_bridge.c and CMakeLists add
original, bounded native glue; app build metadata packages it alongside the unchanged core.
SaveStore adds bounded IO and serialized replacement. Dependency versions are unchanged.

BatterySaveTest uses the production Compose UI/engine and an original ARM SRAM diagnostic. SaveStoreTest
covers conflicting writers and malformed-input preservation. FixtureDocumentProvider, Build-Probe.ps1,
Test-Device.ps1 and tools/probe/gba_sram_probe.S expose only original fixtures. README, architecture,
product/testing/roadmap/license notes and ADR 0007 record the choices and results.

## Actual verification

Both APKs build; 19 host tests, four GLB checks and lint pass (zero errors/21 existing warnings).
The two real RP5 battery tests passed in 28.462 s. All twelve deterministic device tests passed with
50 native surface handoffs in 70.337 s. No dependency pins changed; the updated app is installed/open.
GPU pixels prove the boot latch reads the restored SRAM, changes on A/B, and restores across repeated
ejection and backgrounding. A 32 KiB save is read from private storage without any save-state file.
Oversized native restoration leaves memory unchanged; oversized stored input is preserved through
failed preparation and remains recoverable after replacing only the isolated fixture's invalid bytes.

The production 15-second checkpoint is observed while still Running. A blocked unique save destination
causes a visible warning, preserves RAM and recovers after retry. The fixture then deliberately skips
graceful shutdown, releases native resources and recreates the Activity; the checkpoint boots green.
This is abrupt fixture teardown, **not** a claimed OS force-stop/process-kill or physical reboot test.
Tests do not modify owner ROMs, accepted artwork, library entries or saves.

The first native build exposed missing C++ runtime linkage. This small ABI bridge needs only C11,
so it now uses C without another C++ runtime. Windows competing-file replacement initially failed;
shared-directory serialization resolved it and the full host suite passed. No test gate was relaxed.

## Remaining work

Sudden termination can lose changes since the latest completed checkpoint; Android scheduling and
storage failures can extend that interval. Full-state/seamless resume, backups, state-version headers,
save-management UI, power-loss durability and disk-full recovery are not completed. Long-run memory,
frame/audio timing, controller disconnection, real sleep/reboot and actual OS process death remain
physical validation. Short tests and code inspection do not establish a leak-free application.

Next: finish the GBA media/insertion asset, then profile larger libraries and sustained gameplay.
The owner deferred scraper UX redesign; its working reviewed API workflow remains the main artwork path.

## Starting from a save state — 2026-10-10

Settings › Gameplay › Start from save state, off by default, with a per-game override.

- The manual state is loaded during `prepare`, before the first emulated frame and after the battery
  save has been restored. A session being resumed already has its state and is not affected.
- The pinned wrapper synchronises a loaded state's own battery memory into the core, which is what a
  manual Load State is expected to do. At a start that would roll an in-game save back to the moment
  the state was made, without the player asking. So the battery save and clock are restored a second
  time after the state: the console's memory comes from the state, the cartridge's from the last
  save. `lastWrittenRam` still matches memory, so no checkpoint is written because of it.
- No state: an ordinary start. A state that cannot be read, has the wrong PS1 core revision, or is
  refused by the core: the core is reset, the battery is restored again, the game starts from the
  beginning and a notice says so once. The state file is never rewritten or deleted.
- The state is only as recent as the last Save State. Progress made afterwards and kept only by an
  in-game save is still on the cartridge, but the game resumes from the older moment; saving
  in-game from there replaces the newer save, as loading a state by hand always could.

Verified on the RP5 with the original Game Boy probe (`GameOptionsDeviceTest`): the picture the
state was saved on is shown at boot; the battery byte stays at the value saved last, not the
state's; a per-game override returns that game to a fresh start; 4 KiB of junk in place of the
state starts the game from the beginning with the notice and is byte-identical afterwards; a
missing state reports nothing. Not verified: a PlayStation state at boot, and any commercial game.
