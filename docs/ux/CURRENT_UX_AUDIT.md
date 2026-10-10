# Current UX audit

Baseline: commit `d53f5fe` on branch `redesign/console-experience`, versionCode 17, Room schema 7.
Audited from source on 2026-10-10. Baseline results before any change: `:app:assembleDebug
:app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` pass, 48 unit tests, 0
failures. The baseline APKs are kept at `.tools/baseline-apk/` for rollback.

An earlier, unfinished attempt at this redesign was present as uncommitted work. It was set aside
with `git stash` (`redesign WIP superseded by from-scratch redo 2026-10-10`) and is not used.

## How the interface is built

- One Activity, `MainActivity` (731 lines), Jetpack Compose with Material 3 `TextButton`s. No
  navigation library; no ViewModel. Compose is drawn above a `SurfaceView`.
- `CollectionCoordinator` owns session state (`Library`, `LibraryMenu`, `Details`, `Inserting`,
  `Handoff`, `Emulator`, `Ejecting`, `LabelPreview`) **and** menu cursor positions (`menuIndex`).
- `ControllerRouter` turns key and axis events into one of five modes: Library, Gameplay, Menu,
  Text, Locked.
- Four parallel overlay systems decide what is on screen, each with its own integer focus:
  coordinator screens, `LibraryOverlay` (Manager/Search/Edit), `ConsolePage` (six pages) and the
  scraper's own page stack. `MainActivity` resolves them with nested `if/else if` chains in
  `navigate`, `select` and `back`.
- Every menu action is addressed by list position (`consoleAction(index)`,
  `libraryAction(index)`, `managerAction(index)`, `detailsAction(index)`), so inserting a row
  renumbers behaviour and tests.

## Screens and actions

| # | Destination | Reached by | Actions |
|---|---|---|---|
| 1 | Library | launch | browse, A insert, X add file, Y scrape, Select image, Start folder, R1 details, L1 favourite, B collection menu |
| 2 | Tools shelf | touch "Tools" | Add game, Folder, Scrape, Image, Reset label, Insert, Motion, Sound, Menu shortcut, Rescan, Collection, Details, Settings, Resume session |
| 3 | Collection menu | B | Back, Remove entry, Undo removal, Sort, Favourites filter, Reset filters, Folders & search, View, Console settings |
| 4 | Details | R1 | Favourite, Edit metadata, Scrape this title, Locate same ROM, Undo artwork, Back |
| 5 | Folders & search | 3 → row 7 | Back, Add folder, Rescan, Verify all, Auto-scan, Search, Clear search, Forget *n* |
| 6 | Search | 5 → row 6 | text field, Apply, Cancel |
| 7 | Metadata editor | 4 → row 2 | six text fields, Save, Cancel |
| 8 | First-run setup | empty library | Choose folder, Controller, Continue |
| 9 | Console settings | 3 → row 9, or shelf | 15 rows: Back, Controller, Game settings, Motion, Media sound, HOME candidate, Default home, First-run setup, About, Resume session, Discard session, Export backup, Restore backup, Android settings, Import PS1 BIOS |
| 10 | Controller | 9 → row 2 | Back, Menu A/B, Gameplay A/B, Gameplay L/R, Dead zone, Pause shortcut, Reset |
| 11 | Game settings | 9 → row 3, or pause | GBA: Audio smoothing, Frame skip. PS1: Import BIOS only |
| 12 | About & licences | 9 → row 9 | Back, Next licence |
| 13 | Restore review | 9 → row 13 | Merge, Restore settings toggle, Cancel |
| 14 | Pause | L3+R3 / Start+Select / Back | Resume, Save state, Load state, Eject, Game settings, Controller |
| 15 | Label preview | Select, or scraper | Sizing, Rotate, Apply, Cancel |
| 16 | Scraper | Y | 8 pages: Scope, Consoles, Targets, Preferences, Matches, Review, Artwork, Metadata |

Sixteen destinations, roughly ninety actions.

## Problems

### Too many controls on the home screen
The library reserves **nine** physical buttons for nine unrelated jobs, and prints seven of them
in a single 12sp hint line. Opening Tools adds a two-row strip of up to fourteen text buttons over
the 3D scene. Only three of the nine actions are things a player does every session.

### The same action lives in several places
| Action | Entry points |
|---|---|
| Add a folder | Start, Tools → Folder, Collection → Folders & search → Add folder, Setup → Choose game folder |
| Rescan | Tools → Rescan, Folders & search → Rescan |
| Motion on/off | Tools, Console settings |
| Media sound | Tools, Console settings |
| Pause shortcut | Tools, Controller |
| Game settings | Console settings, Pause |
| Controller | Console settings, Pause, Setup |
| Details | R1, Tools |
| Scrape | Y, Tools, Details |
| Import PS1 BIOS | Console settings (row 15), Game settings |
| Resume session | Tools, Console settings |

