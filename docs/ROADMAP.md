# Roadmap and gates

| Milestone | Acceptance gate | Status |
| --- | --- | --- |
| M0 — Feasibility | APK builds; genuine GLB renders; physical controller changes state; real GBA core frames/audio; repeatable 3D↔emulator handoff | Passed on RP5, 2026-10-09; see TESTING for precise scope |
| M1 — First loop | Persistent small GBA library, convincing template/carousel, insertion, internal play, pause/save/load/ejection, lifecycle recovery; prove local per-title label assignment on shared mesh | In progress: persistent slots/labels, coordinator and ordinary lifecycle tests pass on RP5; final model/slot and longer validation pending |
| M2 — Refinement | Polished mesh/PBR/lighting/motion, measured frame pacing and reduced motion | Planned |
| M3 — Library | SAF directories, indexing, duplicates, details/favorites; scrape-pack import and single-title/console/all-library scraping with independent candidate selection | Brought-forward GBA slice: nested SAF imports, growth/deduplication, reviewed API choices and durable batch resume implemented; incremental indexing/subsets/preferences planned |
| M4 — Layouts | Horizontal/vertical/grid reuse one data/selection/rendering system | Planned |
| M5 — Consoles | Add one audited core/media format at a time, verified internally | Planned |
| M6 — Environment | Optional HOME, onboarding, sleep/resume, backup and long-term stability | Planned |

**Next task:** profile larger collections and harden directory/save/suspend reliability. The owner
confirmed saved-batch resume works but called review tedious, and explicitly deferred its UX redesign
to a later version. That pass should add selected-title scopes and region/language ranking. SAF imports now
expand the collection, and saved scrape batches resume without repeating completed searches. See
LIBRARY_PROGRESS. Legacy duplicates are collapsed by hash; Remove/Undo preserves data and Rescan exclusions.
Real RetroBase search and independent flat-label/media/metadata choices work.
Socket's precise backend is unverified; the inspected community Slot fork generates composite labels.
Then finish M1's asset/insertion proof:
polish the GBA template, establish a subtle receiving slot and verify asymmetric label UVs. Validate SaveRAM continuity using
an original diagnostic that writes SRAM and profile longer collection/gameplay runs. Background/recreation
tests now pass for stop-and-return behaviour; seamless suspend restoration remains later work.
The GBA online/directory/resume slices are brought forward; the complete provider/library workflow remains M3.
Short handoff tests do not establish a memory plateau.

Label/media/metadata selection is a product priority, not an optional cosmetic extra. Implement the
per-game texture binding and local image import in M1 (proved), then extend the working provider proof
with scrape-pack import and scalable library enrichment in M3. Reuse the chooser/batch pipeline for optical-disc labels when their templates and cores
are introduced in M5; do not claim CD emulation is supported by the initial GBA milestone.
