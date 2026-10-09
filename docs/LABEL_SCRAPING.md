# Label import and scraping requirements

Required by the owner on 2026-10-09. M1 now implements single local PNG/JPEG/WebP import through SAF,
real 3D Fit/Crop/rotation preview and independent persistent labels. RP5 tests prove per-game GPU pixels,
cancel, identical-ROM reimport and database reopening. Real RetroBase API scraping now adds one-title
and current-GBA-collection search, ranked multiple candidates, regional thumbnails, independent
label/media/metadata choices and accepted provenance. The full workflow below also includes future
scrape packs, selected subsets/consoles, undo and durable jobs. Offline ROM launching is independent.

## User flow

1. From a title's details, choose Import label or Scrape. From library tools choose selected titles,
   one/selected consoles, or all games already in the library. The controller and touch use one workflow.
2. Local import accepts an image or a supported scraped-media pack through SAF. A pack adapter must
   declare its supported schema before implementation; no assumption that all scraper exports agree.
   Preserve original files under user control and copy only accepted assets into the local cache.
3. Online search ranks exact hashes and system matches first, then filename/title matches. A filename
   guess is not an accepted identity. Show ranked alternatives with title, platform, region, language,
   edition/revision and disc number where supplied. Allow search correction and skip.
4. Pick identity, physical template, label image, additional media and metadata independently. Multiple
   assets get a thumbnail chooser with source, dimensions, region/language and asset category. Allow
   one source's label and another's description. Missing provider fields remain optional.
5. Preview the label on the actual cartridge/disc. Offer crop, fit and rotation, preserving the original.
   Disc masking respects the centre hole. Box/case art, screenshots, full-media photographs and flat
   label textures have explicit categories; never automatically distort box art into a cartridge label.
6. Review and apply the proposed choices. Retain prior accepted versions so replacement can be undone.
   Returning to the collection uses cached assets immediately; fetching never blocks gameplay.

## Bulk workflow

The selected scope becomes a stable list of existing library IDs. Show counts, progress, current title,
unmatched/ambiguous entries and failures. Default to Fill missing, preserving manual selections. Replace
existing choices requires an explicit mode and a before/after review. A separate opt-in is needed to
replace manual choices. Region/language preferences rank assets without suppressing other choices.

High-confidence matches may fill missing fields under the selected policy; ambiguous identity, media
or metadata choices go into a controller-friendly review queue. Offer per-item apply/skip and a clearly
scoped apply-to-remaining choice. Cancellation stops pending network work and leaves completed accepted
choices consistent. Retry failures without redoing successful entries; resume interrupted jobs locally.
Rate limits, bounded concurrency and provider quotas are reflected in job status.

## Persistence and renderer contract

Keep the ROM content hash as the stable game identity. Provider records are external references, not
replacement primary keys. Save accepted game-match IDs, per-field metadata values/provenance, chosen
media type/region/edition/disc, source URL/provider ID, fetch time, checksum, dimensions, local file,
crop recipe and manual/automatic selection flag. Missing licence/credit information is recorded as
unknown, not inferred as permission to redistribute. Secrets do not belong in Git or exported packs.

Candidate caches are disposable; accepted originals and selections are persistent. Keep derived label
textures separate from originals, deduplicate by content hash, and bound visible texture allocations.
One mesh serves many games with independent material instances; replacement must not mutate every
instance's label. Decode off Main, perform Filament work on Main, and release textures on eviction and
renderer destruction. Local Room writes and file promotion must recover from partial failure.

## Provider feasibility research

[ScreenScraper's official API v2 documentation](https://www.screenscraper.fr/webapi2.php) was inspected
on 2026-10-09. It exposes game search/info, media catalogues and region/disc variants, including support
textures. Availability of usable flat cartridge or disc labels must be checked against the catalogue
and actual responses; a support image is not assumed to be a flat label. API developer credentials
require contacting the service. The documented use terms require a completely free app or prior
agreement for other distribution models. These are unresolved adoption gates, not an implemented API.

RetroBase Collection is selected for the first account-free API proof. OpenAPI and actual regional
support-texture records were inspected, and the live contract test passes on RP5. Only fingerprints,
sizes/platforms and title queries are sent; never ROM bytes. The normal INTERNET permission is explicit.
No account or secret is stored. The provider's final data license remains pending; images stay with
their rightsholders. Runtime assets are private, never Git/APK fixtures. See PROVIDER_RESEARCH,
SCRAPING_REPORT and LICENSING for evidence and precise Socket/Slot findings.

## Acceptance criteria

- Import a local label through SAF and preview it on the proper template while other games keep theirs.
- Resolve multiple title matches and label/metadata variants independently with controller-only input.
- Scrape one title, selected titles, one/selected consoles and the whole existing library.
- Review ambiguous matches, skip missing media, cancel/retry/resume a batch and undo accepted replacements.
- Prove fill-missing/manual protection, region ranking, duplicate handling and partial-failure recovery.
- Relaunch offline and browse/play with accepted labels/metadata; no account or network needed to launch.
- Audit the selected provider terms and asset provenance; keep credentials and downloaded media out of Git.

## Current implementation and limits

GBA only, a growing persistent collection, one accepted label and one asset per additional-media category
per game. SAF folder/Add .gba imports fill empty slots then append distinct content hashes. One-title,
GBA collection and all-current-games scopes operate on deduplicated existing identities; the latter
two remain the same scope while GBA is the only system. Every apply is explicit, protecting unchecked
fields/current labels; no automatic fill-missing or apply-to-remaining policy. Search cancellation
preserves accepted choices; failed and unfinished searches can retry or explicitly resume after reopening.

Room v4 retains v2's per-field/provider/record/source/license/time and asset type/region, adding saved
SAF directory URIs and removal exclusions through explicit migrations. Collection removal retains
accepted choices; Undo/Add restores them, while Rescan respects exclusions. Originals carry a
local SHA-256 content ID, while label Fit/Crop/rotation remains separate. Dimensions are checked at
decode, not stored as provenance. Edition/disc/language tags and selection history are not modeled yet.
Candidate JSON has a one-hour TTL and a 32-file/32-MiB cap; accepted assets persist offline.

ScrapeJournal stores targets, corrected queries, search/error status and review index in an atomic
manifest, plus candidates in one file per title (≤8 MiB per file). Only current candidates occupy RAM.
Completed searches load from disk on Resume; no implicit network activity on cold launch. One saved
batch is supported. Starting a new scope replaces unfinished search progress, preserving applied data.
Unapplied previews/checkbox drafts are discarded on recreation. See LIBRARY_PROGRESS and ADR 0006.

Selected subsets/consoles, preferences, multiple providers, scrape-pack import, accepted-artwork undo, full metadata/details
views and disc templates/emulation are future work. Directory rescans rehash files; scan cursors are
not resumed after recreation, but completed imports remain deduplicated.
Cancelled imports can leave unreferenced originals; reference-aware garbage collection is pending.
The provider's source checksum is not verified; cached originals have the app's own SHA-256 identity.

M3 library projection keeps full canonical entries separate from favorites/search/sort views.
The GBA/all scraper scopes still include the whole active collection; filters do not silently shrink
those scopes. Details offers a direct single-title entry and local changed-field edits with independent
provenance. Existing reviewed labels/media and saved batches are unchanged. See M3_PROGRESS.
