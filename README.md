# Project Cartridge

Native Android, controller-first physical-media collection with **embedded** GBA, GB, GBC and PS1 emulation.
This repository now contains the development implementation through M6: shared 3D collection layouts,
API label/metadata review, internal GBA/GB/GBC/PS1 play, console setup/settings, optional Android HOME,
completed-session recovery and portable backup/restore. It remains a private development build;
art direction, broad game/peripheral compatibility and public-release rights/source obligations are open.
No RetroArch installation, external emulator intents or browser renderer is used. Commercial ROMs,
BIOS and proprietary artwork are excluded from Git/APK assets.

**PS1 increment (v17):** source-built embedded PCSX ReARMed, CUE/BIN folder imports, optional SBI,
CHD/PBP/ISO files, real 3D discs, per-game memory card, pause/save/load/eject and session recovery.
Add your BIOS under Settings › Gameplay › PlayStation, then add your CUE/BIN folder with START › Add Games.
Initial PS1 controller is digital; M3U/disc swapping and analog-required games are deferred.
See [PS1 progress, exact validation and remaining checks](docs/PS1_PROGRESS.md).

**Media models and browsing (2026-10-10):** the cartridge, the tall cartridge and the disc are
converted from three models the owner downloaded, with the app's own replaceable label in place of
the game label each download had baked in. A game keeps its 3D object while it is near the screen,
so browsing a shelf of more than three games no longer re-labels what is in view. See
[ADR 0019](docs/decisions/0019-imported-media-and-stable-pool.md); results are in
[TESTING](docs/TESTING.md).

**Console feel (2026-10-10, [ADR 0020](docs/decisions/0020-closed-screen-and-settled-focus.md)):**
browsing no longer re-reads the library on every step, which was what made a large collection feel
like 30 frames a second; games start and end behind a closed screen with three transitions to choose
from; cartridges no longer use a receiving slot; a shelf opens on its first game; the right stick
turns the focused object; a focused row is always visibly focused. Settings › Advanced › Show frame
rate prints the library's real frame rate beside the clock.

**Verified:** M0 passed on the physical RP5. The owner confirmed physical controller navigation,
gameplay/audio, pause/eject, save/load, label thumbnails/apply/persistence and imports. Both APKs,
48 host tests, lint, 12 asset checks and four GLB validators pass. The final PS1-inclusive regression passed
35 RP5 tests with 50 native handoffs and eight 500-title recreations. PS1 video/input/PCM/card/state,
disc-label pixels, CUE/SBI import and backup checks pass using original diagnostics. Actual M6 sleep/wake, process-death,
reboot recovery and the ten-minute sustained diagnostic pass. Their exact scope and results are recorded
in [M6_PROGRESS](docs/M6_PROGRESS.md) and [TESTING](docs/TESTING.md).
The live GBA/GB/GBC/PlayStation API probe passes. Owner BIOS-backed PS1 games, speaker output and
longer title-specific performance remain physical checks in [PS1_PROGRESS](docs/PS1_PROGRESS.md).

See [ROADMAP](docs/ROADMAP.md) for exact scope and remaining manual gates. Collection presentation
p95 measured 16.74–16.75 ms on RP5 across three layouts; this is a bounded viewport measurement,
not a claim about every library/game or long-term battery use.

## Prerequisites

- Windows, Linux, or macOS with JDK 17 or 21 (validated here with Microsoft OpenJDK 21.0.8).
- Android SDK: platform 35, build tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1,
  and platform-tools. Android Studio can install these, or use `sdkmanager` below.
- First build requires access to Google Maven, Maven Central, JitPack, and GitHub codeload.
  After dependencies and the source archive are cached, the application runs offline.
- Physical arm64 Android device with OpenGL ES 3.0; RP5's Android 13 is the target.
- Python 3.10+ is optional for the lighting and sound generators and for structural
  validation. Blender 4.2+ is needed only to re-convert the media models
  ([ASSET_PIPELINE](docs/ASSET_PIPELINE.md)); an ordinary build uses the packaged GLBs.

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
and verifies its distribution SHA-256. CMake fetches **hash-pinned mGBA 0.10.5 and PCSX ReARMed
c8816799 source**, builds arm64 Libretro cores and packages `libmgba_libretro.so` and
`libpcsx_rearmed_libretro.so`. There are no separately downloaded core binaries. Existing exact
source checkouts can be supplied with `"-PmgbaSource=C:/absolute/path/to/mgba-0.10.5"` and
`"-PpcsxSource=C:/absolute/path/to/pinned-pcsx"`. See ADR 0017 for the full source pin/hash.

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

