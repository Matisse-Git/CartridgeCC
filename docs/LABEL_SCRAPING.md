# Label import and scraping requirements

Required by the owner on 2026-10-09. M1 now implements single local PNG/JPEG/WebP import through SAF,
real 3D Fit/Crop/rotation preview and independent persistent labels. RP5 tests prove per-game GPU pixels,
cancel, identical-ROM reimport and database reopening. Real RetroBase API scraping now adds one-title
and current-GBA-collection search, ranked multiple candidates, regional thumbnails, independent
label/media/metadata choices and accepted provenance. M3 adds selected scopes, preferences, protected
fill-missing, faster reviewed batches and undo. Private Cartridge v1 packs are implemented; multi-console scopes remain future work. Offline
ROM launching is independent.

## Cases and automatic apply (2026-10-17, ADR 0029, merged 2026-10-18)

This supersedes parts of what follows. Games are shown in their retail case, and case art is
`box-texture` (the whole cover) or `box-2D` (the front). A flat `support-texture` label is kept as extra
media, never wrapped round a case. ScreenScraper is the default source, using the owner's login and
developer ID from Settings › Library › Artwork; RetroBase stays selectable. Adding a game and the bulk
scopes (consoles, all games, games without case art) apply the best match automatically, with Fill
missing (default) or Fill all, which replaces only `automatic` choices unless *Also replace my manual
choices* is ticked. *This game* and *Choose games* keep the reviewed queue below. Automatic choices are
one write with one Undo. Games waiting for a scrape are saved before network work; an unreachable
provider leaves them waiting until the next add or bulk scrape, never until the app opens.

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
per game. SAF imports deduplicate content hashes. Single/GBA/all, selected-title and missing-label scopes
use canonical identities; collection filters do not silently reduce whole-library scope. GBA/all remain
identical with one console. A saved batch is published before network work. Search only the current title,
review its match, then explicitly advance; completed candidates survive cancellation/relaunch.

Review offers a preferred flat-label 3D preview, Fill missing metadata and Next. Persistent title/label
region and description-language preferences rank source-tagged alternatives stably, without hiding any.
Defaults US/English; Any keeps source order. Unknown tags stay untagged. All media and individual metadata
fields/variants allow explicit replacements; existing values are displayed. Fill missing reads/merges
current Room data atomically, preserving manual and previous provider choices. It cannot replace labels.
No unattended apply-all or automatic acceptance of ambiguous title matches is implemented.

Room v6 retains per-field/provider/record/source/license/time and asset type/region through explicit
migrations, adding one prior enrichment snapshot per game. Changed label/metadata/media writes save it
atomically; no-op writes retain it. Undo restores only the previous enrichment and consumes the snapshot.
It survives reopening; ROM/source/membership/saves/history/favorites/query remain independent. Original
files remain available. Removal keeps accepted choices/undo; rescan exclusions persist.

Originals have local SHA-256 content IDs; Fit/Crop/rotation are separate recipes. Dimensions are checked
at decode, not stored in provenance. Edition/disc tags and multi-step selection history are not modeled.
Accepted metadata stores values/provenance, not its candidate's region/language tag separately. Candidate
cache TTL is one hour with a 32-file/32-MiB cap; accepted assets persist offline. Source checksums are not
verified; cached originals use the app's own hash.

ScrapeJournal v2 holds targets, corrected queries, searched/error status, review position and applied/
skipped outcomes in an atomic manifest; tagged candidates use one ≤8-MiB file per title. Only current
candidates occupy RAM. It reads v1 and untagged candidates. Resume refreshes accepted Room values; no
implicit network activity on launch. One saved batch; a new scope replaces progress, preserving accepted
data. Unapplied preview/checkbox drafts are discarded on recreation. Undo does not recompute batch counts.

A filtered target's preview uses a temporary view projection without persisting query/focus changes.
Apply/cancel/recreation restore the saved collection view. Opaque scraper pages pause the Filament loop;
the real preview resumes it. Gameplay surface/core ownership is unchanged. See M3_SCRAPE_REVIEW/ADR 0010.

Cartridge v1 offline scrape packs now reuse this review/journal/preview pipeline with checksum-validated
private images and exact ROM-hash matching. See SCRAPE_PACK_FORMAT/ADR 0011. Unrecognized scraper
formats need conversion; no account or network is used for imported candidates/images.

Remaining: selected-console scopes, multiple providers, disc templates/emulation,
reference-aware garbage collection and large-batch/storage-failure validation. Incremental directory
rescans/details/favorites/local metadata are implemented in M3_PROGRESS; scan cursors do not resume
across recreation, but completed imports remain deduplicated. Provider data/image rights and exact
Socket backend/coverage remain unresolved as documented in PROVIDER_RESEARCH and LICENSING.
