# Redesign specification

Scope: the existing application at `d53f5fe` — embedded GBA, GB, GBC and PS1 play, the 3D media
collection, reviewed artwork, backups and HOME mode. This is a redesign of how those capabilities
are reached, not a new product. What is built is tracked in
[IMPLEMENTATION_STATUS](IMPLEMENTATION_STATUS.md); this document describes the design as built.

**Principle: the games are the interface.** The library is a shelf of physical objects. Everything
else is reached from it and returns to it.

## Information architecture

```
Library ─┬─ A        play (insertion → game)
         ├─ X        Details ─┬─ Game options (this game only)
         │                    └─ Manage game ── artwork, edit details, locate, hide
         ├─ L1 / R1  previous / next shelf
         ├─ Y        favourite
         ├─ B        Apps (only with nothing open; added 2026-10-11, ADR 0021)
         └─ START    System Menu ─┬─ Add Games
                                  ├─ Search
                                  ├─ Artwork & Info
                                  ├─ Refresh Library
                                  └─ Settings ── Library · Appearance · Gameplay · Controls · System · Advanced

Game ──── pause      Pause menu ── Resume · Save State · Load State · Game Options · Return to Library
```

Four destinations: Library, Details, System Menu, Pause. Every common action is at most two
levels from the library. Nothing appears in two places.

Apps, added on 2026-10-11, is a fifth: the library's sibling rather than something inside it. It is
a full screen on the same ground with the same title, status and hint positions, and it shows
Android apps as their own icons, never as media. It is reached by the one button that had no job at
the library's root, and it adds no button, tab or menu row. See [ADR 0021](../decisions/0021-android-apps-library.md).

| Old (16 destinations) | Now |
|---|---|
| Tools shelf, Collection menu | removed |
| Folders & search, Console settings, Controller, Game settings, First-run setup | one Settings page with six sections |
| Search panel, Metadata editor | one text-entry screen |
| Details (full screen) | sheet beside the object |

### Full screens and overlays

| Kind | Used for | Stage behind it |
|---|---|---|
| Sheet (trailing panel) | System Menu, Manage game, Game options, Label preview | visible; dimmed unless the sheet is about the focused object |
| Details sheet | focused game | visible, object presented at the left |
| Dialog (centred card) | import progress and result, launch recovery, folder actions | dimmed |
| Page (opaque) | Settings, text entry, hidden games, about, backup review, artwork | 3D rendering paused |

The stack is owned by one class, `ConsoleShell`. A destination already on top is never pushed
again. Entering a destination starts at its first row; returning from a child keeps your place.
Overlays are cleared whenever the scene changes (library ↔ game ↔ label preview), so nothing
opened in one can linger over another.

## Library

- **Top left** — current shelf name, a row of position marks (one per shelf), game count.
- **Top right** — real time, battery level and charging state, and a small menu mark for touch.
- **Centre** — the real 3D objects, unchanged geometry, materials and lighting.
- **Bottom** — focused title, one line of facts (console on unified shelves, year, developer,
  Favorite), hints, and position (`3 / 12`).

No toolbar, no panels, no permanent buttons. A soft shade darkens the edges so neighbours recede
and text always sits on quiet ground.

### Shelves

Shelves are views over one collection; they never change membership or stored order.

1. Each console that has at least one game, in a fixed console order.
2. **All Games** — only when there are two or more consoles.
3. **Favorites** — only when something is a favourite.
4. **Recently Played** — only when something has been played; most recent first, 24 at most.

Consoles without games do not exist. L1/R1 cycle and wrap. A shelf the player turns to always opens
on its first game, a unified shelf included (ADR 0020; the first design remembered a game per shelf,
which made a shelf open somewhere different each time). The last shelf and its focus are still
restored on launch. A new shelf's objects slide in from the side
it was reached from.

### Layouts

| Layout | Composition |
|---|---|
| Horizontal (default) | focused object centred, neighbours either side; text beneath |
| Vertical | objects stacked at the right; title, facts and hints beside them at the left |
| Grid | three columns, three full rows between the top bar and the title; the focused object is larger |

