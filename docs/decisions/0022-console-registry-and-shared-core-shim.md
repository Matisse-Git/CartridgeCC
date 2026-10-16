# ADR 0022 — a console registry, a shared core shim and six more consoles

Date: 2026-10-11. Status: accepted for the private development increment.

## Context

Four consoles were playable through two cores, and the second one had been added by asking
`system == PS1` in a dozen files: the core's file name, its revision, its options, its BIOS, its
state envelope, its private path, its 3D template, its typeset label, its artwork lookup, its scan
rules and the names of two native symbols. The owner asked for more consoles. Adding each one the
same way would have multiplied those questions.

## Decision

**One definition per console.** `GameSystem` now carries everything the app asks about a console:
title, maker, generation, media template, core, formats with their content checks, private
extension, bundle or single file, size bound, folder names, provider platform name and whether its
private copy is a fingerprintable dump. `Core` carries what consoles sharing an engine have in
common: the packaged library, the revision that binds states, the options heading, firmware, fixed
variables and whether the core writes files of its own. `ConsoleCatalog` derives the supported
folders from it. The enum names GBA, GB, GBC and PS1 are stored in Room, backups, the scrape journal
and preferences and are unchanged; new consoles are appended, so existing data and shelf order read
back as before. No schema change.

**Media is presentation only.** `Media` (cartridge, tall cartridge, disc) picks the pooled 3D
template and the label's proportions. Whether a game is one file or a bundle of tracks is a separate
field, because the owner asked for every new console to be shown as a disc until their own models
exist. The pool stays at three templates and 45 objects. (Until ADR 0025: seven templates, 105.)

**A shared native shim.** mGBA and PCSX each compile their Libretro frontend inside an original
wrapper to count real video frames. New cores need nothing core-specific, so `cartridge_core()` in
`cores.cmake` compiles the unchanged upstream sources with `retro_set_video_refresh` and
`retro_set_audio_sample_batch` renamed, and links `cartridge_shim.c`, which provides those names
and counts produced frames and non-silent PCM. `SaveBridge` looks for the shared counter first and
keeps mGBA's and PCSX's extensions. Every core's exports are limited to `retro_*` and `cartridge_*`.
Readiness is unchanged: Running still requires two actual video frames.

**Cores, each pinned by archive hash and built from its own Android recipe:**

| Core | Pin | Consoles |
|---|---|---|
| Snes9x | tag 1.63, SHA-256 `bec7792b…2162` | Super Nintendo |
| FCEUmm | `7a542dab…c0c2`, SHA-256 `a2e8793d…8a8a` | NES |
| Genesis Plus GX | `393f70cd…13f4`, SHA-256 `7de279d6…9f2c` | Genesis, Master System, Game Gear |
| Beetle Saturn | `ec20ea05…2ff`, SHA-256 `d7e8869d…d036` | Saturn |

Snes9x was the owner's stated preference and is the lightest accurate choice. FCEUmm is plain C
with the widest mapper coverage. Genesis Plus GX serves three consoles from one library. Beetle
Saturn is the most compatible Saturn engine; its cost is speed (see Consequences).

**Files identify themselves.** The importer reads the first 512 bytes before copying anything and
asks each candidate console's format to accept them. A shared disc container goes to the console its
folder names; a CUE's first data sector overrules that, because Sega discs begin with the console's
name. An extension that is common outside games (`.exe`, `.md`) is tried quietly; `.bin` is a game
only in a Genesis folder. A later console's disc is hashed with its console's name in front, so an
image once listed under PlayStation by mistake can be listed correctly; PlayStation identities are
computed exactly as before.

**Firmware is a property of a core.** `FirmwareStore` replaces the PlayStation-only store: explicit
SAF import, accepted by content against the dumps the pinned core is known to run, stored under the
private name that core looks for, never downloaded, bundled or backed up. A core says whether its
firmware is required. PlayStation's is not (built-in stand-in); Saturn's is, per region. A console
whose required file is missing explains that before a game leaves the shelf (`Destination.Setup`),
on the import result, on the game's details and in Settings.

**A core's own files get a folder per game.** Beetle Saturn writes its clock and cartridge-backup
files by content base name, which is `game` for every bundle. Such a core is given
`saves/<content hash>.files/` so two games never share a file. Internal backup memory still goes
through the bounded battery path.

**Options stay few and real.** Each console gets the frontend's screen effect and, where the core
declares one that matters, a variable checked against that build (`snes9x_reduce_sprite_flicker`,
`fceumm_nospritelimit`, `genesis_plus_gx_no_sprite_limit`, `genesis_plus_gx_audio_filter`).

## Not decided here

(Superseded the same day by [ADR 0023](0023-cores-that-draw-for-themselves.md), which adds all
three.) Dreamcast, GameCube and PlayStation 2 are not implemented. The blockers are the host's
OpenGL-ES-only hardware rendering, digital-only gameplay input, the bounded save and state design,
private copies of multi-gigabyte discs and the lack of verifiable original content; they are listed
in [CONSOLES](../CONSOLES.md). Launching a particular game in another emulator app is rejected:
those apps expose no documented interface for it. Multi-disc grouping and disc change remain open.

## Consequences

- Three source-available but non-GPL licences join the build: Snes9x and Genesis Plus GX are
  non-commercial. That is acceptable for a private build and is a further gate on any public
  release, on top of the existing GPL one (LICENSING).
- A clean native build is much longer. Beetle Saturn's 68000 interpreter is one generated switch
  that takes about sixteen minutes to compile on this machine; cores are therefore built without
  debug information. Upstream's split form does not link outside its Emscripten recipe.
- On Windows a project path containing a space makes CMake call the linker driver by its 8.3 name,
  which hides the `++` clang reads its mode from; C++ cores pass `--driver-mode=g++`.
- The debug APK grows from about 17 MB to 28 MB.
- Saturn speed on the RP5 is unmeasured and may be poor for 3D games; nothing in the interface
  promises otherwise. The fork's new SCU and SCSP translators are fixed off until measured.
- Flycast was compiled for arm64 as a feasibility check and left out: a hardware-rendered core is
  not added without a run on the device.
- Core-written files are not in backups yet.
