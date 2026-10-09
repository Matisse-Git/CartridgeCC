# M3 scraper review increment — 2026-10-09

Version 0.3.1-m3-scrape-review (versionCode 9), continuing the owner's primary API-label workflow.
M0 remains passed. Full M3 acceptance is not claimed: scrape-pack import and broader physical-library
validation remain open, alongside M1/M2 sustained lifecycle/performance/artistic gates.

## Implemented

- Y / Scrape supports This title, all current GBA games, manually selected games and games missing
  accepted labels. Pick with A; X selects all/clears. Canonical content hashes deduplicate targets;
  collection filters do not silently shrink whole-library scopes. GBA/all are identical while GBA
  is the only supported system. A saved batch is written before network work.
- Search the current title first. Choose its match immediately; Next fetches only the next pending
  title. Completed candidates remain durable across cancellation/recreation; accepted data stays in
  Room. No implicit network resume, no batch-wide candidate RAM growth, no bulk silent acceptance.
- Persistent label/title-region and description-language preferences. Stable ranking retains every
  alternative and uses actual provider tags; unknown tags keep original order. Defaults US/English;
  optional Any preserves source ordering. Preferences do not change accepted choices or hide regions.
- Compact match review: preferred flat-label thumbnail/3D preview, Fill missing metadata, Next and
  access to all media, individual metadata fields/variants and other title matches. Existing values
  display as kept; explicit replacements show the old field. Preferred label never replaces an
  accepted one. Fill missing merges atomically against current accepted values, not stale journal data.
- One persistent Undo per game for the last changed label/metadata/media operation, available in review
  and Details. It restores previous enrichment only; ROM/source, saves, membership, favorites, history
  and query/focus remain independent. No-op writes preserve the undo point; applying another change
  replaces it. It is not multi-step history or redo. Accepted originals remain available for restoration.
- Filtered-out batch targets preview on the real shared GLB with a transient view projection. Apply,
  cancel and recreation retain the original saved query/focus. Opaque scraper pages pause 3D rendering;
  preview resumes it. Embedded emulation/native surfaces are unchanged.
- Room schema 6 adds enrichment_undo through explicit 5→6 migration, joining the existing chain.
  Journal format 2 adds outcome and candidate tags, retaining version-1 compatibility.

## Files and decisions

ScrapePreferences / SharedScrapePreferences, ArtworkProvider / RetroBaseProvider, ScrapeCoordinator /
ScrapePanel and FileScrapeJournal / ScrapeJournal define the selection, ranking and review workflow.
CollectionRepository / RoomCollectionRepository / LibraryDatabase / schema 6 store atomic merge/undo.
CollectionCoordinator, MainActivity, LibraryPanels and DebugCollectionActivity connect real preview,
filtered view restoration, Details undo and isolated preferences. Host queue/preferences/coordinator
and device contract/resume/review tests cover these guarantees; Test-Device adds a Review suite.
README, AGENTS, product/architecture/roadmap/licensing/testing and ADR 0010 describe the increment.
All dependency versions, native core/saves and production meshes/materials remain unchanged.

## Verification

Both APKs build; 31 host tests and 8 asset checks pass. Lint: 0 errors, 24 warnings, no broad
suppressions. The extra warning recommends a KTX preferences-edit style; the explicit synchronous
commit runs on IO and checks its failure result. The focused Review suite passed 4 RP5 tests in
7.968 seconds. The final full suite passed 19 tests in 105.499 seconds (`-Cycles 3`); a prior 50-handoff
assertion passed, with one unrelated old UI test subsequently corrected to scroll to its action. Fresh
real RetroBase metadata/URL probing passed in 1.882 seconds without downloading artwork. Version 9 is
installed and MainActivity launched; precise results are in TESTING.

Host tests prove stable regional/language ranking without dropped alternatives, legacy untagged order,
selected-ID deduplication/publish-before-request, lazy fetching, resume against freshly accepted values,
manual protection/no-op filling and filtered preview apply/cancel/recreation restoring persisted view.
RP5 tests use production Room and Filament with original fixture artwork, verify persistent Japan/French
preferences, selected hidden target, real GPU preview, recreation, accepted manual year protection,
preferred French description, persistent undo and a single search despite repeated reopening. Schema
5→6 reopening proves undo cannot rewind saves, moved source URI, favorite/history/query. Journal v1 and
untagged candidate compatibility plus tagged/outcome round-trip are checked on Android. Resume cancels
while the second request waits; the first search/accepted metadata survive without repetition.

Initial host failures exposed tests using real IO outside the coroutine scheduler; inject the IO
dispatcher for deterministic scheduling while production defaults to IO. Memory journal now mirrors
File journal's empty-batch semantics. The first new UI test omitted opening the intentionally closed
Tools shelf; corrected to exercise the production shelf. The old independent-media test now scrolls
to Other title matches in the new review list. Behavior assertions remain intact.

## Boundaries / next task

GBA only. Search still requires explicit title-match review; this does not claim one-click unattended
scraping, additional providers, selected-console scopes or downloaded disc templates. A batch stores
one review position; unapplied checkbox/crop drafts are discarded on recreation. Applied/skipped counts
track whether an operation was accepted, not current coverage; undo does not reinterpret prior counts.
Undo retains one snapshot per game and can increase retained originals; reference-aware garbage
collection is pending. Disk-full/network/error cases stay visible, but destructive power-loss/storage
failure injection and very large batches need physical testing. Provider coverage/terms may change.

Next: define a versioned, bounded scrape-pack format with a review-before-apply import and provenance,
using the same independent choices/undo. Before calling M3 complete, review controller ergonomics with
real titles, reboot/revoked SAF grants and large-library storage/input profiling. Long sleep, audio,
thermal/battery and memory validation remain open. No public release until existing GPL/source and
provider/rightsholder distribution obligations are resolved.

Provider references rechecked on 2026-10-09: [public OpenAPI](https://retrobase-collection.com/api/public/v1/openapi.json)
and [developer documentation](https://retrobase-collection.com/developers). Actual records contain optional
regional names and synopsis-language maps; missing fields remain missing and coverage varies by title.
No Socket backend/artwork equivalence is inferred.
