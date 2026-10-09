# Project Cartridge

Native Android, controller-first physical-media collection with **embedded** GBA emulation.
This repository implements M0, focused M1/M2 increments and active M3 library management, not the finished product. No RetroArch
installation, external emulator intents or browser renderer is used. No commercial ROM, BIOS or
proprietary artwork is bundled; users can scrape artwork into their private collection.

**Status:** M0 passed on RP5 on 2026-10-09. M1 has Room-persisted game slots/focus, real API label
scraping, independent media/metadata choices, 3D Fit/Crop/rotation preview and a tested session
coordinator. Folder imports now grow the collection and saved scrape batches resume after reopening.
Battery saves now restore before gameplay and checkpoint during play/pause. Both APKs build;
34 unit tests and eight asset checks pass. Twenty-one RP5 tests cover real native
handoffs, ordinary UI recovery, independent labels, schema migrations, folder imports and resumed scraping; a separate
live API test passes. The owner confirmed physical navigation, gameplay/audio, save/load and local labels.
M2 refinement is implemented: refined GBA mesh/PBR, original studio reflections, receiving slot,
phased insertion/ejection, optional contact sound and carousel reduced motion. A short isolated RP5
viewport benchmark measured 16.74–16.75 ms presentation p95 across rest/navigation/media motion.
Long-duration validation
remains open, alongside M1's sleep/reboot gates. See [docs/M2_PROGRESS.md](docs/M2_PROGRESS.md),
[docs/SAVE_RELIABILITY.md](docs/SAVE_RELIABILITY.md),
[docs/LIBRARY_PROGRESS.md](docs/LIBRARY_PROGRESS.md) and [docs/TESTING.md](docs/TESTING.md).

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

The empty collection shows one study cartridge and grows as games are imported. **Tools** opens the
touch shelf for imports/scraping, motion/sound/shortcut preferences and collection actions. It starts
closed; controller shortcuts work independently. At most three visible instances
reuse one GLB, regardless of collection size. Browse with D-pad left/right, left stick, or touch arrows.
Press X / Add .gba to add an uncompressed `.gba`
document or focus its existing entry, then A / Insert to play. The picker uses SAF and requests a persistable
read grant. A bounded, SHA-256-addressed working copy lives in app-private storage; the original
document is unchanged. Room preserves slot assignments, labels and the selected slot across relaunch.
Old M0 assignments were transient and must be selected once in this version. SaveRAM and one
save-state slot per content hash persist across sessions. Battery bytes restore before the first
emulated frame and checkpoint when changed every 15 seconds during play, on pause/state operations
and ejection. A save warning remains visible until a successful retry; sudden termination can still
lose changes since the latest completed checkpoint. Automatic full-state resume is not implemented.

Press **Start / Folder** to import a GBA directory and its subfolders through SAF. Read permissions and
multiple roots persist. New content hashes append; duplicates keep accepted
labels/metadata. **Rescan** revisits saved roots without adding duplicate games. **B / Cancel scan** keeps
completed imports. Legacy repeated assignments collapse on startup by hash; identical titles with
different ROM content stay separate. **B / Collection → Remove selected entry** hides the game while
keeping ROMs, saves and artwork. Rescan respects removal. **Undo last removal** restores its position
in the current Activity session; after reopening, **X / Add .gba** restores a hidden game and its data.
Only uncompressed GBA files are supported. Scans stop with an error at 10,000 entries or 16 levels;
invalid files are reported while valid siblings continue. Originals are never changed or deleted.

Press **Y / Scrape** for the main artwork workflow. Choose This title, GBA collection, All games,
Choose games, or Games missing labels. A toggles selected titles; X selects all/clears. Region & language
saves your preferred label/title region and description language. Preferences rank all alternatives;
they do not hide options or accept choices. GBA/all cover the same canonical collection until other
systems are supported, including games hidden by collection filters.

The current title is searched first. Choose a RetroBase match, preview its preferred flat label on the
real cartridge, then Fill missing metadata or Next. Existing label/metadata choices stay protected.
All label / media choices and Choose metadata fields / variants allow explicit replacements; box art,
screenshots and logos remain additional media. Undo last artwork / metadata change restores one
previous operation per game and survives reopening, including through Details. ROMs/saves/history
remain independent. Correct the title search or choose another match when needed; no account is required.

Cancel preserves accepted choices. Resume saved batch restores its review position and cached
completed searches; Next searches only the next pending title. Starting a new scope replaces saved
search progress, preserving accepted data. Unapplied previews/checkbox drafts are discarded on recreation.
A hidden target's preview temporarily shows it without changing your persisted filter/focus.

