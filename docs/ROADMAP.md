# Roadmap and gates

| Milestone | Acceptance gate | Status |
| --- | --- | --- |
| M0 — Feasibility | APK builds; genuine GLB renders; physical controller changes state; real GBA core frames/audio; repeatable 3D↔emulator handoff | Passed on RP5, 2026-10-09; see TESTING for precise scope |
| M1 — First loop | Persistent small GBA library, convincing template/carousel, insertion, internal play, pause/save/load/ejection, lifecycle recovery; prove local per-title label assignment on shared mesh | Partially scoped infrastructure exists; milestone not started/accepted |
| M2 — Refinement | Polished mesh/PBR/lighting/motion, measured frame pacing and reduced motion | Planned |
| M3 — Library | Room + SAF directories, indexing, duplicates, details/favorites; local scraped-label import and single-title/console/all-library scraping with independent candidate selection | Required; planned |
| M4 — Layouts | Horizontal/vertical/grid reuse one data/selection/rendering system | Planned |
| M5 — Consoles | Add one audited core/media format at a time, verified internally | Planned |
| M6 — Environment | Optional HOME, onboarding, sleep/resume, backup and long-term stability | Planned |

**Next task:** begin M1 by extracting the shell coordinator and proving safe background/suspend/recreation
and error recovery through ordinary UI tests. Add persistent small-library slots and independent local
label texture assignment/3D preview next. Profile longer runs: the short handoff test does not establish
frame pacing, battery behaviour or a memory plateau. Keep the complete provider scraper in M3.

Label/media/metadata selection is a product priority, not an optional cosmetic extra. Implement the
per-game texture binding proof in M1, then build local import and provider enrichment on the persistent
library in M3. Reuse the chooser/batch pipeline for optical-disc labels when their templates and cores
are introduced in M5; do not claim CD emulation is supported by the initial GBA milestone.
