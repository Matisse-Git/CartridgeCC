# Design system

Everything on screen is built from the tokens and components below. They live in
`app/src/main/java/dev/cartridge/ui/` (`Theme.kt`, `Components.kt`, `SystemStatus.kt`). Material 3
is present only as the theme host; no Material buttons, cards or dialogs are used.

Reference canvas: Retroid Pocket 5, 1920×1080 at 360 dpi = **853 × 480 dp**, landscape.

## Colour — `Ink`

| Token | Value | Use |
|---|---|---|
| `Stage` | `#161514` | The ground: page backgrounds; matches the 3D clear colour so pages and stage read as one material |
| `Surface` | `#1F1E1C` | Sheets, dialogs, keys, text fields |
| `Raised` | `#2B2926` | Focused row, toast |
| `Line` | white 10% | Hairlines, inactive switch track, progress track |
| `Text` | `#F2EEE6` | Titles, focused rows |
| `Soft` | `#B5AFA4` | Values, body copy, status |
| `Faint` | `#7F7A71` | Hints, captions, notes |
| `Warning` | `#E6B27A` | Low battery, unreachable folder, skipped files, save warnings |
| `Danger` | `#E79482` | Titles of actions that remove something from view |
| `Scrim` | `#100F0E` 70% | Behind sheets and dialogs that are not about the focused object |

One accent, chosen by the player (`Accent`): Sand `#D9C8A9` (default), Sage, Sky, Rose, Paper. The
accent marks focus and the current choice. It is never a background for text except the single
primary action on the welcome and the focused key.

No gradients as decoration, no glass, no coloured panels. The only gradient is `StageShade`, which
darkens the edges of the 3D stage.

## Type — `Type`

One family (the system sans). Five sizes; a sixth means the layout is wrong.

| Token | Size / line | Weight | Use |
|---|---|---|---|
| `Display` | 30 / 36 sp | Medium | Welcome headline only |
| `Title` | 22 / 28 sp | Medium | Shelf name, game title, page title |
| `Heading` | 16 / 22 sp | Medium | Sheet and dialog titles, key caps, text field |
| `Body` | 14 / 20 sp | Regular (Medium when focused) | Rows, values, copy |
| `Label` | 12 / 16 sp | Regular | Facts, notes, hints, status |
| `Caption` | 10.5 / 14 sp, +1.1 tracking, upper case | Medium | Section headings, glyph caps, "PAUSED" |

Text is drawn with `Txt`, which always ellipsises and takes an explicit line limit. Long titles
truncate; they never wrap into neighbouring content.

## Space — `Space`, `Radius`

4 dp grid.

| Token | dp | Use |
|---|---|---|
| `XS` 4 · `S` 8 · `M` 12 · `L` 20 · `XL` 32 | | Gaps: related things `S`, groups `L`, columns `XL` |
| `Edge` | 28 | Distance from the screen edge to any content |
| `Row` | 44 | Minimum row height; also the minimum touch height |
| `Radius.Row` 10 · `Radius.Sheet` 18 · `Radius.Chip` 6 | | Rows and fields; dialogs; thumbnails |

Widths: system menu 300, sheets 340, details and sheets beside the object 400, dialog 460, settings
section list 210, text entry 560 maximum.

Every screen pads for `WindowInsets.safeDrawing` before `Edge`.

## Elevation

There is none. Depth comes from three flat tones (`Stage` → `Surface` → `Raised`) and from the 3D
stage itself. No shadows are drawn by the interface.

## Focus

One treatment everywhere (`RowItem`):

- fill animates to `Raised`;
- a 3 × 18 dp accent mark appears at the leading edge;
- the title becomes full `Text` and Medium;
- a choice shows its value in the accent with ‹ › either side.

"Where you are" without focus — the current settings section while focus is among its rows — keeps
the mark and tints the title with the accent, without the fill.

Disabled rows stay focusable at 42% opacity, so the help line can say why.

An app tile in Apps (`AppsPage`) uses the same three signals turned to suit a tile: the `Raised`
fill behind the whole tile, the accent mark as an 18 × 3 dp bar at its lower edge, and the name in
full `Text` and Medium. Its icon is drawn at 56 dp when focused and nine-tenths of that otherwise.

On the stage, focus is physical: the focused object is centred (or enlarged, in the grid), nearer
and larger. In menus, focus never leaves the screen: `RowList` scrolls the focused row into view
with a margin, and reveals the heading above the first row.

## Components

