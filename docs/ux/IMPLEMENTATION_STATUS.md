# Implementation status

**Addendum, 2026-10-13.** PlayStation 2 now needs the console's BIOS (ADR 0026), so it is the
second console, after Saturn, whose games open the setup dialog instead of starting until the file
is added: one row, **Add BIOS file**, on the import result, the game's details, the dialog and
Settings › Gameplay › PlayStation 2. No new component or destination; `Ps2CoreTest` checks the
first, third and fourth of those on the RP5 ([TESTING](../TESTING.md)), not the details row;
journey J in [USER_FLOWS](USER_FLOWS.md) describes it, and no screenshot was added. Closing the app with a game still loaded now releases the game before the
interface is taken down, which nothing on screen shows.

**Addendum, 2026-10-11.** The Android apps library (ADR 0021) was added after this status was
written: B from the library opens Apps, a fifth destination. Its verification is the first entry of
[TESTING](../TESTING.md); its flow is journey I in [USER_FLOWS](USER_FLOWS.md); screenshots 25 and 26
were added and the library and welcome captures were taken again for the `B Apps` hint. Everything
below describes the redesign as it stood on 2026-10-10.

Branch `redesign/console-experience`, on top of `d53f5fe`. Session date 2026-10-10. Changes are in
the working tree and **not committed**. The earlier, unfinished attempt at this redesign is kept in
`git stash` ("redesign WIP superseded by from-scratch redo 2026-10-10") and is not used.

No Room migration (schema stays 7). No asset, core, save-format or dependency change.
`versionCode` is unchanged at 17.

## Verified

Build host: Windows 11, JDK 21.0.8, Gradle 8.13. Device: Retroid Pocket 5, Android 13, firmware
`RP5_V1.0.0.189_20251128_174732_user`, 1920×1080 at 360 dpi.

| Check | Baseline `d53f5fe` | This build |
|---|---|---|
| `:app:assembleDebug :app:assembleDebugAndroidTest` | pass | pass |
| `:app:testDebugUnitTest` | 48 pass | **57 pass**, 0 fail |
| `:app:lintDebug` | pass, 27 warnings | pass, **24 warnings** (none in new files) |
| `python tools/test_assets.py` | 12 pass | 12 pass |
| RP5 instrumentation | 35 recorded in TESTING.md | **37 pass**, 0 fail, 262 s |

The 37 device tests are the 35 existing ones, adapted to reach controls through the new interface,
plus `GameOptionsDeviceTest` and `UxTourTest`. Debug APK SHA-256 begins `6f18c04142618cb6`.

Render pacing on the RP5 (`RenderPacingTest`, real presentation timestamps, original labels;
measured on the build immediately before the last one-line dialog change, renderer identical):

| Phase | Frames | Median | p95 | Gaps |
|---|---|---|---|---|
| At rest | 540 | 16.66 ms | 16.73 ms | 0 |
| Rapid navigation | 1141 | 16.66 ms | 16.76 ms | 0 |
| Insertion / ejection | 300 | 16.67 ms | 16.76 ms | 0 |
| Vertical navigation | 420 | 16.66 ms | 16.78 ms | 0 |
| Nine-object grid | 540 | 16.66 ms | 16.77 ms | 0 |

CPU submission p95 2.47 ms; 3242 of 3242 frames submitted. This matches the 16.74–16.75 ms recorded
before the redesign. It measures the 3D stage, not the Compose layer above it.

### What the device tests prove

| Area | Test | Evidence |
|---|---|---|
| GBA play, input, save, load, eject | `SurfaceHandoffTest`, `CollectionUiTest`, `BatterySaveTest` | 50 native hand-offs; battery save restored before boot; state save/load; background and recreation |
| GB / GBC play, battery, clock | `AdditionalSystemsTest` | Real frames, input, SRAM and RTC bytes |
| PS1 play, memory card, states, CUE+BIN+SBI | `Ps1DeviceTest` | Real PCSX frames, card bytes, one entry per CUE, label on the real disc |
| Options reach the core | `GameOptionsDeviceTest`, `ConsoleEnvironmentTest` | Values read back from the running PCSX and mGBA cores and the emulator view; live per-game override |
| Welcome, import, shelves, details, menus, settings, search, layouts, recovery, pause | `UxTourTest` | Controller-driven; 26 screenshots of the real display |
| Controller-only editing and search | `LibraryManagementTest` | Title and year typed on the key grid; favourites shelf; search; history |
| Layout change keeps the game | `CollectionLayoutDeviceTest` | Start → Settings → Appearance by D-pad; 500-title window stays bounded |
| Import, duplicates, hide, restore | `LibraryGrowthTest` | 5 added, 2 known, 1 unreadable; hidden game survives refresh and returns with label and save |
| Labels on the real object | `CollectionUiTest`, `RenderRefinementTest`, `Ps1DeviceTest` | Pixel colours sampled from the 3D surface, including the staged label preview |
| Session recovery | `SessionRecoveryTest` | Sleep/wake, cold launch, corrupt checkpoint rejected, discard from Settings |
| Backup | `BackupDeviceTest` | Export, review, cancel, merge with settings through the new Settings rows |
| Artwork workflow | `ScrapeUiTest`, `ScrapeReviewTest`, `ScrapeResumeTest`, `ScrapePackTest`, `ProviderContractTest` | Unchanged coordinator behind restyled rows |
| Rendering rate | `RenderPacingTest` | Table above |

