# M3 library management — 2026-10-09

Version 0.3.0-m3-library (versionCode 8). The owner explicitly requested M3; M1 sustained lifecycle and
M2 owner/artistic/sustained profiling gates remain open. This builds on working GBA imports/scraping,
without adding consoles/layouts or rewriting the native renderer/emulator.

## Implemented

- Persistent favorites (L1), import/title/recent/most-played sort, favorites filter and word search
  across title/developer/genre. Canonical membership/hash/order and all-library scraping stay separate.
  Focus remains attached to identity; empty results display "No matching games". Add exposes/focuses
  an existing/new game by clearing active favorites/search filters. Removal/Undo/exclusions remain intact.
- R1 details: optional metadata, running playtime, last played, battery/state dates, cached readiness,
  last-scan source status and accepted label/media/metadata provenance. Actions work by controller.
- Local metadata editing: ordinary Android text entry, only changed fields replaced, local provenance,
  blank fields cleared and blank title falls back to filename. Scraped choices are retained otherwise.
- Folder/search panel manages multiple remembered roots, automatic scan on open (persisted opt-out),
  incremental rescans, Verify all and forgetting roots while keeping membership/media/caches/saves.
- SAF root/document size/mtime/hash index. Positive matching stamps plus a present matching-sized
  private ROM skip reimport; unknown stamps/missing cache rehash. Full verification always hashes.
  Completed scans mark missing indexed originals; incomplete/cancelled traversals cannot infer removal.
  Missing originals remain playable from cache. Selecting a revoked root again restores its grant.
- Locate same ROM accepts only the same content hash, repairing a source/copy and preserving choices.
  Picker add/recovery flags survive Activity recreation; returned ROMs wait for library readiness.
- Monotonic Running-only history with 15-second checkpoints, pause/eject/background flushes and
  per-game retry of failed writes. Warnings remain visible; failed time cannot transfer to another game.
- Room v5 with non-destructive explicit migration chain. Opaque library panels pause the 3D render loop.
  Existing internal gameplay, saves, API candidate/label/media/metadata selection and batch resume remain.

## Files and decisions

LibraryQuery.kt/PlayClock, LibraryPanels.kt, CollectionRepository, LibraryDatabase/schema 5 and
RoomCollectionRepository define projection/preferences, metadata/facts/history and index storage.
DirectoryScanner, CollectionCoordinator, MainActivity and ControllerRouter connect indexing, identity
focus, optional panels, controller routing and picker recovery. DebugCollectionActivity and the
read-only FixtureTreeProvider retain isolated test databases and original fixtures.
LibraryQueryTest, CoordinatorTest, LibraryManagementTest, migration tests and Test-Device.ps1 cover
the new behavior. README/AGENTS/product/architecture/roadmap/testing/license notes and ADR 0009 align.
No dependency versions or production native/3D assets changed.

## Verification

Final source: both APKs build, **27 host tests** and **8 asset checks** pass. Lint reports **0 errors,
23 warnings**, with no broad suppressions; the added warning is URI KTX style. The focused two-test
RP5 management suite passed in 8.046 seconds before final picker/history hardening. The final full
suite passed **16 tests / 50 native handoffs in 119.638 seconds** on RP5 Android 13 firmware
RP5_V1.0.0.189_20251128_174732_user. Version 8 is installed and launched.

Tests prove controller details/favorites, metadata edits, empty-search/preferences persistence, real
internal play/history, v4→v5 migration, incremental zero-read rescans, full verification, incomplete
scan safety, missing-cache recovery, changed-document identity, multiple roots and forget retention.
Existing tests prove v1/v2/v3 upgrades through v5, independent labels/API metadata/media, saved-batch
resume, removal/Undo, battery/state saves, failed-ROM recovery and exclusive native surface handoff.
Host failure injection proves history retry remains assigned to the correct hash across sessions.

Initial host fixture assertions counted old empty slots; fixtures now reflect compact membership and
retain navigation/removal assertions. The first RP5 test used a zero-duration D-pad press; navigation
is sampled on display frames, so it now holds through a real frame before key-up. No implementation
was mocked or assertion/performance threshold weakened. Production Room, SAF provider traversal,
Filament and embedded mGBA execute; only system-picker/grant returns and provider responses use
isolated original fixtures. No owner library data is modified by the test databases.

The existing original-artwork 36-second viewport regression measured presentation p95
16.726–16.732 ms and CPU submission p95 1.411 ms, with no presentation gaps above 25 ms in sampled
phases. This does not establish sustained performance or large-library scaling. Repro commands and
the precise fixtures/results are in TESTING.

## Boundaries and next task

GBA/uncompressed ROMs only. Details source status describes the last scans, not continuous existence.
Same-size/time mutations require Verify all; unknown stamps never skip. Directory scan cursors and
pending history/drafts are not durable across process death. Large-library projection currently runs
in the coordinator; thousand-title responsiveness and private-storage usage need physical profiling.
No automatic metadata/label replacement, background scheduler, scrape-pack format, selected-subset
scope, region preferences, accepted-artwork undo or new scraper UX is claimed. The existing provider
single/GBA/all scopes, independent candidates and durable batch resume remain the primary artwork path.
The owner had deferred the tedious review-flow redesign; these extensions remain open M3 enrichment work.

Next: owner/controller review with a real folder, revoked/reboot grants and a large collection, then
the requested simpler scraper review/subset/preferences workflow before M4/M5. Longer sleep/reboot,
audio/thermal/battery/memory/input-latency profiling remains shared with M1/M2.
