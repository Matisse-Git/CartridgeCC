# Project Cartridge

Native Android, controller-first physical-media collection with **embedded** emulation: Game Boy, Game Boy
Color, Game Boy Advance, PlayStation, Super Nintendo, NES, Genesis, Master System, Game Gear, Dreamcast,
GameCube and, once you have added the console's BIOS, PlayStation 2; Saturn is implemented and waiting for
its BIOS. [docs/CONSOLES](docs/CONSOLES.md) says exactly what each one does, what was run on the device and what
was not.
This repository now contains the development implementation through M6: shared 3D collection layouts,
API label/metadata review, internal GBA/GB/GBC/PS1 play, console setup/settings, optional Android HOME,
completed-session recovery and portable backup/restore. It remains a private development build;
art direction, broad game/peripheral compatibility and public-release rights/source obligations are open.
No RetroArch installation, external emulator intents or browser renderer is used to play a game.
Commercial ROMs, BIOS and proprietary artwork are excluded from Git/APK assets.

**PlayStation 2 on the console's own BIOS (2026-10-13, [ADR 0026](docs/decisions/0026-playstation-2-on-the-console-bios.md)):**
PlayStation 2 now runs on ARMSX2, the open-source PCSX2 for ARM64, built into the app from pinned source in
place of Play!. It needs the console's BIOS, which only you can supply: add the 4 MiB file once under
Settings › Gameplay › PlayStation 2 (any region's; the app recognises it by its contents and never
downloads one). On the RP5, with a BIOS of the owner's, **both of the owner's games now run at full speed
with whole sound**, the one that had run at 85 % and the one that had never shown a picture, and both
survive the device sleeping. Still true: no save states on this console (a game saves to its memory card;
the two cards are shared by all PlayStation 2 games), and nothing but those two games has been tried. The
same work found that closing the app with a PlayStation 2 or GameCube game still loaded crashed it ten
seconds later; that is fixed and tested.

**Real games on Dreamcast, PlayStation 2 and GameCube; a cartridge for each console (2026-10-12,
[ADR 0024](docs/decisions/0024-what-real-games-showed.md), [ADR 0025](docs/decisions/0025-a-cartridge-per-console-and-market.md)):**
the owner's own games were run on the three engines below and showed what the test programs could not: a
black screen on PlayStation 2, sound at twice its speed on Dreamcast, a corrupted picture on GameCube. All
three were this host's doing and are fixed. On the RP5 the GameCube game tried now runs at 60 pictures a
second with whole sound; three Dreamcast discs run at their own speed with whole sound; one PlayStation 2
game plays from its opening film into its first scene (at about 85 % speed there) and **a second one never
shows a picture, which is Play!'s own limit** *(both run since the paragraph above)*. PlayStation 2 lost its
save states and, with GameCube, now keeps its game loaded and paused while the device sleeps. NES, Super
Nintendo and Genesis games are shown as their own cartridges, from models the owner supplied; a Super
Nintendo game named as a North American release has that market's shell.

**Dreamcast, PlayStation 2, GameCube (2026-10-11, [ADR 0023](docs/decisions/0023-cores-that-draw-for-themselves.md)).**
*Written before any game had run; the paragraph above is what games then showed.*
Flycast 2.7, Play! and Dolphin are built into the app from pinned source and draw with the device's own
OpenGL ES. None needs a BIOS to start. Each was run on the RP5 from an original test program (picture; for
Dreamcast and GameCube also buttons and the stick), and the whole device regression passes with them in.
**No commercial game has been run on any of the three**, nothing 3D has been drawn and no speed was measured:
add a game and see. Limits to know: a DVD is copied into the app, so it needs its size again; PlayStation 2
runs on Play!, which plays part of the library; GameCube has no save states; triggers are buttons; memory
cards are not in backups yet.

**More consoles (2026-10-11, [ADR 0022](docs/decisions/0022-console-registry-and-shared-core-shim.md)):**
a console is now one definition in a registry instead of special cases across the app. Five consoles were
added on it and verified on the RP5 with original test programs: Super Nintendo (Snes9x 1.63), NES (FCEUmm),
Genesis, Master System and Game Gear (Genesis Plus GX). Saturn (Beetle Saturn) is built in with its BIOS
workflow; its core was run on the device with an original stand-in image, and a game needs your BIOS. Every new console was shown as a disc
until its own model existed (NES, Super Nintendo and Genesis have theirs since 2026-10-12). Game Boy, Game Boy Advance and PlayStation behave as before.

**Android apps (2026-10-11, [ADR 0021](docs/decisions/0021-android-apps-library.md)):** pressing B on
the game library, with nothing else open, opens the apps installed on the device as a grid of their
own icons; A starts the focused one as a home screen would, and B returns to the same shelf and
game. Y searches. The list follows installs and removals without being stored. Nothing else about B
changed: it still closes whatever is open, and in a game it is the game's.