### Seen, not just compiled

Screenshots in [screenshots/](screenshots) are the device's own display capture, 3D surface
included, from `UxTourTest` on the final build, reduced to 960×540. They were captured again on
2026-10-10 after the media models changed (ADR 0019), so the cartridges and discs shown are the
imported ones; the interface is as it was. On 2026-10-12 four were added and two captured again
for the cartridges of ADR 0025: `04b` (Super Nintendo: both shells side by side, the North American
one for the game named `(USA)`), `04c` (NES), `04d` (Genesis), `17c` (all of them in the grid),
and `05` and `17b` because the fixture library grew. Every title, label and game in
them is an original fixture in an isolated database. Three rounds of defects were found by looking
at them and fixed (a fourth, on the new cartridges, is in ASSET_PIPELINE: it was found in renders
before the device):

1. The object was cut by the screen edge beside its sheet; discs overlapped the title; menu focus
   was remembered across openings; the current settings section was unmarked.
2. The label preview sheet covered the label; the vertical layout ran under the hints; three grid
   rows collided with the header and the title.
3. Game Boy labels were corrupted: in the tall template the label lies exactly on its recess
   floor. This predates the redesign and affected any label on that template. Fixed with a depth
   bias on label materials; the asset is untouched.

### Two failures that were not regressions

On the first full run two `Ps1DeviceTest` cases failed. The same two fail identically with the
`d53f5fe` build on the same device: a real BIOS had been added to the app's `system/` folder at
12:59, after the last recorded regression, and those original diagnostics are written against the
built-in BIOS. `DebugCollectionActivity` now gives test sessions their own firmware folder, so the
diagnostics no longer depend on what the owner has imported. The owner's BIOS file was not
touched (same size and timestamp before and after).

## Built

| Phase | State |
|---|---|
| 0 Audit and baseline | Done — [CURRENT_UX_AUDIT](CURRENT_UX_AUDIT.md) |
| 1 Research and architecture | Done — [FRONTEND_RESEARCH](FRONTEND_RESEARCH.md), [REDESIGN_SPEC](REDESIGN_SPEC.md), [DESIGN_SYSTEM](DESIGN_SYSTEM.md), [SETTINGS_STRUCTURE](SETTINGS_STRUCTURE.md), [USER_FLOWS](USER_FLOWS.md), ADR 0018 |
| 2 Library | Done — shelves, L1/R1, real status, hints, typeset labels, three recomposed layouts |
| 3 Onboarding and importing | Done — welcome, discovery pass, per-console result, console detection by folder, recovery copy |
| 4 Settings and details | Done — system menu, six-section settings, console and per-game options, details and manage sheets |
| 5 Polish | Partly — motion system, presentation and arrival on the stage, notices, launch recovery. Open items below |
| 6 Verification | Done for this build — table above |

### Acceptance criteria

| # | Criterion | State |
|---|---|---|
| 1 | Opens into an uncluttered library | Met |
| 2 | 3D cartridge and disc models retained | Met; assets byte-identical |
| 3 | Console libraries separated | Met |
| 4 | Console switching smooth and controller-friendly | Met |
| 5 | Select and launch directly | Met |
| 6 | GBA and PS1 emulation work internally | Met; device tests |
| 7 | Guided first run | Met |
| 8 | Adding a folder is simple | Met: START, A, choose |
| 9 | New consoles need no configuration | Met for the four supported consoles |
| 10 | Existing consoles accept more games without duplication | Met |
| 11 | PS1 disc structures handled | Met for CUE+BIN, CHD, PBP, ISO. Multi-disc is not supported and the interface says so |
| 12 | Real time and battery | Met; broadcast-driven |
| 13 | Settings comprehensive, organised, unobtrusive | Met for what the build can do; see "Not offered" in SETTINGS_STRUCTURE |
| 14 | Usable entirely with a controller | Met, including text entry |
| 15 | Navigation depth and clutter reduced | Met: 16 destinations → 4; nine library shortcuts → six with one meaning each |
| 16 | Library, metadata and saves survive the upgrade | Met by construction (no migration) and by the migration and backup tests. The owner's own library was not opened by any test |
| 17 | Animation and rendering performant | Met on the stage; see limits |
| 18 | Consistent from startup to gameplay and back | Met |
| 19 | No working capability lost | Met; see the mapping in SETTINGS_STRUCTURE. One removal: the legacy "assign a file to this slot" path, superseded by adding |
| 20 | Looks finished | Judged from device screenshots; needs the owner's eye in the hand |