| Component | Purpose |
|---|---|
| `Txt` | All text |
| `Glyph` | A controller button as a keycap: round for A B X Y, pill for the rest |
| `Hint`, `HintBar` | Button + meaning. A hint with an action is also the touch control for it |
| `RowItem` | The only row: command, link ›, switch, choice ‹ ›, information, heading |
| `RowList` | Scrolling rows that keep focus in view |
| `SidePanel` | Trailing sheet |
| `Scrim` | Dim behind a sheet or dialog; tapping it goes back |
| `ProgressLine` | 3 dp determinate or resting progress |
| `Notice` | Toast or persistent warning |
| `StatusCluster` | Time, battery level, battery mark with charging bolt |
| `StageShade` | Edge and band darkening over the 3D stage |
| `AppsPage` | The Android apps: title and count, status, a grid of real icons that divides its space into whole tiles (at least 124 × 108 dp; six columns and three rows on the RP5), hints and position |

Screens are assembled from these in `LibraryChrome.kt`, `Overlays.kt` and `AppsPage.kt`; the artwork workflow in
`scraping/ScrapePanel.kt` uses the same rows.

Rows are data (`MenuRow` in `shell/ShellModel.kt`): id, title, kind, value, note, enabled, action,
adjust. They are addressed by id, never by position.

## Glyphs

| Shown | Meaning |
|---|---|
| `A` `B` `X` `Y` | Face buttons. Confirm and back follow Settings › Controls › Confirm button |
| `L1 R1` | Shoulders |
| `START` | Start |
| `◀ ▶` `▲ ▼` `✥` | D-pad or stick: horizontal, vertical, both |

Glyphs are outlined keycaps in the hint colour: 18 dp tall, 1 dp stroke, `Caption` text. They are
labels, not buttons with their own colour.

Hint order is fixed: movement, confirm, secondary actions, back, menu. At most five hints on a page.
The library carries up to seven (Browse, Play, Details, Turn, Console, Apps, Menu), which fit the
RP5's width; they retire once learned, and `B Apps` retires on its own count.

## Icons

There are three drawn marks, all vector, all 1–1.5 dp stroke in `Soft`: the menu mark (two lines,
28 dp target), the battery (22 × 11 dp, with a bolt cut out when charging) and the shelf position
marks (4 dp dots; the current one a 14 dp bar in the accent). There is no icon set.

## Motion — `Motion`

| Spec | Value | Use |
|---|---|---|
| `focus` | spring, critically damped, stiffness 1400 | Row fill and mark, switch, shelf marks |
| `enter` | 220 ms, cubic-bezier(.2, 0, 0, 1) | Sheets and pages arriving (fade; sheets also travel 28 dp) |
| `exit` | 140 ms, same curve | Library chrome leaving |
| `fade` | 180 ms, same curve | Hints, shelf name cross-fade, notices |
| `res/anim/app_open` | `enter` as an Android transition: 220 ms, same curve, fade with a 96 % → 100 % scale | An Android app arriving from Apps. A cut under reduced motion. Never the insertion |

On the stage (`CartridgeSurface`, `FocusSpring`, `MediaMotion`):

| Motion | Value |
|---|---|
| Focus between objects | critically damped spring, ω = 14; retargets in place, never queues |
| Presentation (object steps aside) | same spring |
| Shelf arrival | starts 1.4 object-widths to the side, same spring |
| Insertion | 540 ms: lift, align, leave through the bottom of the screen |
| Ejection | 420 ms: the reverse, once the screen has opened on the library |
| The closed screen | closes in 260 ms after the object has gone, opens in 420 ms on a picture; Fade, Iris or Shutter, always black |
| Turning the object | right stick: 210°/s about its upright axis, tips to 55°; eases home 1.2 s after the stick rests |
| Key repeat | first step immediately, then 300 ms, then every 110 ms |

Rules: motion follows input and never delays it; nothing loops or idles; leaving is quicker than
arriving. **Reduced motion** replaces every spec with a cut and snaps the stage springs. The order
of operations — release one renderer before creating the next, wait for real frames — never
depends on an animation finishing.

## Writing

- Sentence case for rows and titles. Title Case only for the five named places: Add Games, Search,
  Artwork & Info, Refresh Library, Settings, and the pause actions.
- Say what happens, then what is kept: "Hide from library — Keeps the game file, saves and artwork".
- No implementation words in the main flow: no ROM, core, Libretro, HLE, scrape, hash, SAF, URI.
  "BIOS" is kept because it is the word on the file the player must find.
- Errors say what the player can do next. Storage failures are translated by `ConsoleShell.plain`.
- Numbers agree with their nouns (`plural`).

## Typeset labels

A game without artwork carries its own title, set in the system sans Medium on label stock
`#D3CDBF`, with the console's name in small capitals beneath a hairline (or under the hub, on a
disc). Sizes step down until the title fits in three lines (four on tall cartridges and discs).
Generated per visible object by `TypesetLabel`, bounded by the same window as decoded artwork, and
never written to storage.
