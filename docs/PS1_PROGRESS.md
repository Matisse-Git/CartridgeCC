# PS1 development increment — 2026-10-10

Version 17 / `0.7.0-ps1`. Owner priority: CUE/BIN games and existing user-provided BIOS files.

## Implemented

- Internal PCSX ReARMed arm64 core built from the exact source in ADR 0017, with native video/audio.
- Explicit PlayStation library identity and independent core selection; no external emulator intent.
- Folder import of CUE/BIN, including multi-track BINARY CUE layouts and optional same-basename SBI.
  Track names must be in the same folder; paths, unsupported directives and malformed SBI are rejected.
  A single CUE selected with Add game directs the user to Folder import so sibling permissions are explicit.
- Single-file CHD/PBP/ISO import and PS-X EXE diagnostic/homebrew import. Containers keep their extensions.
  Streaming is bounded to 2 GiB per complete bundle, with hash identity, cancellation and publish-last copying.
- First 128 KiB memory card per game, checkpointed through the existing safe save path. Second card disabled.
- Native save/load, frozen custom pause menu, ejection, and core-bound completed-session recovery.
- Original real annular GLB with reflective underside, 1.2 mm thickness, actual 15 mm hole and replaceable
  512×512 printed artwork. The same horizontal/vertical/grid renderer and label preview serve all systems.
- PlayStation title search in existing single/console/all/subset scraper workflows, with independent
  disc label/media/metadata review and offline accepted choices. No box-art substitution for disc labels.
- Console settings → Import PS1 BIOS. Accepts known 512 KiB retail dumps, keeps the original unchanged,
  and atomically replaces only the configured private BIOS. PS1 game settings identify HLE versus imported.
- Physical X/Y/L2/R2 now route into active gameplay alongside existing keys; UI routes remain separate.

## Use on RP5

1. B → Collection → Console settings (or Tools → Settings) → Import PS1 BIOS. Choose your supported dump.
   Eject before importing. Accepted families: SCPH-5500/5501/5502/1001/7001/101 and PSXONPSP660.
2. Start / Folder: choose a folder containing CUE files and their BIN tracks. Each CUE becomes one game;
   tracks do not become entries. Nested game folders are scanned. Keep referenced tracks beside their CUE.
   Put an optional LibCrypt `.sbi` beside the CUE with the same basename before importing.
3. Select the disc and A / Insert. L3+R3 pauses; Save state, Load state, Resume and Eject use Cartridge's menu.
4. Y → This title / Choose consoles → PlayStation to review scraped disc artwork and metadata.

Direct mappings follow Libretro: Android B = cross, A = circle, Y = square, X = triangle; L1/R1/L2/R2,
D-pad, Start and Select work. Retroid's Android button-layout mode and the app's swap preferences affect
physical positions. The first controller is digital; sticks act as D-pad. Android system buttons remain untouched.

## Validation

The connected RP5 is Android 13/API 33, arm64, Adreno 650, 1920×1080/60 Hz,
firmware `RP5_V1.0.0.189_20251128_174732_user`. No commercial games or BIOS are used by diagnostics.

The first native PS1 device suite passed 2 tests in 19.391 s: original PS-X EXE, ISO and CUE/BIN boot,
real PixelCopy colors changing on controller input, non-silent SPU PCM delivered to LibretroDroid,
program-originated memory-card sector writes through emulated BIOS, manual states, completed-state
restoration after Activity recreation, frozen pixels and repeated ejection/reinsertion. An additional
marker exercises the Libretro card ABI across native teardown. The earlier full regression passed
34 tests in 207.458 s. Final validation extends this with CHD/SBI, actual disc label pixels and
PS1 backup revision checks:

| Check | Actual result on 2026-10-10 |
| --- | --- |
| Both debug APKs and `:app:testDebugUnitTest` | Build passed; 48 host tests, zero failures/errors |
| `:app:lintDebug` | Zero errors, 26 warnings (dependency/API/style/arm64/backup-policy notices) |
| Focused `-Suite PS1` | 3/3 passed in 25.173 s |
| Full `-Suite All -Cycles 50` | 35/35 passed in 213.877 s, including 50 mGBA/Filament handoffs and eight 500-title recreations |
| Live provider probe | 1/1 passed in 4.695 s; sampled GBA/GB/GBC/PlayStation titles returned matching platforms and flat-label URLs, with no artwork download |
| `python tools/test_assets.py` | 12/12 passed |
| Official glTF validator | Four GLBs, zero errors/warnings |
| Disc regeneration | Byte-identical output from the versioned generator |
| APK contents | Both embedded core libraries and original disc GLB; no ROM/BIOS/probe content |