## Changed files

New: `domain/ConsoleCatalog.kt`, `environment/GameOptions.kt`, `library/Shelf.kt`,
`shell/ConsoleShell.kt`, `shell/ShellModel.kt`, `shell/ShellPages.kt`, `storage/TypesetLabel.kt`,
`ui/Theme.kt`, `ui/Components.kt`, `ui/SystemStatus.kt`, `ui/LibraryChrome.kt`, `ui/Overlays.kt`;
tests `ConsoleExperienceTest`, `GameOptionsDeviceTest`, `UxTourTest`; ADR 0018; this folder.

Rewritten: `MainActivity.kt` (731 → 371 lines), `session/CollectionCoordinator.kt`,
`input/ControllerRouter.kt`, `scraping/ScrapePanel.kt`, `androidTest/ToolShelf.kt`.

Extended: `storage/DirectoryScanner.kt` (discovery pass, per-console tallies, folder hints),
`rendering/CartridgeSurface.kt` (presentation, arrival, layout metrics),
`rendering/CartridgeLabels.kt` (depth bias), `emulation/LibretroEngine.kt` (per-session options,
shader, injectable firmware folder), `library/CollectionRepository.kt`,
`library/LibraryDatabase.kt`, `library/RoomCollectionRepository.kt` (read-only hidden-games query),
`debug/DebugCollectionActivity.kt`, `tools/Test-Device.ps1` (suites `Options`, `Tour`).

Removed: `environment/ConsolePanel.kt`, `library/LibraryPanels.kt`.

Adapted tests: `CoordinatorTest` and eleven device suites. Their assertions about emulation,
saves, storage and rendering are unchanged; only how they reach a control changed, plus two
intended behaviour changes (the stored favourites filter becomes the Favorites shelf; scan results
are read from the import summary instead of a status string).

## Not done, and what it needs

| Item | Needs |
|---|---|
| Multi-disc games, `.m3u`, in-game disc change | Importer and adapter work; the wrapper already exposes disk control |
| Analog / DualShock on PlayStation, D-pad, stick and per-game remapping, touch gameplay controls | Adapter and input work (each console's game buttons are remappable since ADR 0030) |
| Rewind, fast-forward, aspect and integer scaling | Adapter work (the wrapper exposes frame speed and viewport) |
| Light theme, background styles, display scale, animation speed | Design and implementation; deliberately not faked |
| Interface sounds beyond insert/eject | Original audio assets |
| Automatic artwork on import | Decided and built in ADR 0029 (automatic apply); see the next row for what is still unchecked |
| Cases on the owner's library (ADR 0029, merged 2026-10-18) | Unchecked: live ScreenScraper with the owner's login, how real `box-texture` art lines up on each case (the jewel case's back/spine split above all), and the owner's games shown in their cases. The owner runs *Scrape all* on the main library |
| Quiet scan on open locks library input while it runs, and re-hashes CUE tracks | Making scans non-blocking changes session serialization (ADR 0018, "Not decided here") |
| Every game is copied into private storage | A storage-design change (ADR 0003, 0017). The interface states it and shows the cost |
| Exit animations for sheets | Enter is animated; leaving is a cut |
| Localisation; TalkBack pass | Strings are in code, English only |
| `tools/Test-Device.ps1` under Windows PowerShell 5.1 | It stops at the first `adb push` because stderr progress is treated as an error; this session ran the same instrumentation through adb directly |

## Needs the owner's hands

1. Open the real library: confirm shelves, focus restored per console, labels, and that nothing is
   missing. No test opens it.
2. Physical controller feel: shoulder switching, hold-to-repeat in menus and on the key grid, the
   confirm/back swap.
3. Compose-layer smoothness while browsing with the chrome visible — the pacing test measures the
   stage only.
4. Screen effects (Handheld LCD, Television) and Sharper 3D on real games: the values are proven to
   reach the view and the core; how they look and perform is a judgement.
5. A real PlayStation game with the BIOS now on the device, start to save to return.
6. Idle behaviour: the library now lets the screen sleep on Android's timeout.
7. HOME mode from Settings › System, and an SD-card folder removed and reinserted.
8. Low-battery colour at 15% and the charging mark.

## Recommended next

Commit this work in reviewable pieces (foundations; shell and UI; tests; docs), then take the
first two rows of "Not done": multi-disc import with in-game disc change, since the wrapper already
supports it and it is the largest gap in PlayStation library behaviour.
