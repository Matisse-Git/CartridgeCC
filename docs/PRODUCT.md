# Product scope and UX

**2026-10-10 — console experience.** The interface was redesigned around one idea: the games are
the interface. Four destinations (Library, Details, System Menu, Pause) replace sixteen; consoles
are shelves reached with L1/R1; an empty library is the welcome; importing is one folder choice
with a per-console result; settings are six goal-based sections with console and per-game scope for
gameplay options; time and battery are real. The complete specification, the audit it answers, the
research behind it and its verification are in [docs/ux](ux/REDESIGN_SPEC.md). Sections below
describe earlier milestones; where they name the Tools shelf, the B collection menu, Y / Select /
Start shortcuts or Console settings, the specification supersedes them.

The 2026-10-10 PS1 increment adds internal PlayStation gameplay and original 3D optical discs.
CUE/BIN folder import is the owner's first workflow; CHD/PBP/ISO single files and explicit BIOS import
are also implemented. Disc label scraping remains the primary artwork workflow and reuses existing
review scopes. Digital controller, one memory card and single-disc operation define the initial scope;
see PS1_PROGRESS for implemented behavior, device evidence and remaining compatibility checks.

**2026-10-10 — media models and browsing.** At the owner's direction the cartridge, the tall
cartridge and the disc are converted from three Sketchfab downloads instead of being generated, and
they carry their makers' console marks on the shell. Each game still shows its own label: the
downloads' baked game labels are removed and the app's replaceable label takes their place.
Browsing a shelf longer than the screen no longer re-labels the objects in view; a game keeps its
object and an object never appears with another game's label. See ADR 0019 and ASSET_PIPELINE.
Statements below that the bundled media are original or unbranded describe the earlier templates.

**2026-10-10 — console feel (ADR 0020).** A game starts and ends behind a closed screen: the object
leaves through the bottom of the screen for cartridges and discs alike, the screen closes with the
owner's choice of Fade, Iris or Shutter, and opens only on a real picture. No loading title is
shown for any console. A shelf reached with L1/R1 opens on its first game. The right stick turns
the focused object. Browsing a large library no longer stutters. Statements below about a receiving
slot, a loading title, or a shelf remembering its own game describe earlier behaviour.

**2026-10-11 — Android apps (ADR 0021).** Cartridge is the front of the handheld, so the other apps
on it are one press away: B on the game library, with nothing else open, opens **Apps**; A starts
the focused app the way a home screen does; B returns to the same shelf and game. Games are physical
media; apps are their own icons. They are never mixed: an app is not a library entry, has no console
and no 3D object, and nothing about it is stored except which one was focused. There is no Apps
button, tab or menu row — one hint, `B Apps`, which retires once it has been used a few times.
Scope is finding and starting. Managing apps (uninstalling, hiding, pinning, folders) is not offered.
Games are still run only by the embedded cores; an emulator the owner starts from Apps is just an
Android app they started.

Project Cartridge is one native, offline-first console experience: physical media collection →
insertion → internal gameplay → app-owned pause/save/load → ejection → collection. The cartridge
is the centerpiece. Warm charcoal, quiet lighting, restrained text and controller-first navigation
guide later refinement. Bundled labels are original; user-selected scraped artwork is privately cached.
No Apple/Nintendo interface assets, commercial ROMs or BIOS are bundled.

The supplied master specification governs the roadmap. **M0 feasibility passed on the physical RP5
on 2026-10-09**; M1 is in progress, with broader lifecycle/performance hardening still required.

## Implemented M0 slice

- Landscape immersive Android shell, with a horizontal three-object scene.
- One genuine instanced GBA-shaped GLB, molded-plastic/label/contact PBR materials.
- D-pad/stick focus, deterministic axis repeat and spring retargeting; touch fallback.
- Three engineering slots; M0 assigned one user-selected `.gba` per slot for that app session.
- Short lift, release barrier, internal LibretroDroid/mGBA surface, truthful readiness/errors.
- App-owned pause menu, resume, single state slot, SaveRAM and return to selected collection item.
- Reduced-motion toggle and two menu-shortcut choices saved locally.

