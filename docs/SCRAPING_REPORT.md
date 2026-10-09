# API scraping increment — 2026-10-09

Version 0.1.0-m1-scrape (versionCode 3) adds real provider-backed scraping to the installed RP5 app.
M0 remains passed; M1 remains in progress. Custom image import is the optional fallback.

## Implemented

- Y / Scrape opens one-title, GBA-collection or all-current-games scope. The latter two currently
  cover the same three GBA slots. ROM identities are deduplicated in a stable in-memory review queue.
- Real RetroBase HTTPS lookup by SHA-1/size/platform, then title search, ranked alternative records,
  corrected-title search, per-title errors, progress, skip and retry. No account, secret or ROM upload.
- Real artwork thumbnails with category and region. Flat support textures enter the existing
  Filament cartridge preview with Fit/Crop/rotation and explicit Apply; boxes/screenshots/logos are
  additional media. Other cartridges keep their independent labels.
- Independently checked metadata fields and variants; a label from one match can coexist with a
  year/developer/description or other media from another. Unchecked fields are preserved.
- Room v1→v2 migration retains slots, ROMs, focus and labels. Accepted choices record their source,
  provider record, region, license status and timestamp. Images are immutable SHA-256-addressed
  originals. Accepted artwork and metadata survive offline relaunch.
- Bounded HTTPS responses, dimensions and candidate cache; one-hour candidate TTL, sequential API
  calls respecting the published anonymous quota. Cancel never accepts a draft or launches a game.

## Changed files

`scraping/ArtworkProvider.kt`, `RetroBaseProvider.kt`, `ScrapeCoordinator.kt` and `ScrapePanel.kt` implement
the provider/review UI. `library/AcceptedArtwork.kt`, `LibraryJson.kt`, `CollectionRepository.kt`,
`LibraryDatabase.kt`, `RoomCollectionRepository.kt` and `app/schemas/.../2.json` implement accepted
selections and migration. `storage/LabelStore.kt` shares bounded stream import between SAF and remote
images. MainActivity/ControllerRouter add the scrape route and retain local import on Select.

AndroidManifest adds the normal INTERNET permission; build configuration identifies the increment.
DebugCollectionActivity, ScrapeQueueTest, ProviderContractTest, ScrapeUiTest, ProviderLiveTest and
Test-Device.ps1 extend verification. README, product/architecture/roadmap/testing/license notes,
LABEL_SCRAPING, PROVIDER_RESEARCH, M1_PROGRESS and ADR 0005 document the actual boundaries.

## Validation

Both APKs compile. Thirteen host unit tests pass, four GLB structural checks pass, and lint reports
zero errors (20 existing warnings). The final deterministic RP5 suite passes six tests in 15.824 s,
including three native handoffs and the ordinary collection/session regressions. The Scrape suite
passes its three tests: parser contract, actual v1 database
migration/reopening, and ordinary UI mixed-source selection/3D preview/persistence. Its network
fixtures use original artwork; Room, Filament and the production UI run on the real RP5.

The separately opted-in live-provider test passed on RP5 in 1.851 s using a fresh cache, querying real GBA records and
regional flat-label URLs. It downloads no commercial artwork. The owner separately confirmed real
remote thumbnails, label application and persistence after reopening. Full regression results and
outstanding offline/failure checks are recorded in TESTING. The initial migration fixture incorrectly
required an indices array on every table;
it now follows the actual exported v1 schema and the device test passes.

## Limits and next task

Three slots, one accepted label and one additional asset per category per game; no directory scanner,
disc model/core, scrape-pack adapter, cross-provider chooser or durable batch job. Queue state disappears
on Activity/process recreation; accepted choices persist. No accepted-version undo or automatic
fill-missing mode. Explicit reapplication replaces only the selected field/category. Provider outages,
missing labels and the pending data license are real limitations; Socket's exact provider is unverified.
Cancel may wait for a bounded socket timeout to finish the network read. Orphan original cleanup,
source-checksum verification, orientation/language polish and a details/media viewer remain future work.

Next: expand Room/SAF indexing beyond three slots while keeping this reviewed scrape pipeline; add
durable scoped jobs and region/language preferences. Continue RP5 offline/failure/cancel tests, SaveRAM
continuity, sustained audio/frame-pacing/memory and suspend/controller-disconnection checks. The GBA
model/receiving slot still needs M1 visual work; this increment makes no production-quality or 60 FPS claim.
