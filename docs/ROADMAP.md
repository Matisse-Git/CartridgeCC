# Roadmap and gates

| Milestone | Acceptance gate | Status |
| --- | --- | --- |
| M0 — Feasibility | APK builds; genuine GLB renders; physical controller changes state; real GBA core frames/audio; repeatable 3D↔emulator handoff | Build/host checks pass; device gates unverified |
| M1 — First loop | Persistent small GBA library, convincing template/carousel, insertion, internal play, pause/save/load/ejection, lifecycle recovery | Partially scoped infrastructure exists; milestone not started/accepted |
| M2 — Refinement | Polished mesh/PBR/lighting/motion, measured frame pacing and reduced motion | Planned |
| M3 — Library | Room + SAF directories, indexing, duplicates, metadata/artwork/details/favorites | Planned |
| M4 — Layouts | Horizontal/vertical/grid reuse one data/selection/rendering system | Planned |
| M5 — Consoles | Add one audited core/media format at a time, verified internally | Planned |
| M6 — Environment | Optional HOME, onboarding, sleep/resume, backup and long-term stability | Planned |

**Next task:** connect the physical RP5 and run the debug surface-handoff instrumentation fixture and
manual controller/audio checks. Fix any lifecycle, ABI or surface-order failure before asset polish.
Then improve suspend/context-loss recovery and factor the shell coordinator for M1 tests.