## Using the console

The interface was redesigned on 2026-10-10 (branch `redesign/console-experience`). The
design, its research and its evidence are in [docs/ux](docs/ux/REDESIGN_SPEC.md); screenshots from
the Retroid Pocket 5 are in [docs/ux/screenshots](docs/ux/screenshots).

**First run.** An empty library is the welcome: press A, choose the folder that holds your games —
a parent of several console folders, one console's folder, or an SD card. Cartridge reports what it
found per console, adds it, and opens the first console's shelf. The picker is Android's own (SAF)
and the read grant is persisted; no broad storage permission is requested.

**Library.**

| Button | Does |
|---|---|
| D-pad / left stick | Browse the shelf |
| Right stick | Turn the focused object to look at it; it settles back when you let go |
| A | Play: the object inserts and the game starts |
| X | Details, beside the object |
| Y | Favourite |
| L1 / R1 | Previous / next shelf: each console with games, then All Games, Favorites, Recently Played. A shelf always opens on its first game |
| START | Menu: Add Games, Search, Artwork & Info, Refresh Library, Settings |
| B | Back; from the library it clears a search |

Touch works throughout: screen edges browse, the object plays, the title opens details, the mark at
the top right opens the menu, and every *B Back* hint is itself the back control.

**Details** (X): play time, last played and saved, description, then Play, Favorite, Game options
(this game only) and Manage game — find artwork online, use your own image, edit details, undo the
last artwork change, remove the label, locate the game file, hide from library. Hiding keeps the
file, saves and artwork; restore from the menu in the same session or from Settings › Library ›
Hidden games.

**In a game.** L3 + R3 pauses (Start + Select is the alternative, Settings › Controls). Android's
Back and the top-right corner also pause. The pause menu offers Resume, Save State, Load State, Game
Options and Return to Library. Nothing is drawn over a running game. The first button of a
two-button shortcut may reach the game before the second is pressed.

**Settings** (START › Settings): Library, Appearance, Gameplay, Controls, System, Advanced. Layout
(Horizontal, Vertical, Grid) is under Appearance. Gameplay options are grouped by console; a game
can override them from its details or pause menu. See
[SETTINGS_STRUCTURE](docs/ux/SETTINGS_STRUCTURE.md).

### What importing does

Cartridges support `.gba`, `.gb` and `.gbc`. PlayStation supports CUE/BIN folders and single
CHD, PBP, ISO and PS-X EXE files. BIN tracks belong to their CUE and never become separate
entries; import a CUE through a folder so its tracks can be read. A disc image in a folder named
for another console (`ps2`, `saturn`, `dreamcast`, …) is counted and reported, not imported. Files
for other consoles, `.zip`/`.7z` archives and `.m3u` playlists are counted and explained on the
result. Multi-disc games are not supported: each disc is its own entry.

Each game gets a bounded, SHA-256-addressed working copy in app-private storage (ADR 0003, 0017);
the original document is never changed. The interface says so during import, and Settings ›
System › Storage used shows the cost. New content hashes append; duplicates are recognised by
content and keep their labels, details and saves. Identical titles with different content stay
separate. Scans stop with an error at 10,000 entries or 16 levels; invalid files are reported while
valid siblings continue.

Remembered folders are refreshed quietly when Cartridge opens (Settings › Library). Fast refreshes
reuse positive matching size/mtime stamps and a present working copy; unknown stamps or missing
copies are reimported; CUE tracks are always rehashed. Settings › Advanced › Check every game file
rehashes everything. Stopping a scan keeps completed imports. Missing originals are reported after
a complete scan and cached games remain playable. Removing a folder (Settings › Library › the
folder › Stop using this folder) releases its grant and keeps its games.

PlayStation games without a BIOS use HLE, which runs only some of them. Add your own BIOS under
Settings › Gameplay › PlayStation › BIOS file; it is also offered on the import result and when a
PlayStation game fails to start. The controller is digital.

