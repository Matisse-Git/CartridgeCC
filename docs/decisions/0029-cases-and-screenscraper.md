# ADR 0029: Cases wrapped in ScreenScraper art

Date: 2026-10-17. Status: accepted; being built on branch `feature/box-cases`, increment by increment.
Steps 1 to 3 are implemented and run on the RP5 with original fixtures: every console is shown in its
case, and the seven cartridge and disc models are no longer packaged. Not run yet: the live ScreenScraper
path, because no login is on the device, and the owner's own games, because the branch app's library is
empty.

## Context

Games were shown as cartridges and discs carrying a flat label (ADR 0019, 0025). The owner wants each
game shown in its retail case instead (a cardboard box, a plastic clamshell, a CD jewel case or a
DVD-style keep case), wrapped in that game's cover from ScreenScraper. The decisions below were settled
with the owner in one interview on 2026-10-17, and supersede the parts of ADR 0005, 0010, 0019 and 0025
they contradict.

## Decision

### Branch and app

1. The work lives on `feature/box-cases`. Its build is a separate app, `dev.cartridge.boxes`, labelled
   *Cartridge Boxes*, with its own library, so the main app and its data are never touched. The debug
   fixture providers are named after the application ID so both debug builds can be installed together.
   The branch merges into master only when the owner says so. Merging puts back `dev.cartridge` and
   *Cartridge*. Accepted cartridge and disc labels stay stored but are no longer drawn; the owner runs
   *Scrape all* on the main library afterwards.

### Artwork source

2. ScreenScraper (API v2) is the default provider; RetroBase stays selectable in Settings › Library ›
   Artwork. ScreenScraper needs the owner's user login and a developer ID and password. All four are
   typed on the device and kept only in the app's private preferences (`artwork-source`): never in Git, a
   backup, a scrape pack, a stored URL, a journal or a log. No developer ID is built in. The API returns
   media links with the login in their query string: links are stored without it
   (`ScreenScraperProvider.withoutLogin`), and it is added back only for the download.
3. A scrape takes only **case art** and text details. Case art is `box-texture` (the whole unfolded
   cover: back, spine, front), or failing that `box-2D` (the front alone). It is fetched at most
   1024 px wide. Details are title, year, developer, publisher, genre, players and description.
   Screenshots, videos, logos and manuals are not downloaded.
4. A cartridge is looked up by its CRC, MD5 and SHA-1 (`jeuInfos`). A checksum match is the game, and
   no name search follows, which saves the owner's daily quota. A disc, or a cartridge ScreenScraper does
   not know, is looked up by name (`jeuRecherche`, the console's `systemeid` from `GameSystem.screenScraper`).

### Applying matches

5. **Adding a game** (a folder scan, a single file, the scan when the app opens) and every **bulk scope**
   (consoles, all games, games without case art) apply the best match without a review. This replaces
   the earlier rule that no match was accepted automatically. The best match is the checksum match if
   there is one, otherwise the provider's first. Choosing *This game* or a list of *Choose games* still
   opens the reviewed queue (ADR 0010), and is how the owner corrects a wrong match.
6. Bulk scopes offer **Fill missing** (default) or **Fill all**. Fill all replaces what an earlier
   automatic scrape applied; the owner's own choices are replaced only with *Also replace my manual
   choices* ticked. Adding a game always fills missing. The mode is chosen each time the panel opens
   and is never saved. Every automatic choice is marked `automatic` in its provenance. An automatic
   apply is one write with one Undo (`CollectionRepository.applyScraped`). A cartridge or disc label it
   displaces is kept as extra media, not deleted.
7. The ids waiting for an automatic scrape are saved before any network work (`SharedPendingScrapes`).
   A run that cannot reach the provider (offline, no login, quota, maintenance) stops and keeps them
   waiting. Settings shows why. They are tried again the next time a game is added or a bulk scope runs,
   never because the app opened. A game ScreenScraper does not know is not retried.

### Cases

8. The case replaces the cartridge and the disc. A game's **market** comes from its file name
   (`Market.of`): a US tag first (`(USA, Europe)` is US), then Europe (`(Europe)`, `(UK)`, `(E)`…), then
   Japan. `(World)` and an untagged name are US. A scraped title never changes it (`LibraryGame.fileName`).
   The market picks the case's proportions and ranks art from the same region first (EU: `uk`, `eu`,
   `wor`). Art of another region is still offered, and is stretched to this region's case.
9. Shapes, one per console and market, sized to the real thing:

   | Shape | Consoles |
   | --- | --- |
   | Small cardboard box | Game Boy, Game Boy Color, Game Boy Advance, Game Gear |
   | Cardboard box | NES, Super Nintendo |
   | Plastic clamshell | Genesis (US), Master System |
   | CD jewel case | PlayStation, Dreamcast US/JP, Saturn JP |
   | Tall case | Saturn US/EU, Dreamcast EU |
   | DVD keep case | PlayStation 2 |
   | GameCube keep case | GameCube |

   Markets whose real boxes match share one model.
10. Each case has exactly one art primitive, which wraps back, spine and front. `box-2D` art fills the
    front, and spine and back are plain. A game without case art shows a plain case with its title
    printed on the front and spine, as an unlabelled cartridge did.
11. Models are downloads converted by `tools/import_models.py` where a usable one exists. Shapes for
    which none is found are made by a case-only generator. As an exception to ADR 0019, it writes only
    case files, never an imported object. Every new model is rendered and looked at before it reaches
    the device.
12. Browsing, turning with the right stick, and leaving through the bottom of the screen are unchanged.
    A case does not open.

## Order

1. App ID, ScreenScraper, Settings login and automatic apply.
2. The PlayStation jewel case and the Game Boy Advance box, checked on the RP5 with the owner's games.
3. The remaining consoles, a few shapes at a time.

## Consequences

- The first scrape of a large library takes a while: one request a game for a known cartridge, two for
  a disc, plus one image, paced a little over one a second for a free ScreenScraper account.
- A wrong name match on a disc is applied until the owner reviews that game. Undo is per game.
- The owner's login sits in plain private preferences. `allowBackup` is off, and backups and packs
  never read those preferences.
