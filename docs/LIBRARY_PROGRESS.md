# Larger collection and resumable scraping — 2026-10-09

Version 0.1.0-m1-library (versionCode 5) continues the validated embedded-play/API-label workflow.
M0 remains passed and M1 remains in progress; final model/lighting/slot and sustained validation are pending.

## Implemented

- Start / Folder opens the standard Android tree picker. Read grants and multiple directory URIs
  persist. Nested GBA files import through content URIs; no broad storage permission or source writes.
- An ordered collection grows beyond three games. Folder imports append distinct content hashes;
  X / Add .gba adds or selects an existing entry. Identical hashes deduplicate, with accepted labels,
  metadata and saves retained. Empty collections show one placeholder, not three apparent copies.
- Startup collapses legacy repeated assignments by ROM hash, preserving order and focused identity.
  B / Collection opens Remove selected entry and Undo last removal. Removal keeps originals, private
  ROM copies, saves and enrichment. Persisted exclusions survive reopening and Rescan. Undo restores
  the prior position in the current Activity; Add .gba restores a removed game after reopening.
- Rescan reads all remembered roots. Progress includes added/duplicate/failure counts and a concise
  failure. B / Cancel scan keeps committed imports; invalid files do not abort valid siblings.
- Only three real GLB instances and up to six decoded label recipes remain per viewport. Global
  selection/spring state drives a moving visible window. GPU acknowledgements include the window
  offset, preserving correct Fit/Crop/rotation application after the third game.
- Room v3 adds remembered directories; v4 adds removal exclusions. Explicit v1/v2/v3 migrations preserve focused game identity,
  labels, recipes, scraped provenance, metadata and additional media; no library reset.
- One durable scrape manifest plus candidate files retain targets, corrected queries, completion/error
  state and review position. Only current candidates remain in memory. Cancel/reopen/Resume searches
  pending titles without repeating completed ones. Applied choices remain separate in Room.
- Cold launch browses cached data without fetching. Y → Resume saved batch explicitly continues
  network work. A new scope replaces saved search progress, with a visible explanation.

## Files and decisions

CollectionRepository/RoomCollectionRepository, LibraryDatabase and schemas 3/4 implement collection growth,
saved roots, duplicate cleanup and reversible removal. DirectoryScanner and RomImporter implement read-only, cancellable bounded imports.
CollectionCoordinator/MainActivity/ControllerRouter connect folder/add/rescan/cancel actions and variable
selection. CartridgeSurface reuses its three instances; the existing texture cache stays bounded.

ScrapeJournal, FileScrapeJournal, ScrapeCoordinator and ScrapePanel add durable reviewed queues and paging
of candidates from private files. DebugCollectionActivity/FixtureTreeProvider, LibraryGrowthTest,
ScrapeResumeTest, ProviderContractTest, CoordinatorTest, ScrapeQueueTest and Test-Device.ps1 extend tests.
Build metadata, README, architecture/product/roadmap/testing/artwork notes and ADR 0006 record the behavior.

No dependency versions changed. DocumentsContract, DocumentsProvider and AtomicFile were checked
against current Android reference documentation. The debug DocumentsProvider must be exported and
protected by MANAGE_DOCUMENTS; it has no picker discovery filter and is absent from release builds.
The production app requests no MANAGE_DOCUMENTS or broad-storage permission.

## Verification and limits

Both APK builds, 17 host tests and lint pass. Four structural GLB checks pass. RP5 tests prove six
distinct games from a nested fixture tree, duplicate/error handling, repeated rescan, actual blue GPU
label pixels on game six, focus/label reopening and real internal gameplay/ejection on that game.
Durable queue tests cancel during the second search, recreate the Activity, continue only pending
searches, reload candidates at the next review index and preserve independently applied metadata.
Fixtures are original; only picker/grant results and scraper responses are substituted. Native renderer,
emulator, ContentResolver, Room and on-disk journal execute on the real RP5. TESTING records run results.

Before the removal fix, nine deterministic RP5 tests passed with 50 native handoffs in 41.485 s,
then three handoffs in 23.428 s. The owner confirmed folder import and saved batch resume, but found
three legacy copies and called the current scraper review flow tedious. Version 5 addresses duplication
and removal; the final device result is recorded in TESTING. Lint has zero errors/21 warnings.
Ten deterministic RP5 tests passed in 24.810 s; the final journal reset guard passed a focused two-test
library/resume rerun in 9.379 s. The updated production application is installed and open.
No new dependencies; no physical device-reboot/process-kill result is claimed.

Current limitations: GBA only; no ZIP archives, directory-removal UI, incremental mtime index or source
availability model. Rescan rehashes/copies bounded files and requires enough private storage. Cached
games are retained when source files disappear; no automatic removal. Scans are bounded to 10,000
entries and 16 levels, with truthful errors; registration survives cancellation but scan cursors do not.
One saved scrape batch, no automatic acceptance, selected-subset UI, accepted-artwork undo, region/language preferences
or unattended/background scheduler. Unapplied previews/checkbox drafts do not survive recreation.
Journal disk use grows with the current batch; individual manifest/candidate files are capped at 8 MiB.
Corrupt journal data reports an error and permits a new scope; accepted Room data is unaffected.

Next engineering task: profile larger collections and validate directory/save/suspend reliability.
The owner explicitly defers redesigning the tedious scraper review flow to a later version; that pass
should add controller-friendly selected-title scopes and region/language ranking. Validate picker grants after
device reboot, revoked/missing directories, disk-full/cancel/process-kill cases and long-term frame/audio/
memory/suspend behavior. Socket's exact artwork backend and provider distribution terms remain unresolved.
