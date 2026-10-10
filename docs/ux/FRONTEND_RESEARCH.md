# Frontend research

Reviewed 2026-10-10 for the console-experience redesign. Nothing here is a hands-on evaluation: no
competitor was installed or exercised. Every observation is tagged.

- **Verified** — the linked page says it, and it was read during this review.
- **Secondary** — a third-party guide or review says it; the vendor page was not readable.
- **Inferred** — a design conclusion drawn for Project Cartridge, not a fact about the product.
- **Not verified** — the brief asked about it, but no readable source covered it.

No competitor code, artwork, layout or wording is reused. Screenshots were not inspected; the web
reader used here returns page text only.

## Beacon Game Launcher

Sources: [Play listing](https://play.google.com/store/apps/details?id=com.radikal.gamelauncher)
(page text could not be read by the fetch tool — **not verified**),
[setup guide, Joey's Retro Handhelds](https://joeysretrohandhelds.com/guides/beacon-game-launcher-setup-guide/),
[user reviews mirror](https://grand-screen.com/apps/beacon-game-launcher/reviews/),
[frontend round-up, Androidphoria](https://androidphoria.com/aplicaciones/mejores-frontend-para-emuladores-android).

| Aspect | Observation |
|---|---|
| Navigation | Secondary: opens on a "NOW" tab; L1/R1 switch tabs; B opens Android apps; Y opens settings. A row of cards at the bottom cycles the view mode. |
| Library | Secondary: three view modes, Gallery → List → Grid. Horizontal game row and per-platform switching are **not verified**. |
| Onboarding | Secondary: first screen is one large plus icon. Adding a platform is Platform type → Player app → ROMs folder → Add Platform, repeated per console. |
| Adding consoles | Secondary: Settings → Platforms → plus. Emulators must already be installed. |
| Metadata | Secondary: scraping sources are toggled in Settings; ScreenScraper and SteamGridDB each need an account. Reviews praise automatic covers and offline animated backgrounds. |
| Settings | Secondary: three groups — Platforms, Scraping, Preferences (theme, theme colour, background style, font, more). |
| Controller | Secondary: shoulder buttons move between top-level sections; face buttons open apps and settings. Hint presentation is **not verified**. |

Lessons (inferred):
1. An empty product should show exactly one obvious action. Beacon's lone plus icon is the right
   amount of first screen.
2. Three settings groups is enough when the product is opinionated; grouping follows user goals.
3. Shoulder buttons are the natural "move between collections" control on a handheld.

Friction to avoid (secondary/inferred): each console is a separate three-field form, so a
ten-console library is ten trips through the same dialog. The player app must be chosen by hand.
Scraping needs third-party accounts. Reviewers ask for cover resizing and report an emulator
that will not launch — costs of being a launcher for other apps, which Cartridge does not pay.

## Daijishō

Sources: [repository README](https://github.com/TapiocaFox/Daijishou),
[wiki home](https://github.com/TapiocaFox/Daijishou/wiki) (sub-pages failed to load).

| Aspect | Observation |
|---|---|
| Navigation | Verified: separate settings and widgets pages; genres, platforms and search exist. Exact tab layout **not verified**. Hotkeys "have different abilities in different sections". |
| Library | Verified: a *Platform* "contains players" and takes files from chosen sync paths. A *Player* is "a set of arguments… to execute playable files filtered by regular expressions". |
| Onboarding | Verified checklist: install emulators → set up hotkeys → download and import platforms → set game files, path and sync per platform → wallpaper pack and theme colour → add widgets. |
| Adding consoles | Verified: download a platform definition, import Pegasus config or shared JSON, or build one from players. Re-importing updates "without losing records". |
| Metadata | Verified: platforms can "scrape correct boxarts and other preview media". Sources **not verified**. RetroAchievements records after login. |
| Settings | Verified: theme colour and wallpapers live in settings. Category list **not verified**. |
| Controller | Verified: hotkeys are configurable. Defaults **not verified**. Favourites and recently played **not verified**. |

Lessons (inferred):
1. Console-first organisation is the model players already hold; a platform owns its paths.
2. Several source folders can feed one console without duplicating it.
3. Re-syncing must never cost the user their records.

Friction to avoid (verified/inferred): six setup stages before a game appears; players, launch
arguments and regular expressions are user-facing; buttons change meaning between sections. The
project is closed-source and its author describes development as slowed.

## ES-DE (EmulationStation Desktop Edition)

Sources: [site](https://es-de.org/),
[user guide](https://gitlab.com/es-de/emulationstation-de/-/raw/master/USERGUIDE.md) (first
100,000 characters read),
[Android guide](https://gitlab.com/es-de/emulationstation-de/-/raw/master/ANDROID.md) (first
100,000 characters read).

| Aspect | Observation |
|---|---|
| Navigation | Verified: system view → gamelist view. Start opens the main menu; Back/Select opens gamelist options; A selects, B goes back, Y toggles favourite, shoulders jump 10 games, triggers jump to first/last. |
| Library | Verified: one folder per system under a ROM root. Systems appear only when they contain games. |
| Onboarding | Verified (desktop): with no games, a dialog offers to change the ROM directory or generate the system directory structure. Verified (Android): a configurator requires storage access, then an application-data directory, a ROMs directory and optional system-folder creation. Only a "missing games" dialog shows until one supported file exists. |
| Adding consoles | Verified: drop files into the matching system folder; `systeminfo.txt` in each folder lists accepted extensions. |
| Metadata | Verified: "Built-in scraper for downloading game info and game media", including images, videos and manuals. Media is matched by ROM filename. |
| Settings | Verified entries: UI Settings, Other settings, Input device settings, Utilities. Kiosk and Kid modes restrict menus. |
| Controller | Verified: "Full controller navigation support"; an on-screen help system shows the actions available on the current screen and can be switched off. |

Lessons (inferred):
1. The empty state is a recovery screen: say what is missing and offer the fix in place.
2. Hide consoles that have no games.
3. Contextual help prompts that list only what the current screen can do, with an off switch.

Friction to avoid (verified): broad storage permission is mandatory before anything works; the
folder taxonomy is prescribed; multi-file discs need per-system scoped storage set up in each
emulator; missing cores or BIOS files produce "a black screen and hang at launch"; re-running
setup means revoking permission or clearing app storage.

## Lemuroid

Sources: [README](https://raw.githubusercontent.com/Swordfish90/Lemuroid/master/README.md),
[Scanning wiki page](https://github.com/Swordfish90/Lemuroid/wiki/Scanning).

| Aspect | Observation |
|---|---|
| Navigation | **Not verified** — the README does not describe screens. |
| Library | Verified: "ROMs scanning and indexing". Folder names such as `gba`, `gbc`, `gb`, `psx` matter for detection. |
| Onboarding | **Not verified**. |
| Adding games | Verified detection rules, in order: CRC32 matches the bundled Libretro database; serial inside an `.iso`; filename match; an extension that uniquely identifies a platform; a shared extension (`.iso`, `.bin`) inside a folder named for the platform. |
| Metadata | Verified: identity comes from a local copy of the Libretro DB. Artwork handling **not verified**. |
| Settings | Verified feature names only: automatic save and restore of states, quick save/load, LCD/CRT display simulation, HD mode, customisable touch controls, cloud save sync. Structure **not verified**. |
| Controller | Verified: gamepad support, local multiplayer. Mapping **not verified**. |

Lessons (inferred):
1. An embedded emulator removes the entire "choose a player" step. Cartridge already has this.
2. Identify by content first, extension second, folder name last — and only fall back to the
   folder name for extensions that several consoles share.
3. Resuming where you stopped should be automatic, not a save-management task.

Friction to avoid (verified): when detection fails the documented fix is "split ROMs into
platform-named folders"; multi-file zips are skipped silently; disc formats and BIOS are not
covered by the scanning documentation at all.

## ZNeko Launcher

Source: [repository README](https://github.com/zneko-org/zneko-launcher).

| Aspect | Observation |
|---|---|
| Navigation | Verified: customisable home with widgets, plus a library. |
| Library | Verified view modes: "Vertical, Horizontal, Physical, and Standard". Physical View "follows a concept from Socket". Grid is not mentioned. |
| Onboarding | Verified: "quick to set up and stays out of your way"; open a folder or launch a game and it guides setup. Step detail lives in documents that were not read. |
| Adding games | Verified: most platforms detected automatically; parent folders (`ROMs/gba`) and standalone platform folders both work; folder names are free. |
| Multi-disc | Verified: M3U playlists and "(Disc N)" naming are grouped automatically. |
| Metadata | Verified: synopsis, screenshots, videos, developer, publisher, rating and release date, all editable; per-game playtime. |
| Collections | Verified: "All Games" and "Favorites" folders, user collections, global search. |
| Controller | Verified: touch and gamepad input. Mapping **not verified**. |

Lessons (inferred):
1. Accept whatever folder the user already has — a parent of many consoles or a single console.
2. A physical-media view is a recognised, wanted presentation, not a novelty.
3. Discs of one game are one library entry.

Friction to avoid (inferred): a widget home in front of the collection dilutes the "games are the
interface" idea. Unsupported emulators are added by editing a JSON file.

## Pegasus Frontend

Sources: [site](https://pegasus-frontend.org/),
[metadata file documentation](https://pegasus-frontend.org/docs/user-guide/meta-files/).

| Aspect | Observation |
|---|---|
| Navigation | Verified: presentation is entirely theme-defined; themes can change "everything that is on the screen". No single layout exists to describe. |
| Library | Verified: text metadata files declare `collection` and `game` entries. Same-named collections in different files merge. "one game can belong to as many collections as you wish". |
| Onboarding | Verified: Settings → Set game directories, then author or import metadata. |
| Multi-file games | Verified: a game lists `file`/`files`; with several, "you'll be able to select which one to launch". |
| Metadata | Verified: developer, publisher, genre, tags, players, release, rating. Compatible with EmulationStation gamelists; recognises Steam. |
| Settings | Verified: collection-level `launch` with game-level override. |
| Controller | **Not verified**. |

Lessons (inferred):
1. Game identity, collection membership and presentation are three separate things. A console
   shelf is a projection over the same games, never a second copy.
2. Defaults at the collection level with an override at the game level is an understood model.
3. Presentation can be replaced without touching data — which is exactly the shape of this redesign.

Friction to avoid (verified): authoring text files and launch commands is a prerequisite.

## RetroArch / Libretro (capability reference only)

Sources: [Libretro docs](https://docs.libretro.com/),
[PCSX ReARMed core page](https://docs.libretro.com/library/pcsx_rearmed/).

Verified for PCSX ReARMed: content extensions `.bin .cue .img .mdf .pbp .toc .cbn .m3u .ccd .chd
.iso .exe`; retail BIOS optional with HLE fallback that "can cause issues such as memory card
problems and black screens"; multi-disc through an `.m3u` of `.cue` files and the disk-control
interface; options for region, BIOS selection, dithering, frameskip, enhanced resolution, memory
card type, CPU clock and many compatibility and speed hacks.

Checked against this repository rather than taken from the documentation:

| Capability | Upstream | In Cartridge's pinned build |
|---|---|---|
| Enhanced resolution | `pcsx_rearmed_neon_enhancement_enable` | Present: `GPU_NEON` is defined in `pcsx.cmake` |
| Dithering | `pcsx_rearmed_dithering` | Present, unguarded |
| Screen shaders | — | LibretroDroid 0.14.0 `ShaderConfig` Default / Sharp / LCD / CRT |
| mGBA audio low-pass, frameskip | `mgba_audio_low_pass_filter`, `mgba_frameskip` | Present, already used |
| Disc switching | Disk control interface | Wrapper exposes `changeDisk`, but the importer rejects `.m3u`; **not available** |
| Rewind, netplay, cheats, achievements | Documented upstream | Not integrated; **not available** |
| Analog / DualShock | Documented upstream | Digital pad only (PS1_PROGRESS) |

Lessons (inferred):
1. Translate: "Dithering Pattern" becomes "Colour smoothing"; "Enhanced Resolution" becomes
   "Sharper 3D". One sentence of consequence, no core jargon.
2. Scope is the thing RetroArch gets wrong for consumers. Say which console a setting belongs to
   and whether a game overrides it, in the place the setting is shown.
3. A setting that needs a restart must say so on the row.

Avoid: the Quick Menu's depth, hundreds of options, and offering a control because upstream
documents it rather than because this build honours it.

## Comparison

| | Beacon | Daijishō | ES-DE | Lemuroid | ZNeko | Pegasus | **Cartridge (target)** |
|---|---|---|---|---|---|---|---|
| Emulation | external | external | external | embedded | external | external | **embedded** |
| Steps to first game | form per console | 6 stages | permission + 3 dirs + files | scan | folder | author metadata | **welcome → folder → play** |
| Console setup | manual form | import definition | folder taxonomy | automatic | automatic | manual | **automatic, no form** |
| Empty consoles | n/v | n/v | hidden | n/v | n/v | hidden | **hidden** |
| Collection switch | L1/R1 (tabs) | n/v | system view | n/v | n/v | theme | **L1/R1** |
| Media presentation | covers | covers | theme | covers | covers / physical | theme | **real 3D media** |
| Multi-disc | n/v | n/v | folders | n/v | grouped | file list | **CUE+BIN grouped; no M3U** |
| Settings groups | 3 | n/v | 4 + utilities | n/v | n/v | theme | **6 by goal** |
| Contextual help | n/v | n/v | yes, optional | n/v | n/v | theme | **yes, auto-fading** |

n/v = not verified.

## Decisions for Project Cartridge

1. **One folder picker replaces platform setup.** Cartridge detects the console from content and
   extension, uses folder names only to *exclude* disc images that belong to another console,
   and never asks which emulator to use. (Lemuroid's rules, ZNeko's folder tolerance, against
   Beacon's and Daijishō's forms.)
2. **Console shelves are projections.** L1/R1 move between consoles that have games, then All
   Games, Favorites and Recently Played. No shelf rewrites membership or stored order. (Pegasus.)
3. **Empty consoles do not exist** until a game for them is imported. (ES-DE.)
4. **The first screen has one action.** "Add your games" over the real 3D object. (Beacon.)
5. **Hints list only what this screen does** and retire themselves once the player has started a
   few games; a setting keeps them or hides them. (ES-DE's help system, made self-effacing.)
6. **Start is the menu, B is back, A acts, everywhere.** No section-dependent meanings.
   (Against Daijishō.)
7. **Settings are six goal groups**, with console options under Gameplay and per-game overrides
   reached from the game itself. Each row states its scope and whether it needs a restart.
   (Against RetroArch.)
8. **Only verified capabilities are shown.** Disc switching, rewind, fast-forward and analog pads
   stay out of the interface until the adapter implements them.
9. **Artwork stays opt-in and reviewed.** The existing reviewed scraper is kept; games without
   artwork get a typeset label on the real model instead of a placeholder image.
10. **No broad storage permission, no account.** SAF tree grants only. (Against ES-DE on Android
    and Beacon's scraper accounts.)

What this redesign deliberately does not take: widget home screens, theme engines, per-console
launch commands, or a prescribed folder taxonomy.
