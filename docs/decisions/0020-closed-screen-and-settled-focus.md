# ADR 0020: A closed screen around the game, settled focus writes, and the right stick

Date: 2026-10-10. Status: accepted by the owner's direction. Amends ADR 0018 (where a shelf opens)
and ADR 0019 (the receiving slot).

## Context

After a day with the redesigned console on a real library the owner reported: browsing felt like
30 frames a second; a console's shelf sometimes opened on its second game; cartridges did not need
a receiving slot; a focused menu row sometimes had no highlight; and starting a game went black,
flashed white, then showed the game.

Measured on the RP5, not assumed:

- **The lag was not the models.** With the imported models on screen the GPU sat at 54 % busy at its
  lowest clock (305 of 670 MHz), about a quarter of what it can do, and a 40-game shelf held 60 fps
  with the D-pad held. With 800 games the same test dropped to 54.7 fps with 29 doubled frames in
  six seconds. Every browse step wrote the selection to the database; that write invalidated the
  observed library query, and the whole library was read and its JSON parsed again on the main
  thread. The cost grew with the library, one stall per step.
- **The white flash is the core's own first frames.** A Game Boy Advance shows white until the game
  enables its display, and the stage was shown as soon as the core reported a frame.
- **The missing highlight was a colour.** The focus fill is an opaque dark grey chosen for dark
  panels. Over a paused game's picture it could match what was behind it, and a row that could not
  be used dimmed its fill with its text.

## Decision

**Settled focus writes.** `CollectionCoordinator` writes the selection 400 ms after browsing stops,
and before any operation that reloads the library or leaves the foreground. `RoomCollectionRepository`
maps rows to games off the main thread and does not announce a write that only moved the selection.
`LabelStore` and `TypesetLabel` copy pixels in one call, decode no larger than a label can show, and
the Activity decodes up to three labels at a time.

**The closed screen.** `CollectionState.curtain` is true while the stage changes hands. Starting a
game: the object leaves, the screen closes (`MediaMotion.CURTAIN_MS`), only then is the library
surface released, the core prepared, and the screen stays closed until
`RendererHandoff.awaitPicture()` returns — the middle of the emulator's presented frame is no longer
flat white or flat black, or two seconds have passed. Leaving a game: the screen closes over it, the
emulator is released, and `rendererReady` for the rebuilt library opens it; the ejection is played
then, not blind behind it. No title, spinner or other loading text is shown for any console. Input
stays locked while the screen is closed.

**Three ways to close it.** `LaunchTransition` is Fade, Iris or Shutter, always black, chosen under
Settings › Appearance › Game start and drawn by `ui/Curtain`. Reduced motion closes and opens it as
a cut.

**No receiving slot.** Cartridges leave through the bottom of the screen as discs do. The pose
descends 2.13 world units so the tallest object is wholly out of view before the screen closes. The
slot's model and generators are removed.

**A shelf opens on its first game.** Turning to a shelf with L1/R1 or its selector always focuses
index 0. Reopening the console still returns to the shelf and game it was closed on, and changing
layout still keeps the focused game.

**The right stick turns the focused object.** `ControllerRouter` reports `AXIS_Z`/`AXIS_RZ` outside
gameplay; `CartridgeSurface.spin` turns the object about its upright axis for as long as the stick
is held and tips it up to 55°. It eases home 1.2 s after the stick rests and returns at once when the
focus moves or a game starts. It applies on the shelf and beside the object's details only.

**Focus is always visible.** A row that cannot be used dims its words, not its fill or mark, and the
pause menu's side of the frozen frame is close to solid.

## Consequences

- Starting a game takes `CURTAIN_MS` longer when it starts quickly, and up to two seconds longer
  when a game genuinely opens on a blank screen. A game that stays black longer than that is shown
  black.
- The stored selection can be up to 400 ms behind the screen. The shelf and focus restored on the
  next launch come from preferences written on every step, so nothing visible depends on it.
- A component that writes only the selection must not expect `snapshots` to announce it.
- The models were left as converted. Reducing their triangles or textures would not have changed
  the frame rate; the measurements above are the reason.
- Settings › Advanced › Show frame rate prints what the library stage drew in the last second, so
  the owner can check a real library, which no automated test here opens.