Scraping requires internet and sends title queries or ROM fingerprints/size, never ROM bytes.
Accepted images/metadata are cached for offline use; ordinary launch has no network dependency.
Data: [RetroBase Collection](https://retrobase-collection.com). Provider terms and coverage are documented
in [docs/PROVIDER_RESEARCH.md](docs/PROVIDER_RESEARCH.md); exact Socket artwork coverage is unverified.

Press Select / Image for optional local PNG, JPEG or WebP import, including an already scraped flat label.
Preview it on the actual cartridge, toggle Fit/Crop, rotate in 90° steps, then Apply or Cancel.
Use D-pad up/down and A in this panel; B cancels. Reset label restores the fictional study artwork.
Original images are cached unchanged; only accepted recipes are saved. Adjacent games retain their
own labels. Images are limited to 12 MiB and decoded off Main into 512×256 label textures.

L3 + R3 opens the app-owned pause menu; Start + Select is an alternate configurable combination.
Back also pauses. D-pad up/down selects menu actions; A activates and B resumes. Android gameplay
A/B go directly to the corresponding Libretro A/B; verify Retroid's selected button-layout mode.
The first button of a two-button shortcut may reach the game before the second is pressed.

Insertion lifts, aligns and slides the cartridge into a small physical receiving slot over 540 ms;
ejection reverses it over 420 ms. Motion off bypasses both and snaps carousel selection. Optional
original contact sound defaults off. Final manual art direction,
disc media, other layouts and HOME registration remain planned.
Backgrounding ends the current session after saving RAM; full session sleep/resume comes later.

## M3 library controls

R1 / Tools → Details opens optional metadata, playtime, last-played and save/source information.
L1 toggles favorite. B → Collection cycles import order, title, recent and most-played sorting,
filters favorites, or resets the view. Folders & search manages registered roots, title/developer/genre
search, fast rescans, full hash verification and automatic scanning on open (on by default).
An empty search has an explicit "No matching games" state; it does not hide data from all-library scraping.
Explicit Add clears active search/favorite filters so its existing/new identity is visible.

Details → Edit metadata uses ordinary text entry; only changed fields replace previous choices, with
local provenance. Clearing title falls back to the ROM filename. Details → Locate same ROM requires
identical bytes/hash and preserves labels/saves. Forgetting a folder unregisters scanning and releases
that root grant, keeping games and private cached files. Missing originals are reported after a
complete scan and cached games remain playable. Reauthorize a revoked root using Start / Folder.

Fast rescans reuse positive matching size/mtime stamps and a present working copy. Unknown stamps or
missing copies are reimported. Verify all rehashes every ROM, including same-size/time replacements.
Play history counts Running time, excludes pause/preparation, checkpoints every 15 seconds and flushes
on pause/eject/background. Failed history writes remain visible and retry for the correct game while
the Activity lives. Process death can lose history since the latest completed checkpoint.

See [M3 library progress](docs/M3_PROGRESS.md), [scraper review](docs/M3_SCRAPE_REVIEW.md) and ADRs 0009/0010.
M3 scrape-pack import is implemented. Large-library/grant/storage-failure validation and broader
sleep/reboot/performance gates remain open; the active roadmap continues through M4–M6.

## Engineering record

- [Technical plan and feasibility blockers](docs/TECHNICAL_PLAN.md)
- [Product and UX scope](docs/PRODUCT.md)
- [Label import, candidate choice and bulk scraping](docs/LABEL_SCRAPING.md)
- [API scraping implementation and limits](docs/SCRAPING_REPORT.md)
- [Folder imports, growing collection and saved scrape batches](docs/LIBRARY_PROGRESS.md)
- [Socket/Slot and provider research](docs/PROVIDER_RESEARCH.md)
- [Architecture and lifecycle](docs/ARCHITECTURE.md)
- [Milestones](docs/ROADMAP.md)
- [Asset pipeline](docs/ASSET_PIPELINE.md)
- [M2 refinement and physical presentation results](docs/M2_PROGRESS.md)
- [M3 library management and validation](docs/M3_PROGRESS.md)
- [M3 selected scraping, preferences, fast review and undo](docs/M3_SCRAPE_REVIEW.md)
- [Tests and actual results](docs/TESTING.md)
- [License audit and distribution obligations](docs/LICENSING.md)
- [M0 report and created files](docs/M0_REPORT.md)
- [M1 progress and changed files](docs/M1_PROGRESS.md)

LibretroDroid is GPL-3.0-or-later. Shipping this integration requires a GPL-compatible distribution
plan and complete corresponding source. See the audit before distributing; no project-wide
commercial licensing decision has been made by this feasibility work.