### Saves and history

SaveRAM and one save-state slot per content hash persist across sessions. Battery bytes restore
before the first emulated frame and checkpoint when changed every 15 seconds during play, on
pause/state operations and ejection. A save warning stays visible until a successful retry; sudden
termination can still lose changes since the latest completed checkpoint. Automatic session
checkpoints are separate from manual states: wake restores the game paused, and a cold launch
offers **Continue <game>** as the first row of the menu. Play history counts Running time only.

**Settings › Gameplay › Start from save state** (off by default) starts every game from its last
Save State instead of from the beginning; a game can be set differently from its own Game options.
A game without a state starts as usual. If a state cannot be loaded the game still starts, from the
beginning, and says so; the state file is left alone. In-game saves are put back after the state
loads, so turning this on never rolls a battery or memory-card save back by itself. The state is
the one made with Save State in the pause menu: it is as old as the last time you made one.

### Artwork

START › Artwork & Info is the reviewed workflow: This game, Choose consoles, All games, Choose
games, Games without a label, a saved batch, Region & language, and Import an artwork pack. For
one game, use Details › Manage game › Find artwork online. Choose a match, try its flat label on
the real object, then fill in missing details or move on. Existing label and detail choices stay
protected; replacements are explicit. Undo restores one previous artwork operation per game and
survives reopening. Scraping needs internet and sends title queries or ROM fingerprints and size,
never ROM bytes. Accepted images and details are cached for offline use.
Data: [RetroBase Collection](https://retrobase-collection.com). Provider terms and coverage are in
[docs/PROVIDER_RESEARCH.md](docs/PROVIDER_RESEARCH.md).

A game without artwork shows its own title set in type on the real cartridge or disc. Your own
PNG, JPEG or WebP (12 MiB at most) can be used from Manage game; preview it on the object, choose
whole image or fill, rotate, then use or cancel.

### Motion and sound

Starting a game lifts the cartridge or disc, turns it to face you and lets it leave through the
bottom of the screen over 540 ms. The screen then closes to black, and opens again only when the
game has a picture, so neither the hand-off nor a console's blank first frames are seen; no loading
text is shown. Leaving a game closes the screen the same way and opens it on the library, where the
object rises back over 420 ms. Settings › Appearance › Game start chooses how the screen closes:
Fade, Iris or Shutter. Settings › Appearance › Motion › Reduced replaces every transition with a cut. The contact sound is off by default
(Settings › Appearance › Sound). Backgrounding saves a completed state and releases the session.

## Engineering record

- [Console experience: research, audit, specification, flows, settings, design system, status](docs/ux/IMPLEMENTATION_STATUS.md)
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
- [M4 shared collection views](docs/M4_PROGRESS.md)
- [Tests and actual results](docs/TESTING.md)
- [License audit and distribution obligations](docs/LICENSING.md)
- [M0 report and created files](docs/M0_REPORT.md)
- [M1 progress and changed files](docs/M1_PROGRESS.md)

LibretroDroid is GPL-3.0-or-later. Shipping this integration requires a GPL-compatible distribution
plan and complete corresponding source. See the audit before distributing; no project-wide
commercial licensing decision has been made by this feasibility work.

M5 GB/GBC implementation and verification are documented in [M5_PROGRESS](docs/M5_PROGRESS.md) and ADR 0013.

M6 version 13 added first-run setup, console settings, separate menu/gameplay controller choices, supported core options and optional Android HOME eligibility; the console-experience redesign moved them into START › Settings and replaced the setup panel with the welcome. HOME is initially disabled; Android asks you to choose the default. See [M6_PROGRESS](docs/M6_PROGRESS.md).

Version 14 adds checksum/core-bound session recovery and a real frozen pause frame; see M6_PROGRESS and ADR 0015.

Version 15 adds portable backup/restore (now Settings › Library › Backup). See [BACKUP_FORMAT](docs/BACKUP_FORMAT.md) for contents, review and transfer behavior.

The development milestone summary and major changed file groups are in [MILESTONE_REPORT](docs/MILESTONE_REPORT.md). Reproducible sleep/process/reboot/soak commands are in [CONSOLE_RELIABILITY](docs/CONSOLE_RELIABILITY.md).
