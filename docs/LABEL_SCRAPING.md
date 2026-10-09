# Label import and scraping requirements

Required by the owner on 2026-10-09. This document specifies future functionality; M0 has no image
importer, scraper, candidate chooser or provider credentials. Offline ROM launching remains independent.

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

No provider has been selected, no account created and no remote artwork downloaded. Inspect live API
schemas, access/quota rules, service approval and asset rights before choosing an adapter. Local import
is useful independently and must remain available when a provider is unavailable. Network-based scraping
will require a deliberate internet permission and credential-storage design in that later milestone.

## Acceptance criteria

- Import a local label through SAF and preview it on the proper template while other games keep theirs.
- Resolve multiple title matches and label/metadata variants independently with controller-only input.
- Scrape one title, selected titles, one/selected consoles and the whole existing library.
- Review ambiguous matches, skip missing media, cancel/retry/resume a batch and undo accepted replacements.
- Prove fill-missing/manual protection, region ranking, duplicate handling and partial-failure recovery.
- Relaunch offline and browse/play with accepted labels/metadata; no account or network needed to launch.
- Audit the selected provider terms and asset provenance; keep credentials and downloaded media out of Git.
