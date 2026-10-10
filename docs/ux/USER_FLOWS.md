# User flows

The eight journeys from the redesign brief, as built. Each lists what the player does, what they
see, how many presses it takes, and the device test that walks it on the Retroid Pocket 5. Fixture
games in tests are original diagnostics in an isolated database; no test opens the owner's library.

Results for the current build are in [IMPLEMENTATION_STATUS](IMPLEMENTATION_STATUS.md).

## A — First launch

| Step | Player | Sees |
|---|---|---|
| 1 | Opens Cartridge with no games | The real cartridge at the left; "Your games, beautifully collected."; **Ⓐ Add your games** |
| 2 | A | Android's folder picker |
| 3 | Chooses a folder | *Looking through <folder>* → *Found 8 games across 3 consoles*, per-console counts, progress |
| 4 | — | *8 games added across 3 consoles*; problems in plain words; A Play · L1 R1 Change console · START Menu |
| 5 | A (**See my games**) | The first console's shelf, focused on the first new game |
| 6 | A | The game |

Three presses and one folder choice from a blank device to a running game. Nothing asks about
consoles, emulators, file types or permissions.

Verified by `UxTourTest` (welcome, import, result, landing shelf) and `ConsoleEnvironmentTest`
(welcome present with no games, gone with one).

## B — Add more Game Boy Advance games

| Step | Player | Sees |
|---|---|---|
| 1 | START | Menu, focus on **Add Games** |
| 2 | A | Folder picker |
| 3 | Chooses a folder | Progress dialog |
| 4 | — | Back in the library on the first new game, with "5 games added to Game Boy Advance" at the top for four seconds |

If something needs reading — a file that could not be added, files for another console — the
result stays as a dialog instead of a passing line.

The new games join the existing Game Boy Advance shelf. Games already present are recognised by
content and counted as "already in your library"; their artwork, details, favourites, play time
and saves are untouched. A second folder for the same console is simply another row under
Settings › Library › Game folders.

Verified by `LibraryGrowthTest`: 5 added, 2 already present, 1 unreadable; a refresh adds nothing;
a hidden game stays hidden through a refresh and returns with its label and save.

## C — Add PlayStation for the first time

| Step | Player | Sees |
|---|---|---|
| 1 | START › A | Folder picker |
| 2 | Chooses the PlayStation folder | *Found 1 game* … |
| 3 | — | Result. Without a BIOS: **Add PlayStation BIOS** is the first row |
| 4 | A | The PlayStation shelf now exists and is showing; the game is a disc |

A `.cue` and its `.bin` tracks are one game: tracks are never listed, and the importer copies and
re-hashes them as a bundle. The disc is the original 3D disc with its own label area and no
cartridge slot in the insertion.

Not supported, and said so on the result when encountered: `.m3u` playlists and multi-disc sets.
Each disc is added as its own game.

Verified by `Ps1DeviceTest.cueFolderCopiesTracksPlaysAndRescansWithoutDuplicateTrackEntries`
(one entry from CUE + BIN + SBI, label on the real disc, plays, refresh adds nothing, a missing
track is reported) and `ConsoleExperienceTest` (classification).

## D — Browse and play

| Step | Player | Sees |
|---|---|---|
| 1 | R1 | Next shelf slides in; its name and count replace the last; its own remembered game is focused |
| 2 | ◀ ▶ | Objects glide; title and facts follow |
| 3 | A | The object lifts, aligns and inserts; the game starts |

No details page, no Play button, no emulator choice, no loading screen unless the first frame is
slow.

Verified by `UxTourTest`, `CoordinatorTest.consoleShelvesKeepTheirOwnFocusAndNeverRewriteStoredOrder`
and every suite that starts a game with the library's own touch target.

## E — Pause, save, resume

| Step | Player | Sees |
|---|---|---|
| 1 | L3 + R3 | The game frozen and dimmed; *Paused*, title, console; focus on **Resume** |
| 2 | ▼ A | "State saved"; Load State now reads *Saved just now* |
| 3 | B | The game, running |

Return to Library is the last row, set apart. Load State is unavailable until a state exists.
Saving writes through the same serialized path as before; the battery save is checkpointed too.

Verified by `CollectionUiTest`, `AdditionalSystemsTest`, `BatterySaveTest`, `Ps1DeviceTest` and
`UxTourTest`.

## F — Change the layout

| Step | Player | Sees |
|---|---|---|
| 1 | START | Menu |
| 2 | ▼ to **Settings**, A | Settings, Library section |
| 3 | ▼ A | Appearance; focus on **Layout** |
| 4 | ▶ | *Vertical* — applied at once |
| 5 | B B B | The library in the new layout, on the same game |

Verified by `CollectionLayoutDeviceTest` with controller events only: the focused game is the
same after Horizontal → Vertical → Grid and after the Activity is recreated.

## G — Repair a missing game

When the play copy and the original are both unreachable, starting the game shows:

> **Glass Meridian didn't start**
> Cartridge can't reach this game's file. If it is on an SD card, check the card is inserted, or
> choose the file again.
> Your saves and artwork are safe.
> Try again · Locate game file · Back to library

**Locate game file** opens the document picker and accepts only the same content; the entry keeps
its label, details, play time and saves. The same action is always available under Details ›
Manage game, and Details shows **Reconnect game file** first when the copy is known to be missing.

A folder that can no longer be read is marked in Settings › Library › Game folders with
**Choose this folder again**. Its games stay in the library; nothing is removed because storage
went away.

Verified by `UxTourTest` (recovery dialog, entry and focus preserved),
`CoordinatorTest.failedLaunchOffersRecovery…`, and
`LibraryManagementTest.versionFourMigrationIncrementalVerificationAndMissingRecoveryKeepEnrichment`
(records, metadata and favourites survive a missing source; an incomplete scan never marks a game
missing).

## H — Find a PlayStation video setting

| Step | Player | Sees |
|---|---|---|
| 1 | START › Settings | Settings |
| 2 | R1 R1 (or ▼ ▼ A) | Gameplay: a **Game Boy · Color · Advance** group and a **PlayStation** group |
| 3 | ▼ to **Sharper 3D** | Help line: "PlayStation: Draws 3D at twice the original resolution. Demanding games may slow down. Applies from a game's next start." |
| 4 | ▶ | *On* |

The setting lives under its console's heading and its help line starts with the console's name.
There is no global video switch to confuse it with. To change it for one game only: X on the game ›
Game options, where each row says *Console setting* or *Only this game*.

Verified by `GameOptionsDeviceTest`: the values are read back from the running PCSX core
(`pcsx_rearmed_neon_enhancement_enable`, `pcsx_rearmed_dithering`) and the emulator view's shader;
a per-game override changes the paused picture at once and leaves the console value alone; the
memory-card and BIOS variables cannot be displaced.

## Other paths

| Goal | Path | Presses |
|---|---|---|
| Favourite the focused game | Y | 1 |
| Find a game | START ▼ A, type, START | 3 + typing |
| Hide a game | X › Manage game › Hide from library | 4 |
| Restore it | START › Restore hidden game (same session) or Settings › Library › Hidden games | 2 / 4 |
| Artwork for this game | X › Manage game › Find artwork online | 4 |
| Artwork for many games | START › Artwork & Info | 2 |
| Per-game setting while playing | pause › Game Options | 2 |
| Swap confirm and back | START › Settings › Controls › Confirm button | 5 |
| Back up | START › Settings › Library › Back up collection | 4 |
| Continue a saved session | START › Continue <game> (first row) | 2 |
