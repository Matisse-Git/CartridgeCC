# M5 — GB and Game Boy Color

Version 12 adds GB/GBC as the next audited increment. It reuses source-built mGBA 0.10.5,
LibretroDroid 0.14.0 and the existing internal surface handoff. No other emulator, ROM, BIOS,
commercial label, network core download or additional third-party dependency is bundled.

## Behavior

- SAF single-file and folder import accept `.gba`, `.gb` and `.gbc`. GB header colour capability
  determines GB/GBC identity even with a misleading extension. SHA-256 remains the identity;
  file bounds are 32 MiB GBA and 8 MiB plus 64-byte GBX footer for GB/GBC.
- Room 6→7 adds explicit system identity; legacy GBA entries keep their artwork, history and saves.
- A genuine, original tall GLB template serves GB/GBC. Reusable shells stay independent of game
  data. The bounded viewport holds nine slots, each with two reusable format instances, and draws
  only the matching shell. Label decoding is 512×448 for the tall shell, 512×256 for GBA.
- Y → Choose consoles selects any subset of imported systems. This title and All games send each
  game's precise platform to RetroBase. The reviewed queue, region/language choice, independent
  label/media/metadata selection, undo and private offline caching remain shared.
- Reviewed v1 packs accept the three supported platforms. Journal v3 records system identity and
  reads old v1/v2 entries as GBA; no automatic network search on launch.
- Battery saves include the separate Libretro RTC memory. An original wrapper around the unchanged,
  hash-pinned mGBA translation unit synchronizes live MBC3 clock registers and pending state-buffer
  writeback on the core GL thread. This fixes stale immediate checkpoints after loading a state.
  The bounded JNI bridge never initializes another frontend. Original files remain intact on error.

## Evidence and limits

The complete Android debug/test APK build, 40 host tests, lint and ten asset tests pass. All three
GLBs pass Khronos validation with zero errors/warnings. On RP5 Android 13, firmware
RP5_V1.0.0.189_20251128_174732_user, the two focused systems tests pass (10.807 s): original SM83
programs render distinct GB/GBC pixels, respond to A, save/load execution state, cold-boot restored
battery choices and retain a halted MBC3 clock. Migration and repeated mixed SAF scans pass.
The GBX footer identifies these original programs without a proprietary boot/header logo.

This is no claim of every mapper/peripheral or commercial game's compatibility. No link-cable,
camera, accelerometer, Super Game Boy or additional console integration is promised. The tall mesh
is a reproducible development asset; artist polish and owner label review remain open. A halted
clock diagnostic does not establish long-term clock drift or every RTC cartridge's behavior.
Sleep/resume, HOME and backup are M6. Disc systems are not implemented by this increment.

## Reproduction

```powershell
python tools/generate_gb_cartridge.py
python tools/test_assets.py
node tools/validate_assets.cjs
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
.\tools\Test-Device.ps1 -Serial <authorized-device> -Suite Systems
.\tools\Test-Device.ps1 -Serial <authorized-device> -Suite All -Cycles 50
```

Core/API evidence: [mGBA FAQ](https://mgba.io/faq.html),
[pinned Libretro frontend](https://github.com/mgba-emu/mgba/blob/0.10.5/src/platform/libretro/libretro.c),
[RetroBase developer reference](https://retrobase-collection.com/developers),
[public OpenAPI](https://retrobase-collection.com/api/public/v1/openapi.json), checked 2026-10-10.
Public platform names are exactly Game Boy, Game Boy Color and Game Boy Advance. Live title responses
expose regional `support-texture` variants, with incomplete coverage; no usage rights are inferred.

The full 25-test RP5 regression suite passes (148.971 s, three handoffs). The preceding longer run passed all 50 native handoffs; two obsolete scraper test action indices failed, were corrected, and pass in the full rerun. No runtime assertion was removed.
