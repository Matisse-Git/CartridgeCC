# Product scope and UX

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

The cartridge model, lighting and motion still need product polish and a receiving mechanism. This
increment proves the local label path; it does not claim M1 completion.

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
saved batches resume after reopening with accepted choices preserved. Selected subsets/consoles,
scrape packs, undo and automatic fill-missing remain M3 work.
See [LABEL_SCRAPING.md](LABEL_SCRAPING.md) for the complete required workflow and current boundaries.

## Explicitly deferred

The final believable slot/insertion/ejection choreography and mechanical sound, production mesh/textures,
full gameplay touch controls, per-button remapping, details/media panels, incremental directory indexing,
directory removal/availability UI, favorites, scheduled unattended scraping, vertical/grid layouts, additional systems,
HOME launcher mode and backup. Backgrounding currently ends the session after persisting RAM;
the app does not claim seamless resume of a lost EGL context.

No confirmation dialog is added for ordinary insertion/ejection. Loading/saving errors remain visible
and preserve selection. A failed save is reported even though native resources are still shut down.
Force-stop/process death cannot guarantee a final SaveRAM write; periodic checkpoints are future work.

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
scraper UX remains required and is deferred to a later version at the owner's request.