The fictional ORBIT FIELD label is engineering artwork, not a claim a playable game is bundled.
Empty study slots cannot launch. Metadata does not block a user-selected game.

## Implemented M1 increment

- Room persists the three game slots, source URIs, content identities and last selected slot.
- X chooses a game; Select imports an optional local PNG/JPEG/WebP label through SAF. A real 3D preview offers
  Fit/Crop, 90° rotation, Apply and Cancel using controller or touch. No choice is saved on Cancel.
- Per-game label materials/textures share the original mesh. Original image bytes and their source
  URI are retained; recipes survive relaunch and reimporting an identical ROM preserves its label.
- Insertion lifts/aligns/slides; ejection reverses the motion. A tested coordinator locks selection,
  serializes operations, handles failed preparation and releases renderers before the next GPU owner.
- RP5 ordinary UI tests cover pause/save/load/eject, backgrounding, Activity recreation, cancelled
  insertion and recovery. Reopening the Room database restores the independent labels and focus.

That increment proved the local label path; it did not claim M1 completion. The owner's subsequent M2
request brings forward the media/lighting/motion refinement below, retaining unfinished stability checks.

## Implemented M2 refinement

The cartridge has rounded bevels, a recessed rounded label, grip ribs, a side seam, visible plated
contacts and restrained micrograin/roughness variation. An original prefiltered HDR studio environment
provides calm diffuse/specular lighting. A small receiving slot appears only during insertion/ejection;
its geometry occludes the descending shell. The normalized lift/align/slide choreography uses 540 ms
insertion and 420 ms reverse ejection. Readiness still waits for real core initialization/frame output.

Motion off bypasses both media transitions and carousel spring movement. Optional original contact
audio defaults off. Motion/sound/shortcut settings and touch import/scrape controls are behind Tools;
the collection at rest shows its objects, title, small hints and Tools. Existing controller shortcuts
operate without opening it. The then-deferred scraper UX is extended in M3 below. No new confirmation
dialog, proprietary asset, always-moving decoration, disc system or alternate layout is introduced.

## Implemented online scraping increment

Y opens real RetroBase API scraping with single-title/GBA/all-current-library scopes. Review ranked
hash/title candidates, correct a search, pick regional flat-label thumbnails, preview/apply them on
the real model, and choose metadata fields/variants or additional media independently. No automatic
replacement occurs. Sequential queue search reports progress/errors; accepted choices survive cancel
and offline relaunch. Room v2 migrates existing slots and labels without deleting them. No account is
needed, and no ROM bytes are uploaded. Source/record/region/license/time are retained per selection.
See SCRAPING_REPORT and PROVIDER_RESEARCH for evidence and exact limits.

## Required label import and scraping experience

User addition, 2026-10-09: importing already scraped cartridge/disc labels and choosing scraped
media/metadata is a first-class feature. Support a single title, selected titles, one or selected
consoles, and the entire local library. These scopes operate on user-owned library entries, not a
provider's whole ROM catalogue. Local image/scrape-pack import must also work without a provider account.

Show controller-friendly candidate thumbnails when multiple matches or assets exist. Include region,
language, edition, disc number, provider and resolution where available. Let the user choose game
identity, cartridge/disc label, other media and metadata fields independently, including mixing sources.
Preview the selected label on the actual 3D template with crop/fit/rotation controls. Box art, a complete
cartridge photograph and a flat label are distinct media; never silently stretch one into another.

Bulk scraping defaults to filling missing fields, protects manual selections, reports progress and
supports cancel/retry/skip. Ambiguous matches go to a review queue. Replacement is explicit, with a
reviewable change summary. Region/language preferences rank choices but do not hide alternatives.
Accepted assets and provenance are cached locally; network failures must never block launching a game.

Local image import, online one-title/current-GBA-collection scraping, independent metadata/media
candidates, manual review and accepted provenance are implemented. Folder imports grow the GBA library;
saved batches resume after reopening with accepted choices preserved. M3 adds selected subsets,
preferences, reviewed fill-missing and persistent undo below. Multi-console scopes, scrape packs and
unattended acceptance remain future work.
See [LABEL_SCRAPING.md](LABEL_SCRAPING.md) for the complete required workflow and current boundaries.

## Explicitly deferred

