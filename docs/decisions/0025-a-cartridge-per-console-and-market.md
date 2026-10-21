# ADR 0025: A cartridge per console, and per market where they differed

Date: 2026-10-12. Status: accepted by the owner's direction ("use those for the systems that aren't
just CDs"); extends ADR 0019 and replaces the note in ADR 0022 that new consoles are shown as discs.

## Context

Every console after PlayStation was shown as the compact disc, by the owner's instruction, until
their own models arrived. They have: downloads for the NES, the Super Nintendo twice (the square
North American shell and the rounded one Japan and the PAL countries had), the Genesis and the
Nintendo 64 are in `assets/models`.

Three things in the app assumed there would only ever be three objects:

- `CartridgeSurface` had three loaders, three pools and three label binders by name, and a `when`
  for each object's on-screen scale;
- `Media` carried a label height and a noun, and the asset file names lived in `CollectionAssets`;
- the object was a property of the console alone, so one console could not have two shapes.

And the four downloads are less alike than the first three were (ASSET_PIPELINE has each in
detail): a modelled shell with the label recess as geometry, a CAD conversion with an automatic
unwrap and a convex front, a photographic scan with the label spread over several islands of its
atlas, and a 104-triangle game asset sharing a 256 px atlas with three other cartridges.

## Decision

**A template is one entry of `Media`.** `Media(labelHeight, noun, model, scale)`: the packaged file
and the factor that brings its real size to the size the shelf shows every object at are data.
`CollectionAssets` reads one file per entry and `CartridgeSurface` builds one `Template` per entry
(mesh, loader, pool of `CollectionLayout.POOL` copies, label binder), in enum order. Adding an
object is an entry and a GLB; the renderer has no object's name in it.

The pool is still bounded and still independent of the library: 7 templates × 15 copies = 105
instances where there were 45. A template's copies share its mesh and textures; what grows with
the count is entities and transforms, and the GPU memory of four more texture sets.

**The object follows the console, and for one console the name of the game.** `GameSystem.media` is
the console's own shape. `GameSystem.northAmerican` names the other one where a console's cartridges
differed by market, which is only the Super Nintendo, and `GameSystem.media(title)` chooses it for
a dump named as a North American release: `(USA)`, `(USA, Europe)`, `(U)`, `[U]`, `(JU)` and the
like. Any other name, and a name with no tag at all, has the console's own shape, which for the
Super Nintendo is the rounded Japanese and PAL shell. `LibraryGame.shownAs` is that choice for one
game.

The name was chosen over the dump's own country byte because it needs nothing stored: the byte
would have to be read at import and kept in Room and in backups, for a decision that is only about
which plastic to draw. The name is also what the owner sees and can correct. This is presentation
only ("media is presentation only"): identity, core, saves, options and scraping never ask
which shape a game has, and a test holds every other console to its one shape whatever a game is
called.

**A label is decoded for the object that carries it.** The label key and the decode take a `Media`,
not a console; heights are 856 (NES, an upright sheet), 184 (Super Famicom, a strip), 228 (North
American Super Nintendo) and 304 (Genesis), each within 1 % of its template's label. No two shapes
share a height, so a key never names the wrong decode. `TypesetLabel` sets a title by the label's
height: two lines on a strip, six on the upright sheet.

**Each download gives up its label its own way**, and the tool asserts each time that the print is
gone before it writes anything: the NES recess floor becomes the label; the Super Famicom's own
curved faces are cut out and become it, with the cut protected while the shell is reduced; the scan
and the game asset get a sheet laid over the print. Normal-map conventions are measured.

**Not converted, not changed.** The Nintendo 64 model has no console to be shown for. Master System
and Game Gear have no model and stay discs.

## Verified

Host: `python tools/test_assets.py` (10 tests over seven GLBs), the Khronos validator (0 errors, 0
warnings on each), `SystemRegistryTest` (shapes, the market rule on 22 names, label keys). Device
results are in TESTING under the same date.

## Consequences

- The debug APK grows by 4.7 MB of models.
- A game renamed from or to a North American tag changes shell the next time it is staged. That is
  the rule working, and the only way to change it short of a per-game override, which is not built.
- A Super Nintendo dump with no market in its name is drawn in the Japanese and PAL shell even if
  it is a North American game.
- The Genesis object is as coarse as its source; beside the others it looks it.
- The existing release gate grows: four more third-party models, one of them from a commercial
  game (LICENSING).