The focused game survives a layout change. Layout is changed in Settings › Appearance.

### Search

START › Search opens the text-entry screen. Results span every console and replace the shelf: the
header reads *Search* with the query and result count. B clears the search; L1/R1 leave it for a
shelf. No result is a stated condition with the way out printed beneath it.

## Details

X opens a sheet beside the focused object, which steps to the left and turns slightly to face the
viewer while its neighbours slide out of frame. Shown: title; console, year, developer; play time,
last played, last saved; description (four lines). Only fields that exist are shown — there is no
"Not set".

Actions: **Play**, **Add to / Remove from Favorites**, **Game options**, **Manage game**. If the
game's file can no longer be found, **Reconnect game file** appears first, in the warning colour.

**Manage game**: Find artwork online · Use my own image · Edit details · Undo last artwork change
(when there is one) · Remove label (when there is one) · Locate game file · Hide from library.
Hiding states what it keeps. A hidden game is restored from the System Menu for the rest of the
session, and from Settings › Library › Hidden games at any time.

## First run and adding games

An empty library *is* the welcome: the real object at the left, "Your games, beautifully
collected." and one action, **Add your games**, at the right. No account, no network, no
permission screen. Start still opens the menu.

1. A opens Android's folder picker. Any folder works: a parent of several consoles, one console,
   an SD card.
2. *Looking through …* — a names-only pass counts what is there.
3. *Found 18 games across 2 consoles* — per-console counts, a determinate bar, the game being
   added, and one sentence saying the originals are not touched and Cartridge keeps its own copy.
   **Stop** keeps whatever is already added.
4. Result. On first run: the headline, per-console counts, any problems in plain words, the
   three controls worth knowing, and **See my games**. Later: a clean import is a single
   passing line — "12 games added to PlayStation" — and the library is already showing them.

Detection: `.gba`, `.gb`, `.gbc` identify themselves (the importer still reads the header).
Disc containers (`.cue`, `.chd`, `.pbp`, `.iso`) are PlayStation unless they sit under a folder
named for another console, in which case they are counted and reported, not imported. `.bin`
tracks belong to their `.cue`. Files for other consoles, archives and `.m3u` playlists are counted
and explained. Duplicates are recognised by content and merged silently into the existing
console. Several folders can feed one console.

A PlayStation import without a BIOS file offers **Add PlayStation BIOS** on the result, because
that is the moment it matters.

## System Menu

A 300dp panel at the trailing edge. Rows: *Continue <game>* (only with a saved session), *Restore
hidden game* (only just after hiding one), Add Games, Search, Artwork & Info, Refresh Library
(only with folders), Settings. The subtitle counts games and consoles. Start or B closes it.

## Settings

One page. Sections at the left, that section's rows at the right, one help line beneath for the
focused row. Rows are a single line: a command, a switch, a choice with ‹ › or a link. L1/R1 move
between sections from anywhere. See [SETTINGS_STRUCTURE](SETTINGS_STRUCTURE.md).

## Pause

Over the real frozen frame, dimmed: *Paused*, the game, its console; Resume, Save State, Load
State (with when it was saved; unavailable until something is), Game Options, and — set apart —
Return to Library. Time and battery are shown here and never over a running game. B resumes.

Game Options lists the same options as Settings › Gameplay for this game's console, each marked
*Console setting* or *Only this game*, with **Use console settings** to drop every override.
Options the core can change while paused apply at once; the rest say "from its next start".

## Insertion and ejection

540 ms in, 420 ms out, readiness only after real core frames. A on a game is the insertion. Since
ADR 0020 every object leaves through the bottom of the screen (no receiving slot), the screen then
closes with the chosen transition and opens once the game shows a picture; no title or progress line
is shown, however long a game takes. Returning restores shelf, game and layout.

## Feedback

