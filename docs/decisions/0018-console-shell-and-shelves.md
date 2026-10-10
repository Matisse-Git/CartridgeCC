# ADR 0018 — console shell, shelves and scoped game options

Date: 2026-10-10. Status: accepted for the console-experience redesign.

## Context

After six milestones the interface had sixteen destinations and about ninety actions, decided by
four independent overlay systems (coordinator screens, library overlays, console pages, the
scraper), each with its own integer cursor and actions addressed by list position. See
[CURRENT_UX_AUDIT](../ux/CURRENT_UX_AUDIT.md). The session coordinator also held menu cursors.

## Decision

**One navigation owner.** `shell/ConsoleShell` holds a stack of `Destination`s and resolves every
controller role. `ControllerRouter` reports buttons by role (select, back, X, Y, shoulders, Start,
left/right) and no longer knows what a role does. Rows are data (`MenuRow`) addressed by id and
built from current state by `ShellPages`; nothing stores a cursor index. A destination already on
top is not pushed twice. Overlays are cleared whenever the scene changes between library, game and
label preview.

**The coordinator owns sessions and the library projection, not menus.** `LibraryMenu`, `Details`
and `menuIndex` are removed from `CollectionCoordinator`. Positional actions became named intents:
`hideSelected`, `undoHide`, `restoreHidden`, `chooseShelf`, `switchShelf`, `reveal`. `menuAction`
(0 resume, 1 save, 2 load, 3 eject) and `labelAction` (0–3) keep their positions as a documented
contract. A failed launch additionally publishes `LaunchFailure` for a recovery sheet.

**Shelves are projections.** `library/Shelf` (each console with games, All Games, Favorites,
Recently Played) filters the canonical snapshot inside the coordinator, alongside the existing
`LibraryQuery`. Shelves never write membership or order. Per-shelf focus and the last shelf are
presentation preferences, not Room data. The stored `favoritesOnly` flag is moved to the Favorites
shelf once at start and cleared, so no view can stay filtered without a visible control. A search
spans every console and bypasses the shelf; leaving it clears the stored search. The scrape
preview of a game outside the current view keeps its transient-projection rule.

**Options have scope.** `environment/GameOptions` lists the options the pinned build honours, per
core family. `OptionStore` resolves default → console → game. `LibretroEngine` receives the result
per session (`sessionOptions(GameRequest)`), with one reserved key for the frontend shader.
Mandatory PlayStation variables (BIOS selection, memory cards) are applied after user options and
cannot be displaced. `audioFilter` and `frameSkip` keep their names and types because backups
validate an exact key set; new keys are not added to backups.

**Imports explain themselves.** `domain/ConsoleCatalog` classifies files by extension and uses a
folder's name only to exclude disc images that belong to another console. `DirectoryScanner` gains
a names-only `discover` pass and per-console tallies in `ScanResult`; traversal, hashing, indexing
and the incomplete-scan rule are unchanged.

**The renderer gains presentation, not assets.** `CartridgeSurface.presentation` moves the focused
object aside for sheets about it; `arriveFrom` slides a new shelf in; grid, vertical and disc
metrics are recomposed; label material instances get a polygon offset. GLBs, KTX lighting, pool
sizes and the label material contract are untouched. Games without artwork get a `TypesetLabel`
generated per visible object and never stored.

## Consequences

- No Room migration; schema stays at 7. Preferences only gain keys.
- Button meanings in the library change once (X details, Y favourite, L1/R1 shelf, Start menu, B
  back). There is no legacy mapping switch.
- Device tests reach controls through `ToolShelf.tool`, which walks the production interface; row
  tags are stable ids rather than positions.
- The idle library no longer holds the screen awake; scans and gameplay do.
- Translucent sheets keep Filament rendering; opaque pages pause it. A test that observes the
  stage must be in the library.
- Still true and stated in the interface: every game has a private working copy (ADR 0003, 0017).

## Not decided here

Multi-disc playlists, analog pads, rewind and fast-forward remain adapter work. The quiet scan on
open still locks library input while it runs, and still re-hashes CUE tracks; making it
non-blocking is a session-serialization change, not an interface one.