**PS1 increment (v17):** source-built embedded PCSX ReARMed, CUE/BIN folder imports, optional SBI,
CHD/PBP/ISO files, real 3D discs, per-game memory card, pause/save/load/eject and session recovery.
Add your BIOS under Settings › Gameplay › PlayStation, then add your CUE/BIN folder with START › Add Games.
Initial PS1 controller is digital; M3U/disc swapping and analog-required games are deferred.
See [PS1 progress, exact validation and remaining checks](docs/PS1_PROGRESS.md).

**Media models and browsing (2026-10-10; four more cartridges 2026-10-12):** the objects games are
shown as are converted from models the owner downloaded, with the app's own replaceable label in place of
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

**Verified, console increment (2026-10-11):** both APKs, 85 host tests, lint and asset checks pass.
On the RP5 the full regression passed 49 of 49 in 435 s with Super Nintendo, NES, Genesis, Master
System and Game Gear each played from an original test program (picture, input, battery memory,
state, restart). The Saturn test and one later addition to the console test were written after the
device was disconnected and have not been run; [TESTING](docs/TESTING.md) lists exactly which.

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
and verifies its distribution SHA-256. CMake fetches **hash-pinned source** for every core (mGBA
0.10.5, PCSX ReARMed c8816799, Snes9x 1.63, FCEUmm 7a542dab, Genesis Plus GX 393f70cd, Beetle Saturn
ec20ea05), builds arm64 Libretro cores and packages one library each. There are no separately
downloaded core binaries. An existing exact checkout can be supplied instead: `-PmgbaSource=`,
`-PpcsxSource=`, `-PsnesSource=`, `-PnesSource=`, `-PgenesisSource=`, `-PsaturnSource=` with an
absolute path. Pins and hashes are in ADR 0017 and ADR 0022.

The first native build is slow: Beetle Saturn's generated 68000 interpreter alone takes about sixteen
minutes to compile on the development machine. Later builds reuse it.

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
| B | Back. From the library it clears a search; with nothing to close it opens **Apps** |

Touch works throughout: screen edges browse, the object plays, the title opens details, the mark at
the top right opens the menu, and every *B Back* hint is itself the back control.

**Apps** (B from the library). The Android apps installed on the device, by name, each with its own
icon. Android's own Back still does nothing at the library's root, so only the controller's button
leads here.

| Button | Does |
|---|---|
| D-pad / left stick | Move across the grid |
| A | Open the focused app. Come back with Android's Back or Home; Apps is where you left it |
| Y | Search by name. B clears the search |
| B | Back to the library, on the same shelf and game |

Cartridge itself is not listed. An app that has been removed or turned off says so when you try to
open it, and the list is read again. Cartridge only lists apps that have a launcher entry and asks
for no broad permission to do it.

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

| Console | Files |
|---|---|
| Game Boy, Color, Advance | `.gb` `.gbc` `.gba` |
| Super Nintendo | `.sfc` `.smc` `.swc` `.fig` |
| NES | `.nes` `.unf` `.unif` |
| Genesis | `.md` `.gen` `.smd`, and `.bin` inside a folder named for Genesis or Mega Drive |
| Master System, Game Gear | `.sms`, `.gg` |
| PlayStation | CUE/BIN folders; single CHD, PBP, ISO and PS-X EXE files |
| Saturn | CUE/BIN folders; CHD inside a folder named for Saturn |
| Dreamcast | GDI folders; `.cdi`; GD-ROM `.chd`; CUE/BIN and CHD inside a folder named for Dreamcast |
| PlayStation 2 | DVD `.iso` and `.chd`; `.iso`, CUE/BIN, `.cso`, `.isz` inside a folder named `ps2` or PlayStation 2 |
| GameCube | `.iso` `.gcm` `.rvz` `.gcz`; `.ciso` inside a folder named for GameCube |

A file is checked by its first bytes before it is copied, so a text file called `.md` or a Windows
`.exe` beside your games is passed over quietly. Whatever a CUE or GDI names is that disc's track and
never a separate entry, `.bin`, `.raw` or `.iso` alike; import a sheet through a folder so its tracks
can be read. A CUE is identified by
its own first sector: a Saturn or Dreamcast disc is its console's wherever it sits, and a Sega CD
disc is refused by name. A GameCube disc carries Nintendo's marker, a DVD-sized `.iso` is PlayStation
2's, and a `.chd` says whether it holds a DVD or a GD-ROM; a Wii disc is refused by name. Other disc
images go to the console their folder is named for, and to PlayStation when nothing says otherwise. A
disc in a folder named for a console Cartridge cannot play (`wii`, `psp`, `segacd`, …) is counted
and reported, not imported. Files for other consoles,
`.zip`/`.7z` archives and `.m3u` playlists are counted and explained on the result. Multi-disc games
are not supported: each disc is its own entry.

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
PlayStation game fails to start. Saturn games cannot start without one: Settings › Gameplay › Saturn
has a row for the American/European file (`mpr-17933.bin`) and one for the Japanese file
(`sega_101.bin`), and starting a Saturn game without either explains this instead of failing.
Cartridge accepts known dumps by content, keeps them private, and never downloads or backs them up.
Every console's controller is digital.

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
- [Consoles: what plays, what is defined where, what blocks the rest](docs/CONSOLES.md)
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
