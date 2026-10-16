# Roadmap and acceptance gates

PlayStation 2 on the console's BIOS (2026-10-13, ADR 0026, [CONSOLES](CONSOLES.md)): the question
left open below is answered. The owner asked for a BIOS-based engine; ARMSX2 (open-source PCSX2 for
ARM64) replaced Play!, draws through the same OpenGL ES host, and runs both of the owner's games at
full speed on the RP5 with a BIOS of theirs. **Left for the owner's hands:** adding the BIOS to
their own library through Settings (the test library was given it by hand), a first save to a
memory card and loading it back, and any third game. Next for this console, in the order they are
likely to matter: save states (the engine can make them; how long one takes with a game has to be
measured before a pause may wait on it); memory cards in backups; the engine's per-game patches,
which its build would download and this one therefore leaves out; a CUE+BIN game with music tracks.
Also open from this increment: the in-tree build of the engine's eleven libraries has never been
run (a prebuilt set was used), and one library test failed once in two full runs (see TESTING).

Real games and cartridges (2026-10-12, ADR 0024 and 0025, [CONSOLES](CONSOLES.md)): the owner's
games were run on Dreamcast, PlayStation 2 and GameCube. Dreamcast (three discs) and GameCube (one
game) play at their own speed with whole sound; PlayStation 2 plays one of two games, under full
speed in 3D, and the other never draws. NES, Super Nintendo and Genesis games are shown as the
owner's cartridge models. **Open, and the owner's to decide:** PlayStation 2 on Play! is as good as
it will get from this side. The engine that would run what Play! does not is PCSX2's line, which
needs a Vulkan host this app does not have and the console's BIOS from the owner. Next after that,
in the order games are likely to ask: memory cards in backups; a per-game choice of cartridge shell
where the file name does not say; models for Master System and Game Gear, and a console for the
Nintendo 64 model already supplied; playing a DVD where it lies; trigger travel; disc change.

Dreamcast, PlayStation 2, GameCube (2026-10-11, ADR 0023, [CONSOLES](CONSOLES.md)): Flycast, Play!
and Dolphin are built in from pinned source and draw through the device's OpenGL ES. Each ran an
original program on the RP5 (picture; buttons and stick on Dreamcast and GameCube; a state on
Dreamcast and PlayStation 2) and the full device regression passes with them (TESTING). **Left for
the owner's hands, and it is most of what matters:** a real game on each. Nothing 3D has been drawn,
no speed measured, no sound heard, and the PlayStation 2 pad is untested.

Next engineering work, in the order the owner's first games are likely to ask for it: whatever those
games show; playing a DVD where it lies instead of copying it; memory cards in backups; trigger
travel; a way to hold GameCube states (compressed, or streamed to disk); disc change; and, if Play!
does not run what the owner wants, a host that can give PCSX2 Vulkan.

More consoles (2026-10-11, ADR 0022, [CONSOLES](CONSOLES.md)): a console is one registry entry.
Super Nintendo, NES, Genesis, Master System and Game Gear were added on it and each was played from
an original program on the RP5; the full regression passed 49 of 49. Saturn is implemented with a
required-BIOS workflow and disc identification but has not been run on the device. Game Boy, Game
Boy Advance and PlayStation are unchanged; no schema or save-format change. Left for the owner's
hands: real games on each new console, their sound and button feel, and Saturn end to end with a
BIOS. Not done and stated as such: multi-disc grouping and disc change, the new consoles' own 3D
models. (Dreamcast, GameCube and PlayStation 2, analog sticks and the larger state bound followed
the same day; see above. `-Suite Saturn` and `-Suite Cores` have since run on the RP5.)

Android apps (2026-10-11, ADR 0021): B on the game library with nothing open opens the device's
installed apps as a grid of their own icons; A starts one as a home screen would; B returns to the
same shelf and game. Listed through `LauncherApps` with a MAIN + LAUNCHER `<queries>` declaration
and no broad permission; kept current by its callback and a re-read on return. No change to
emulation, assets, the database schema or save formats. Host checks and the RP5 regression pass
(TESTING). Left for the owner's hands: the feel of the grid under the D-pad and stick with the real
app list, returning with the Home button when Cartridge is the HOME app, and a heavy app that makes
Android reclaim Cartridge's process. Not offered: managing apps.

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