| Kind | Presentation |
|---|---|
| Confirmation ("State saved", "Added to Favorites") | a line at the top centre for four seconds |
| Save or history warning | the same line in the warning colour, until the cause is gone |
| Import result with something to read | dialog |
| A game that did not start | dialog: what happened, that saves are safe, Try again / Locate / Add BIOS |
| Folder that cannot be read | marked in Settings › Library with "Choose this folder again" |

Raw storage errors are translated (`ConsoleShell.plain`): lost permission, unreachable storage,
no space, truncated file.

## States

| State | What the player sees |
|---|---|
| New installation / no games | Welcome on the real object; A adds games |
| One console, one game | That console's shelf; no shelf marks, no L1/R1 hint |
| Several consoles | Shelf marks; L1/R1; All Games appears |
| Large library | Same composition; only the visible window owns GPU objects and labels |
| Search with no result | Stated, with B to clear |
| No artwork | Title set in type on label stock, on the real object |
| No internet | Nothing changes; artwork search reports its own failure when asked |
| Scanning on open | "Looking for new games · B Stop" at the top; a notice only if something was added |
| Importing | Dialog with counts and progress |
| New console found | Its shelf exists when the import ends, and is shown |
| Duplicate games | Counted as "already in your library" |
| Unsupported console / archives / playlists | Counted and named on the result |
| Ambiguous disc image | Imported as PlayStation unless its folder names another console |
| Missing game file, removed SD card, lost permission | Launch recovery dialog; details show Reconnect |
| Missing BIOS | Offered on import and on a failed PlayStation launch; status in Settings › Gameplay |
| Emulator failed to start | Launch recovery dialog; library usable, entry untouched |
| Loading | Nothing if fast; title and line if slow |
| Playing | The game only |
| Paused | Pause menu on the frozen frame |
| Ejecting | Reverse motion; same shelf and game |
| Low battery | Battery mark and level in the warning colour at 15% or less when not charging |
| Returning from background | Existing checkpoint restore, paused |
| Reduced motion | Every transition is a cut; the stage snaps; ordering is unchanged |

## Controller

| Button | Library | Menus and sheets | Text entry | Game |
|---|---|---|---|---|
| D-pad / stick | browse | move focus; left/right change a value | move across keys | game |
| A | play | select | type | game |
| B | clear search | back | cancel | game |
| X | details | — | delete | game |
| Y | favourite | favourite (in Details) | space | game |
| L1 / R1 | shelf | settings section | — | game |
| START | menu | close menu | done | game |
| L3+R3 (or Start+Select) | — | — | — | pause |

A and B swap everywhere outside the game with Settings › Controls › Confirm button; every hint
follows. Hold-to-repeat is unchanged (first step at once, 300 ms, then 110 ms). The router
reports buttons by role and `ConsoleShell` assigns the meaning, so a button cannot mean two
things on one screen. Input is locked while an operation runs and cannot queue a second one.

### Touch

Everything works without a controller. Edges of the library browse, the object plays, the title
opens details, the top-right mark opens the menu, rows are tappable, and every *B Back* hint is
itself the back control. In a game, the top-right corner or Android's Back opens the pause menu;
nothing is drawn over the picture.

## 3D presentation

Assets, materials, lighting and the bounded instance pools are unchanged. Added to the renderer:

- **Presentation** — the focused object moves to the left of the stage for Details, its sheets,
  the label preview and the welcome; neighbours leave along the line away from it.
- **Arrival** — a newly chosen shelf slides in from the side it was reached from.
- **Layout metrics** — Horizontal spacing tightened so neighbours are mostly in frame; discs
  reduced so they clear the title; Grid and Vertical recomposed as above.
- **Label depth bias** — labels are biased toward the eye, fixing a pre-existing defect where the
  Game Boy template's label lay exactly on its recess floor.
- **Typeset labels** — a game without artwork gets its title on label stock, generated per
  visible object and never stored.

## Capability limits (not offered in the interface)

Rewind, cheats, achievements, analog or
DualShock pads, per-button remapping, gameplay touch controls, themes other than dark, and UI
sounds beyond insert/eject. Each waits on the emulator adapter, not on the interface.