### Paths that are too long
- Change layout: B → down ×7 → A. It sits in a menu whose first real row removes a game.
- Search: B → Folders & search → down ×5 → A → type → Apply. Four levels.
- PS1 BIOS: B → down ×8 → A → down ×14 → A → picker. Last row of a fifteen-row list.
- Forget a folder: B → Folders & search → past seven rows.

### Confusing entry points
- **B opens a menu from the library.** Everywhere else B means back.
- **X adds a single file** — the most prominent face button after A, spent on the rarest import.
- **"Collection"** mixes destructive data actions (Remove), view preferences (Sort, View) and a
  door to app settings.
- **"Folders & search"** joins storage administration to finding a game.
- Search state persists invisibly; the only sign is a 12sp line under the title.
- "HOME candidate", "Verify all ROM files", "Frames skipped" and "HLE" are implementation words.

### Console organisation is missing
All consoles share one strip in import order. There is no console name on screen, no way to move
between consoles, and Favourites is a filter flag rather than a place.

### Onboarding does not onboard
The first screen is a settings-style panel with three rows and a paragraph. It offers Controller
configuration before any game exists. After the folder is chosen, progress is a single line of
text ("Scanning *name* · 3 added · 0 duplicates · 0 failed") and the result is a status string.
Console recognition is never mentioned. An empty library shows one cartridge carrying a
fictional "ORBIT FIELD" label that looks like a game but cannot be played.

### Settings are a flat index
Fifteen rows in one list, ordered by when each milestone landed. Presentation, hardware,
data-safety and one-shot actions are interleaved. Values are embedded in titles ("Motion: On"),
so there is no visual difference between a switch, a choice and a command. Nothing states scope:
"Game settings" silently means "the mGBA core, for every Game Boy family game".

### Visual language
- Raw `TextButton` rows with a flat `#34312D` fill for focus; no type scale — sizes 11, 12, 13,
  15, 16, 19, 20, 23, 24, 25 and 27sp all appear.
- Six separate hard-coded panel backgrounds; no shared spacing.
- Messages sit in an opaque box bottom-left until the next action replaces them, whether they are
  a confirmation or an error.
- No clock, no battery.
- During gameplay a permanent "Menu" text button sits over the top-right of the picture.
- Games without artwork all show the same fictional label.
- While a label decodes, every visible label briefly reverts to the fictional one.

### Missing states
No designed state for: a console with no games, a failed launch (a status string appears and the
user is back in the library), missing BIOS at the moment it matters, a folder whose permission
was lost, or the result of an import.

## What is sound and is kept

| Area | Kept as is |
|---|---|
| Emulation | `LibretroEngine`, `SaveBridge`, session mutex, readiness barriers, battery and state persistence, `SessionStore` |
| Rendering | `CartridgeSurface` ownership model, GLB assets, KTX lighting, nine-instance pools, label material contract, `MediaMotion` curves |
| Hand-off | `RendererHandoff`, viewport generations, exclusive surfaces |
| Data | Room schema 7 and every migration, SHA-256 identity, removed-games table, enrichment undo |
| Import | `RomImporter`, `Ps1Importer`, `CueSheet`, `DirectoryScanner` traversal and indexing rules |
| Scraping | `ScrapeCoordinator`, journal, packs, provider, preferences — logic untouched |
| Backup | `BackupStore`, `BackupPreferences`, review-then-merge |
| Input | `AxisRepeater`, `FocusSpring`, physical/gameplay separation, pause-shortcut detection |

## What is replaced

| Replaced | By |
|---|---|
| Tools shelf | Removed. Its actions move to the System Menu, Details or Settings — once each |
| Collection menu on B | Removed. Sort → Settings; Remove → Details; View → Settings › Appearance |
| `LibraryOverlay`, `ConsolePage`, per-menu integer focus | One destination stack with rows addressed by id |
| `menuIndex` in `CollectionCoordinator` | Named intents (`hideSelected`, `undoHide`, `chooseShelf`); the coordinator no longer knows about menus |
| `ConsolePanel`, `LibraryPanels` | Shared sheet, page and row components |
| Favourites filter flag | Favorites shelf (the stored flag is migrated once) |
| Status strings | Toasts, a warning banner, an import summary and a launch-recovery sheet |
| Fictional label on unlabelled games | A typeset label generated from the game's title |
| Setup panel | Welcome → folder → discovery → summary |

## Risks carried into the redesign

1. Button meanings change (X, Y, B, L1, R1, Start, Select). Existing muscle memory breaks once.
2. Thirty-five device tests address controls by tag and position; they must move with the UI.
3. The scraper's hidden-target preview depends on the coordinator projecting a game that the
   current view excludes. Console shelves add a second reason a game can be excluded.
4. Translucent overlays keep Filament rendering; only opaque pages may pause it.
5. A working copy of every game is stored privately (ADR 0003, ADR 0017). The interface must not
   claim otherwise.
6. `BackupPreferences` validates an exact key set. New preferences cannot join old backups.
