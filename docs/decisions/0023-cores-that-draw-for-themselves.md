> PlayStation 2 no longer runs on Play!: see ADR 0026. What this record says of Play! is history.

# ADR 0023 — cores that draw for themselves: Dreamcast, PlayStation 2, GameCube

Date: 2026-10-11. Status: accepted for the private development increment.

## Context

ADR 0022 left Dreamcast, PlayStation 2 and GameCube out and listed why: the host draws a
hardware-rendered core through OpenGL ES only, gameplay input was a digital pad, states were bounded
at 16 MiB, every game is a private copy bounded at 2 GiB, and there was nothing original to verify
them with. The owner then asked for the three to be integrated regardless, with freedom to change
the renderer.

The renderer did not need changing. Each console has an engine with an OpenGL ES 3 path and a
Libretro frontend that builds for Android arm64: Flycast, Play! and the Libretro project's fork of
Dolphin. What needed work was the host around them.

## Decision

**The engines, unchanged and pinned.**

| Console | Engine | Pin | Built |
|---|---|---|---|
| Dreamcast | Flycast 2.7 | `flyinghead/flycast` `5aa091fde632fb332c8d8c34e280d62dc951954c` | in Cartridge's CMake tree, by its own target |
| PlayStation 2 | Play! | `jpd002/Play-` `83700b2c31e593bc94e845b4b31b797be84dda59` | as a CMake project of its own |
| GameCube | Dolphin (Libretro fork) | `libretro/dolphin` `114977f115757b1d5506da93c825188233813ec1` | as a CMake project of its own |

A commit pins each tree and, through it, each submodule; only the submodules a Libretro build
compiles are fetched. No source file of any engine is edited.

**Two ways to build an engine that has its own build.** Flycast's CMake tolerates being a
subdirectory, so it stays one (`cartridge_hardware_core`). Dolphin's refers to the top-level source
directory throughout, and all three bundle libraries under the same target names (zlib, zstd,
libchdr, fmt), so Play! and Dolphin are configured as projects of their own with this build's
toolchain (`cartridge_external_core`, CMake's `ExternalProject`). Cartridge reaches into such a
build through `CMAKE_PROJECT_INCLUDE`: `core_wrap.cmake` is read after the engine's `project()` call
and defers, to the end of the engine's own CMakeLists, the linking of Cartridge's shim and the
renaming of the Libretro entry points. The stripped library is placed where Gradle collects every
other core; `cartridge_saves` depends on it, so every build of the app builds it. Dolphin's build
files need CMake 3.25, so the project now names 3.31.6.

**One shim keeps what the host assumes** (`cartridge_shim.c`, built a second time with
`CARTRIDGE_HW_FRAMES`). LibretroDroid 0.14.0 shares one GL context with the core, copies the core's
framebuffer to the screen only inside the core's video callback with client-side vertex arrays,
sizes that framebuffer from the base size the core last announced, and leaves the screen bound when
it calls the core. A core written for a frontend that gives it a context of its own keeps none of
that. The shim wraps four Libretro entry points:

- `retro_set_video_refresh`: around the host's copy to the screen it reads and restores every piece
  of GL state the copy touches (framebuffer, renderbuffer, program, texture and sampler on two
  units, vertex array, array buffer, viewport, clear colour, colour mask, five switches), and unbinds
  what would break the copy. It also counts produced frames, as for every core.
- `retro_run`: binds the host's framebuffer before the frame, and after a frame in which the core
  said nothing reports a repeated frame, so the host redraws instead of swapping an old buffer.
- `retro_set_environment`: remembers where the host's framebuffer is asked for, and replaces a
  core's nominal base size with the size it actually draws, so a higher internal resolution is not
  cropped.
- `retro_set_audio_sample_batch`: the audio counter, as before.

**What the adapter does for them.** The registry says, per engine: the pad has sticks (`analog`:
both sticks go to the game and the D-pad is only the D-pad); the controller ports start empty and
the pad is plugged into the first (`emptyPorts`); packaged data is unpacked into the private system
folder once per build (`data`, `CoreData`); the engine expects Android's Java VM and the app's
assets, which a `dlopen`ed library never receives and which `SaveBridge.attach` hands over before
the core is created (`attaches`, Play! only); and when the whole machine may be captured
(`snapshots`).

**States by size.** The state bound is 64 MiB. A core's `snapshots` is `Always` (the small
consoles: also every 15 seconds of play, as before), `Paused` (Flycast at 27 MiB and Play! at
40 MiB: when asked and when paused; Play!'s frontend also captures without stopping its emulation
thread, which is only sound while that thread waits), or `Never` (Dolphin: 87 MiB measured, more
than a 256 MB heap holds twice). For `Never` the pause menu has no Save State or Load State, no
checkpoint is written, and *Start from save state* is not offered.