Native diagnostics boot EXE, ISO, uncompressed CHD v5 and CUE/BIN, assert real video pixels/input,
non-silent PCM delivery, game-originated memory-card writes, states, frozen recovery and ejection.
CUE import copies validated optional SBI, applies an original solid-color image to the rendered disc,
preserves it on rescan, avoids BIN entries and reports missing tracks without deleting accepted data.
Backup tests retain PS1 core revisions, reject an incompatible revision and preserve prior firmware
when invalid BIOS input is rejected. No synthetic backup payload is passed to an emulator.

An earlier diagnostic failed its expected boot color because the original MIPS probe treated the
initial zeroed PAD buffer as a held button. Corrected the program to require a valid digital-pad ID
before toggling. No emulator readiness criterion, color assertion or timeout was weakened.

The first disc-label pixel test sampled the preceding presented frame after GPU submission was
acknowledged. It now flushes Compose and waits up to eight seconds for the actual SurfaceView pixels,
matching the existing cartridge test. The unchanged color assertion passes in both focused/full runs.

Reproduce:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:GRADLE_USER_HOME=Join-Path (Get-Location) '.tools/gradle-home'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
python tools/test_assets.py
node tools/validate_assets.cjs
.\tools\Test-Device.ps1 -Serial <authorized-device> -Suite PS1
.\tools\Test-Device.ps1 -Serial <authorized-device> -Suite All -Cycles 50
.\tools\Test-Device.ps1 -Serial <authorized-device> -Suite Live
```

The helper generates original diagnostic EXE/ISO/CUE/BIN/CHD files under ignored `.tools/` and pushes
only those files. Debug tests use isolated databases and preserve the owner's collection/default HOME.
An existing exact source checkout can be supplied with `-PpcsxSource=C:/absolute/path/to/pinned-source`.
The normal build fetches and checks the pinned archive; no separately downloaded core binary is packaged.

## Changed code and records

- Core build/instrumentation: `app/src/main/cpp/pcsx.cmake`, `pcsx_frontend.c.in`, `CMakeLists.txt`,
  `save_bridge.c`, `app/build.gradle.kts`, `emulation/LibretroEngine.kt` and `SaveBridge.kt`.
- Identity/storage: `domain/GameSystem.kt`; `storage/Ps1Importer.kt`, `CueSheet.kt`, `SbiFile.kt`,
  `Ps1BiosStore.kt`, `GameFiles.kt`, `ManualState.kt`, `RomImporter.kt`, `DirectoryScanner.kt`,
  `SessionStore.kt`, `BackupStore.kt`; `library/RoomCollectionRepository.kt`.
- UI/input/scraping: `MainActivity.kt`, `environment/ConsolePanel.kt`, `input/ControllerRouter.kt`,
  `scraping/ScrapeCoordinator.kt` (existing provider parsing/review is reused).
- Disc: `assets/models/ps1_disc.glb`, `rendering/CollectionAssets.kt`, `CartridgeSurface.kt`,
  `tools/generate_disc.py`, `tools/test_assets.py` and `tools/validate_assets.cjs`.
- Tests: `Ps1StorageTest.kt`, `Ps1DeviceTest.kt`, `AdditionalSystemsTest.kt`, `ProviderLiveTest.kt`,
  debug fixture providers, `tools/probe/generate_ps1_probe.py` and `tools/Test-Device.ps1`.
- Records: README/gitignore, this report, product/architecture/roadmap/asset/testing/backup/license
  documents, ADR 0017 and five upstream dependency licence texts.

Kotlin paths above are relative to `app/src/main/java/dev/cartridge/`; tests are in their corresponding
`test` / `androidTest` source sets. No Room schema version change or existing core/dependency upgrade.

## Limits / next physical checks

No broad commercial-title, PAL protected-title, BIOS-backed audio/latency or long-duration performance
claim. The diagnostic uses HLE; importing a real BIOS still requires owner validation. PBP loading uses
upstream but has no container-specific device proof yet. Initial scope excluded M3U/disc swapping, added
on 2026-10-20 (ADR 0032, CONSOLES). It still excludes analog-required games, multitap, rumble, WAV CUE tracks and CUE references to other directories.
Multi-disc PBP is not a complete multi-disc gameplay workflow. CHD diagnostics do not establish every
codec/game combination. Different encodings/discs have different hashes. BIOS/grants/disc copies are
excluded from portable backup; reauthorize folders after transfer. Accepted saves/art remain independent.

Owner next: import the BIOS and one actual CUE/BIN game, confirm gameplay/speaker audio/all buttons,
save/load/eject, cold resume and scraping; then representative PAL/NTSC titles and longer profiling.
The original disc template/loading motion need visual review. Public licensing/source and provider
artwork rights gates remain as documented in LICENSING.
