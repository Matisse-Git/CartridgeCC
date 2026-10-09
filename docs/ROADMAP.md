# Roadmap and acceptance gates

The development implementation has reached M6. Each increment is independently buildable and uses real native surfaces and embedded emulation. This is not a public-release or universal game-compatibility claim. See the dated results in TESTING, M6_PROGRESS and CONSOLE_RELIABILITY for the final validation run.

| Milestone | Implemented acceptance scope | Evidence / remaining review |
| --- | --- | --- |
| M0 — Feasibility | Native arm64 APK; genuine GLB; physical controller state; embedded GBA frames/audio; repeated renderer handoff | Passed on RP5, 2026-10-09; owner navigation/gameplay confirmation and native pixel/state tests |
| M1 — First loop | Persistent local GBA collection, physical carousel/insertion, internal play, custom pause/state/battery/eject loop | Implemented and RP5 verified; completed-session sleep/recovery added in M6. Broader real-title/audio/latency coverage remains |
| M2 — Refinement | Original refined GBA PBR mesh, studio IBL, receiving slot, phased motion, optional contact sound, reduced motion | Implemented and bounded 60 Hz presentation profiled. Owner/art direction review and broader thermal/battery profiling remain |
| M3 — Library | Multiple SAF roots, incremental/full-hash scans, SHA deduplication, preserved removal, search/favorites/sort/details/history, metadata editing/source recovery; API title/console/all/selected scraping, independent candidate choices, preferences/undo and offline packs | Implemented and isolated Room/SAF/provider/UI tests pass. Provider coverage/rights and large distinct-artwork/storage-provider failure coverage remain |
| M4 — Layouts | Horizontal, vertical and nine-object grid share the same data, selection, input and bounded renderer pool | RP5 layout/focus/pixel/handoff tests and per-layout presentation profiles pass |
| M5 — Additional consoles | Audited source-built mGBA adds GB/GBC, explicit identities, tall GLBs, matching labels/scrape routing, battery and MBC3 clock handling | Native GB/GBC frame/input/state/battery/RTC tests pass. Other consoles, discs and unusual peripherals are future audited increments |
| M6 — Console environment | First-run setup, independent controller/core/preferences, optional HOME candidate/Android chooser, completed session sleep/cold/process recovery, frozen native pause frames and reviewed ROM-free backup/restore | Features implemented; focused RP5 tests pass. Final sustained/reboot/full-regression results are in CONSOLE_RELIABILITY. Default-HOME selection is the owner's Android choice |

Public-release gates: choose a GPL-compatible distribution/source plan and audit the complete dependency BOM; resolve provider/data/image redistribution terms; retain source/notice/relinking materials. No public release has been made. Registering a HOME candidate never chooses or replaces the owner's default.

Manual hardware coverage: owner visual/motion/input review; choosing and reverting HOME; multi-hour mixed GBA/GB/GBC sessions and unplugged battery/heat/audio/latency measurements; external controller removal/reconnection; revoked SAF grants and storage-full/provider-write failures; hundreds of distinct label textures. Automated diagnostic ROMs contain original code/imagery and are generated outside Git/APK assets.

Label/media/metadata scraping remains the primary enrichment workflow. Local image import is an optional fallback. Exact Socket artwork/backend parity is unverified; box art is not silently used as a flat label. Optical-disc label templates and internal disc-system integration follow as a separate audited console increment, using the existing candidate/review infrastructure.

Recommended next engineering work: complete owner art/input review and a longer mixed-title thermal/memory/audio run, then select one disc-capable core/media format for a separately licensed and verified increment. Do not reopen finished subsystems or add several cores at once.
