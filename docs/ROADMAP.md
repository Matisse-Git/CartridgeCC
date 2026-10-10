# Roadmap and acceptance gates

Console feel (2026-10-10, ADR 0020): browsing no longer re-reads the library per step (60 fps held
with 3,000 games on the RP5, where 800 dropped frames before); games start and end behind a closed
screen with Fade, Iris or Shutter and no loading text; the receiving slot is gone; a shelf opens on
its first game; the right stick turns the focused object; focus is always visible. Host checks and
the 43-test RP5 regression pass (TESTING). Left for the owner's hands: the real library with Show
frame rate on, a commercial game's opening frames, and the feel of the stick.

Media models and browsing (2026-10-10, ADR 0019): the cartridge, tall cartridge and disc are now
converted from three models the owner downloaded, and a game keeps one pooled 3D object while it
is near the screen, which removed the label flicker on shelves of more than three games. No
change to emulation, the database schema, save formats or the interface. Host checks and the
38-test RP5 regression pass (TESTING). Left for the owner's hands: the look of the new models
under the studio light, and browsing the real library with scraped artwork.

Console-experience redesign (2026-10-10): the interface was rebuilt around console shelves, a
system menu, goal-based settings with console and per-game scope, and a guided import, without
changing emulation, assets, the database schema or save formats. Automated and RP5 verification
passed; the owner's hands-on review is listed in [ux/IMPLEMENTATION_STATUS](ux/IMPLEMENTATION_STATUS.md).
The next increments it points to are multi-disc import with in-game disc change, then analog pads.

Post-M6 system increment: PS1 is implemented with source-built PCSX ReARMed and a shared disc template.
See PS1_PROGRESS and ADR 0017. Initial CUE/BIN/ISO/CHD native diagnostic tests pass; commercial-title,
BIOS-backed and long-duration compatibility remain physical gates. Multi-disc and analog-required
games are separate future increments, not completed by this first PS1 integration.

The development implementation has reached M6. Each increment is independently buildable and uses real native surfaces and embedded emulation. This is not a public-release or universal game-compatibility claim. See the dated results in TESTING, M6_PROGRESS and CONSOLE_RELIABILITY for the final validation run.

| Milestone | Implemented acceptance scope | Evidence / remaining review |
| --- | --- | --- |
| M0 — Feasibility | Native arm64 APK; genuine GLB; physical controller state; embedded GBA frames/audio; repeated renderer handoff | Passed on RP5, 2026-10-09; owner navigation/gameplay confirmation and native pixel/state tests |
| M1 — First loop | Persistent local GBA collection, physical carousel/insertion, internal play, custom pause/state/battery/eject loop | Implemented and RP5 verified; completed-session sleep/recovery added in M6. Broader real-title/audio/latency coverage remains |
| M2 — Refinement | Original refined GBA PBR mesh, studio IBL, receiving slot, phased motion, optional contact sound, reduced motion | Implemented and bounded 60 Hz presentation profiled. Owner/art direction review and broader thermal/battery profiling remain |
| M3 — Library | Multiple SAF roots, incremental/full-hash scans, SHA deduplication, preserved removal, search/favorites/sort/details/history, metadata editing/source recovery; API title/console/all/selected scraping, independent candidate choices, preferences/undo and offline packs | Implemented and isolated Room/SAF/provider/UI tests pass. Provider coverage/rights and large distinct-artwork/storage-provider failure coverage remain |
| M4 — Layouts | Horizontal, vertical and nine-object grid share the same data, selection, input and bounded renderer pool | RP5 layout/focus/pixel/handoff tests and per-layout presentation profiles pass |
| M5 — Additional consoles | Audited source-built mGBA adds GB/GBC, explicit identities, tall GLBs, matching labels/scrape routing, battery and MBC3 clock handling; the post-M6 PS1 increment adds PCSX and discs | Native GB/GBC tests and the PS1 diagnostic suite pass. Other consoles and unusual peripherals remain future work |
| M6 — Console environment | First-run setup, independent controller/core/preferences, optional HOME candidate/Android chooser, completed session sleep/cold/process recovery, frozen native pause frames and reviewed ROM-free backup/restore | Features implemented; focused RP5 tests pass. Final sustained/reboot/full-regression results are in CONSOLE_RELIABILITY. Default-HOME selection is the owner's Android choice |

Public-release gates: choose a GPL-compatible distribution/source plan and audit the complete dependency BOM; resolve provider/data/image redistribution terms; retain source/notice/relinking materials. No public release has been made. Registering a HOME candidate never chooses or replaces the owner's default.

Manual hardware coverage: owner visual/motion/input review; choosing and reverting HOME; multi-hour mixed GBA/GB/GBC sessions and unplugged battery/heat/audio/latency measurements; external controller removal/reconnection; revoked SAF grants and storage-full/provider-write failures; hundreds of distinct label textures. Automated diagnostic ROMs contain original code/imagery and are generated outside Git/APK assets.

Label/media/metadata scraping remains the primary enrichment workflow. Local image import is an optional fallback. Exact Socket artwork/backend parity is unverified; box art is not silently used as a flat label. The first PS1 disc template and embedded core now use the existing candidate/review infrastructure; disc artwork fidelity remains an owner review gate.

Recommended next engineering work: validate BIOS-backed PS1 CUE/BIN titles on the owner's device,
including audio, button mapping, scraped disc labels, save/load and recovery. Follow with a longer
mixed-title thermal/memory/audio run before implementing disc swapping or analog-controller support.
Do not reopen finished subsystems or add several cores at once.
