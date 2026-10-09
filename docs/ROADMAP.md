# Roadmap and gates

| Milestone | Acceptance gate | Status |
| --- | --- | --- |
| M0 — Feasibility | APK builds; genuine GLB renders; physical controller changes state; real GBA core frames/audio; repeatable 3D↔emulator handoff | Passed on RP5, 2026-10-09; see TESTING for precise scope |
| M1 — First loop | Persistent small GBA library, convincing template/carousel, insertion, internal play, pause/save/load/ejection, lifecycle recovery; prove local per-title label assignment on shared mesh | Functionality implemented, media refined in M2; longer stability/sleep/reboot validation remains open |
| M2 — Refinement | Polished mesh/PBR/lighting/motion, measured frame pacing and reduced motion | Implemented increment; owner review/sustained validation open: refined GLB/PBR, original studio IBL, receiving slot, phased motion, optional sound and full reduced motion; physical results in M2_PROGRESS |
| M3 — Library | SAF directories, indexing, duplicates, details/favorites; scrape-pack import and single-title/console/all-library scraping with independent candidate selection | Active by owner request: directories/automatic incremental scans, details/local metadata, persistent favorites/sort/search and Running-only history implemented. Selected/missing-label scopes, saved region/language ranking, current-title-first review, fill-missing protection and persistent enrichment undo implemented; offline reviewed scrape packs implemented; large-library/grant/storage-failure validation remains open |
| M4 — Layouts | Horizontal/vertical/grid reuse one data/selection/rendering system | Planned |
| M5 — Consoles | Add one audited core/media format at a time, verified internally | Planned |
| M6 — Environment | Optional HOME, onboarding, sleep/resume, backup and long-term stability | Planned |

**Active task:** M4 alternate layouts, following the verified M3 functional increments. Keep M1 longer stability/sleep/reboot and M2 artistic/sustained
profiling gates open. See M3_PROGRESS and ADR 0009 for identity-preserving views, incremental
indexing, metadata/details and history. M3_SCRAPE_REVIEW and ADR 0010 cover the faster current-title
review, selected scopes, saved preferences, protected fill-missing and undo. The earlier deferred UX
feedback is addressed by this increment; physical owner review remains open; offline packs are implemented in SCRAPE_PACK_FORMAT. SAF imports now
expand the collection, and saved scrape batches resume without repeating completed searches. See
LIBRARY_PROGRESS. Legacy duplicates are collapsed by hash; Remove/Undo preserves data and Rescan exclusions.
Real RetroBase search and independent flat-label/media/metadata choices work.
Socket's precise backend is unverified; the inspected community Slot fork generates composite labels.
The M2 increment brings forward M1's refined GBA template and receiving slot; asymmetric label UVs
are checked on the real GPU. Battery-save
boot restoration and checkpoints now pass an original SRAM diagnostic on RP5 (SAVE_RELIABILITY).
Profile longer collection/gameplay runs and validate real sleep/reboot/process kill. Background/recreation
tests now pass for stop-and-return behaviour; seamless suspend restoration remains later work.
The GBA online/directory/resume slices are brought forward; the complete provider/library workflow remains M3.
Short handoff tests do not establish a memory plateau.

Label/media/metadata selection is a product priority, not an optional cosmetic extra. Implement the
per-game texture binding and local image import in M1 (proved), then extend the working provider proof
with scrape-pack import and scalable library enrichment in M3. Reuse the chooser/batch pipeline for optical-disc labels when their templates and cores
are introduced in M5; do not claim CD emulation is supported by the initial GBA milestone.