Manual final art direction and broader lighting calibration, full gameplay touch controls,
per-button remapping, expanded additional-artwork presentation, scheduled unattended scraping, vertical/grid layouts, additional systems,
HOME launcher mode and backup. Backgrounding currently ends the session after persisting RAM;
the app does not claim seamless resume of a lost EGL context.

No confirmation dialog is added for ordinary insertion/ejection. Loading/saving errors remain visible
and preserve selection. A failed save is reported even though native resources are still shut down.
Changed battery memory now checkpoints every 15 seconds during play, on pause/state operations and
normal ejection. Restoration finishes before the first emulated frame. Save failures remain visible
and preserve the previous file. Force-stop/process death can still lose changes since the latest
successful checkpoint; no automatic full save-state resume or zero-loss guarantee.

## Larger GBA library increment

Start / Folder imports nested .gba files through SAF; multiple read-granted roots and an explicit
Rescan persist. Content hashes collapse legacy and new duplicates while preserving labels/metadata
and focused identity. Actual entries expand; empty collections show one placeholder. At most three
genuine GLB instances remain visible/allocated. B cancels a scan while keeping completed imports.
X / Add .gba adds or selects a single existing entry. B / Collection offers removal and single-step
Undo. Removal keeps ROMs/saves/artwork and survives Rescan/reopening; Add .gba restores hidden entries.
Undo is available in the current Activity session.

Bulk scope now covers imported GBA games. Y → Resume saved batch reads saved targets/candidates and
continues unfinished search work without reapplying accepted media/metadata. No network work resumes
automatically on cold launch. New scopes replace unfinished search progress, with an explanation.
Unapplied preview/metadata drafts are discarded on recreation. See LIBRARY_PROGRESS for exact limits.

Owner feedback: saved-batch resume works, but the review flow is tedious. A simpler controller-led
scraper UX was deferred at that stage; M3 now implements the focused review increment below.

## Active M3 library management

R1 opens optional details with metadata, playtime, last played, local save dates, cached-ROM readiness,
last scan's source availability and accepted artwork/metadata provenance. L1 toggles favorite. The
collection menu offers import/title/recent/most-played sorting, favorites filtering, reset and a
folder/search panel. Search intersects favorites and matches title/developer/genre words. Preference
changes retain focus by hash; no match renders an explicit empty state. Canonical membership and
all-library scraper scopes remain independent of the visible filter. All labels still use the bounded
three-instance pool; opaque details/folder/edit panels pause its render loop.

Edit metadata changes only edited fields and records local provenance; blank optional fields clear,
blank title restores the filename. Locate same ROM requires matching hash and preserves enrichment/saves.
Text entry uses Android/Compose keyboard/focus with touch as a secondary input; controller actions
and panel back/navigation remain separate from gameplay. Drafts are not implicitly accepted on recreation.

Remembered SAF roots scan automatically on open by default, with a persisted opt-out. Progress/cancel
keep completed imports. Size/mtime indexing skips unchanged, positively stamped documents only when
a matching working copy exists. Verify all rehashes every ROM. A same-name changed ROM creates a new
identity; it cannot inherit another ROM's saves. Complete enumeration may mark unseen indexed documents
missing; cancellation/subfolder failures never infer deletion. Cached originals stay playable offline.
Forgetting a root keeps games/media/saves and stops its scans; choosing the root again reauthorizes access.

History accrues during Running only, with 15-second checkpoints and pause/eject/background flushes.
Pending failed writes remain attached to their original hash for retry and display a persistent warning.
Last played reflects a successful running/resumed session, not the insertion animation. There is no
zero-loss process-death promise beyond completed checkpoints. Multi-system/scoped scrape-pack support
remains planned; existing choices are never implicitly replaced.

## M3 faster API review

Y offers selected titles and missing-label scopes alongside single/GBA/all. A toggles target titles;
X selects all/clears. Region/language settings persist and rank provider-tagged choices without hiding
alternatives. Search the current title first; choose a match, preview a preferred flat label, fill missing
metadata and advance. Existing choices display as kept. All media and individual field/variant controls
remain available for explicit replacement. One previous artwork/metadata operation per game can be
undone after reopening, through review or Details. It does not rewind saves/history/ROMs/favorites.
Opaque review pages pause collection rendering. A hidden batch target's actual 3D preview restores the
saved filter/focus after apply/cancel/recreation. No network resume or unattended acceptance on launch.
M5 adds GB/GBC and shared selectable console scopes; disc labels/templates remain future work.

