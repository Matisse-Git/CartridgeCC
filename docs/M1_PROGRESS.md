# M1 progress — 2026-10-09

M0 remains passed. This first M1 increment is implemented and installed on the physical RP5; M1 is
not yet accepted as a complete product milestone. API/database label scraping is the owner's main
enrichment goal. Local image import is the foundation and optional fallback. A second increment now
implements real API scraping; see SCRAPING_REPORT for files, choices, tests and remaining scope.

## Implemented

- Room-persisted three-slot GBA collection, original source URIs, content hashes and selected focus.
- Android-free coordinator serializes launch, save/load, pause/resume, cleanup and recovery. Background
  cancels insertion or waits for a save, releases the native session and returns to the collection.
- 540 ms lift/alignment/slide and 420 ms reverse motion, bypassed by reduced motion.
- SAF local label import, real 3D Fit/Crop/90° rotation, Apply/Cancel/reset, independent label materials
  on one shared mesh. Originals, source URI and accepted recipes persist. Identical-ROM reimport keeps
  the label. Decoding/upload errors are visible; an unrendered draft cannot be accepted.
- Controller game/image and D-pad/A/B preview actions use the same state as touch. The second increment
  changes Y to primary Scrape and Select to optional local Image import.

## Files

MainActivity and ControllerRouter integrate the workflow. CollectionRepository, LibraryDatabase and
RoomCollectionRepository define/persist it; app/schemas records version 1. CollectionCoordinator owns
session decisions. LabelStore derives image textures, CartridgeLabels owns independent GPU resources,
and CartridgeSurface applies them and insertion motion. Build scripts pin Room/KSP and test dependencies.
CoordinatorTest, CollectionUiTest, debug-only DebugCollectionActivity/FixtureDocumentProvider and
Test-Device.ps1 extend verification. README, PRODUCT, ARCHITECTURE, ROADMAP, ASSET_PIPELINE, TESTING,
LICENSING, TECHNICAL_PLAN, LABEL_SCRAPING and ADR 0004 describe the actual state.

## First increment validation

Build APKs and unit/lint checks pass; 11 unit tests and four structural asset checks pass. On the RP5,
all three instrumentation tests passed in 30.628 s with 50 real Filament/mGBA handoffs. Ordinary UI
tests cover save/load/resume/eject, backgrounding, Activity recreation, insertion cancellation/retry,
red/blue independent GPU label pixels, preview cancellation, invalid images, identical-ROM reimport,
accepted recipes and closing/reopening the Room database. The owner confirmed controller label
placement, Fit/Crop/rotation, application and relaunch persistence all work.

Test picker results are stubbed; Android ContentResolver/Room/Filament/mGBA are real. The production
SAF picker was tested manually by the owner. Fixtures use only original diagnostic ROMs and original
images, separate test databases and private test caches. The owner's library is not overwritten.
See TESTING for commands, lint warnings, fixture scope and outstanding physical checks.

Two failures were corrected before acceptance: runtime mipmaps needed Filament's explicit usage flag;
PixelCopy polling needed Compose test-clock synchronization after a selection update. Neither was
replaced with mocked rendering. No RP5 60 FPS or long-term memory-stability claim is made.

## Open M1 work

The cartridge is still faceted engineering geometry, without final plastic normal/roughness textures,
studio cubemap or a receiving slot. Motion needs physical polish. Only three slots, one state slot,
and a label per game are supported; directory indexing, disc templates/cores and durable large-library
scraping are not present. The second increment adds real API choices and independent media/metadata.
Backgrounding ends gameplay; seamless state restoration and periodic
SaveRAM checkpoints are deferred. Force-stop cannot guarantee a final save. Validate a diagnostic that
writes SRAM, asymmetric artwork orientation, held inputs/disconnection, sleep, sustained frame pacing,
audio timing, memory and battery on RP5.

The owner requested immediate priority for database/API scraping and Socket/Slot-style labels after
confirming the local workflow. The second increment meets the initial API proof with genuine provider
candidates and accepted provenance. Full scraping scope remains incremental work, not completed by
custom-image import. See SCRAPING_REPORT, ADR 0005 and TESTING for current results.