**Discs that prove themselves, and DVDs.** A format may now state what its first bytes and its
length prove (`Format.proves`), which outranks the folder's name: Nintendo's marker for GameCube, a
length beyond any CD for a PlayStation 2 `.iso`, the unit and length in a CHD's header for a DVD or
a GD-ROM. A Wii disc is refused by name. PlayStation 2 is bounded at 9 GiB and GameCube at 2 GiB;
the importer asks the document for its size, checks free space first and says how much is needed.
Games are still private copies.

**Original programs for each** (`tools/probe`): SH-4, MIPS R5900 and PowerPC, hand-assembled, each
painting a picture its console's simplest way and, for two of them, answering a button and the
stick. The Dreamcast and PlayStation 2 programs are also written into small original disc images (a
GD-ROM sheet with three tracks; an ISO 9660 volume with a `SYSTEM.CNF`), so each engine's disc path
runs too. GameCube has none: its discs start through a loader that only a real disc carries. Two were first run on the host by a small interpreter written for the purpose, which is how
the Dreamcast and GameCube black screens were shown to be the host's and not the programs'.

## Found on the device

Recorded because each cost a run to find and none is visible in a build:

1. Flycast: black. A vertex buffer left bound made the host's copy draw nothing.
2. Flycast: no input. Its ports are empty until a frontend plugs a pad in.
3. Play!: crash at start in `AttachCurrentThread` on a null Java VM.
4. Dolphin: black. With nothing but the video chip's picture to show it never binds the framebuffer
   it believes is current, and the host had left the screen bound.
5. Dolphin: still black. It says nothing on a frame without a new picture; the host then swaps a
   buffer it never drew.
6. Dolphin: no input, for the reason Flycast had none.
7. Dolphin: a state is 87 MiB.
8. A GD-ROM folder became three games: the sheet, and its two `.iso` data tracks as PlayStation
   discs. A folder's sheets are now read before its files are imported, and what they name is
   skipped (`SheetTracks`).
9. Flycast: the second game of a session had no memory unit. It fits the pad's slots only after all
   four ports have been named; all four are now named.

**Later.** The owner's games were run the next day, and seven more things were found that no test
program could show, among them that item 4's fix (binding the host's framebuffer before every
frame) was itself what blacked out real games. See ADR 0024, which amends this one: the bind is
conditional, Play! has no states, Dolphin runs on one thread, and the shim paces a core by its
sound.

## Not decided here

- **Whether games run, and how fast.** No commercial game has been started on any of the three.
  The programs do not use the tile accelerator, textured geometry or the GameCube's graphics
  processor, which is what games do.
- **PlayStation 2's pad** was not exercised: a bare program cannot reach it.
- **Playing a disc in place.** A DVD is copied. A path that plays the owner's file where it lies
  needs a different storage contract (a persisted grant per game, a game that can go missing) and is
  left for when the owner has tried the copies.
- **A more compatible PlayStation 2 engine.** The PCSX2 Libretro port needs Vulkan or a software
  renderer and the console's BIOS. The host offers neither a Vulkan surface nor, for a software
  renderer at that scale, the speed.
- Trigger travel, a second player, disc change, memory cards in backups, compressing large states.

## Consequences

- The debug APK grows by Flycast (19.6 MB), Dolphin (12.9 MB), Play! (5.5 MB) and 1.1 MB of
  Dolphin's data.
- A clean native build fetches three more trees and their submodules (Dolphin's is several hundred
  megabytes) and took about twenty minutes here with the sources already present. On this machine
  the three are supplied through `-PdreamcastSource`, `-Pps2Source` and `-PgamecubeSource`; **the
  fetch itself has not been exercised** for Play! and Dolphin.
- Two of the three licences are GPL (Flycast GPL-2.0, Dolphin GPL-2.0-or-later) and one is
  BSD-2-Clause (Play!); each bundles further libraries with notices of their own (LICENSING). The
  existing release gate is unchanged in kind and larger in extent.
- The shim is now load-bearing for every core that draws for itself. A change to it is a change to
  three consoles; `-Suite Dreamcast`, `-Suite PS2` and `-Suite GameCube` are its tests.
- Small consoles are unaffected: they use the first build of the shim, which is the counters only.