## M3 offline packs

Y → Import scrape pack accepts the documented private Cartridge v1 ZIP through SAF. Only existing
ROM hashes match; reviewed candidates retain original source/region/language/provenance. The same
preferred-label preview, separate metadata/media selection, missing-only merge, resume and undo apply.
Packs contain no ROMs/BIOS/saves; importing never changes membership or launches network calls. Unknown
formats/unsafe paths/oversized or altered images are rejected. See SCRAPE_PACK_FORMAT/ADR 0011.
## M4 collection views

Horizontal remains the default. B → Collection → View cycles Horizontal, Vertical and Grid and saves
the preference. Focus, accepted labels, metadata, filters, saves and launch behavior are shared. Grid
has three columns, no horizontal row wrapping and a clamped partial final row. D-pad/left stick follow
the view's spatial axes; touch arrows advance sequentially. A fixed pool of reusable genuine 3D
objects (fifteen per media shape since ADR 0019) bounds rendering independently of collection size. Reduced motion applies to every layout.

## M5 additional systems

SAF imports, per-game templates and API scraping now support GBA, GB and GBC. Choose consoles selects any subset; All games includes all three. The tall GB/GBC shell uses independent 512×448 labels. Native SRAM/RTC and state-buffer synchronization are verified with original diagnostics; see M5_PROGRESS.

## M6 console settings

An empty first collection has skippable setup; existing owners go straight to their games. Controller and core settings are app-owned and persist. Menu/gameplay A/B choices are independent. Optional HOME mode uses Android’s default chooser with an initially disabled alias. Matching licence notices are visible in-app. See M6_PROGRESS for implemented behavior and remaining sleep/backup/stability work.

## M6 session recovery

Sleep/wake restores the game paused, with an actual frozen gameplay frame. Cold opening still enters the collection and offers Resume session. Save state and the automatic session are independent. Ejection clears the automatic session after successful save/stop; Discard keeps battery/manual saves. A failed or incompatible checkpoint reports a concise error and returns to the collection. Sudden termination can lose progress since the last completed checkpoint.

Console settings offers Export backup / Restore backup. Review counts and opt into settings when needed; existing collection choices/saves win. ROMs are excluded, so folder permissions must be reauthorized after a device transfer. See BACKUP_FORMAT.

## Consoles (2026-10-11)

*Since then: games have been run on Dreamcast, PlayStation 2 and GameCube (ADR 0024), and
PlayStation 2 moved to an engine that runs the console's own BIOS, which the owner adds once
(ADR 0026). [CONSOLES](CONSOLES.md) is the current statement; the paragraph below is the day's.*

Nine consoles are playable inside Cartridge. Four more are implemented and wait for something only
the owner has: Saturn for its BIOS; Dreamcast, PlayStation 2 and GameCube for a first real game
(their engines start, draw and take input on the device from original test programs; no game has
been run). Each console with games is its own shelf, in a fixed order: Game Boy Advance, Game Boy,
Game Boy Color, PlayStation, Super Nintendo, Nintendo Entertainment System, Genesis, Master System,
Game Gear, Saturn, Dreamcast, PlayStation 2, GameCube. A console without games takes no space
anywhere but Settings › Gameplay.

Promises the interface keeps: a console that cannot be played is never shown as one, only counted
on the import result; a console that needs a file from its owner says so before a game is started,
names the file and offers the picker; an option is shown only if the packaged core honours it; a
new console looks like the rest of the app, because it is drawn by the same shelf, object pool,
details, pause menu and settings rows. A console's games are shown as its own cartridge where the
owner has supplied a model (Game Boy, Game Boy Advance, NES, Super Nintendo in both its shells,
Genesis) and as a disc otherwise. Putting the device to sleep never costs a game its place: a
console with save states comes back from a checkpoint, one without stays loaded, and both return
at the pause menu. Status, formats and limits are kept in [CONSOLES](CONSOLES.md).
