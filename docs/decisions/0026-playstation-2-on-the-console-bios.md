# ADR 0026: PlayStation 2 on the console's own BIOS

Date: 2026-10-13. Status: accepted; replaces the PlayStation 2 part of ADR 0023 and the PlayStation 2
items of ADR 0024.

## Context

PlayStation 2 ran on Play!, an engine that needs no BIOS. With the owner's two games it played one
below full speed in 3D and never drew the other (ADR 0024). The owner then said: the console's BIOS
is on the device; use it, with an engine of the PCSX2 kind, "maybe referencing NetherSX2, it should
be open source", instead of forcing Play!.

Two things in that needed checking before anything was built.

- **NetherSX2 is not open source.** It is a set of patches over the closed AetherSX2 app, which was
  never published as source. There is nothing of it to build from.
- **ADR 0024 said PCSX2's line needs a Vulkan surface, which this host cannot give. That was
  wrong** for the build used here. ARMSX2, the open continuation of PCSX2 for ARM64 with
  translators for all three of the console's processors, carries a Libretro core in its own tree
  whose OpenGL ES path asks the frontend for exactly the context LibretroDroid has: it takes the
  host's context when it is handed over, makes a second one on its own drawing thread that shares
  with it, and gives each finished picture back as a texture for the host's framebuffer.

## Decision

1. **PlayStation 2 runs on ARMSX2** (`ARMSX2/ARMSX2` at `0076b5c389ea7296331ad2d96193c6e8d5d314fa`,
   GPL-3.0), built unchanged as its own Libretro target and wrapped by the same shim as Flycast and
   Dolphin. `Core.Armsx2` is appended to the registry; `Core.Play`, `play.cmake`, the link-time
   replacement in `cartridge_play.cpp`, the Java VM hand-over it needed (`Core.attaches`,
   `SaveBridge.attach`) and the packaged `GameConfig.xml` are removed. Nothing stored names the old
   core but the revision inside a save state, and PlayStation 2 had stopped writing those.

2. **The BIOS is required and is the owner's.** There is no replacement to fall back to, so the
   console behaves as Saturn does: the import result, the game's details, Settings › Gameplay and
   the dialog on A all offer **Add BIOS file**, and a game is never taken off the shelf to fail.

3. **A BIOS is accepted by its own contents, not from a list.** Saturn has two dumps and PlayStation
   seven; this console had one per model and region, in the dozens. `Firmware` gains `proves`: a
   4 MiB file is a PlayStation 2 BIOS when it carries the console's file table (rows of sixteen
   bytes beginning with `RESET`) and the table's `ROMVER` file reads as a version, a region, a
   console or development-kit mark and a date. This is how the engine itself decides. Any region's
   BIOS starts any region's game. It is stored as `system/pcsx2/bios/ps2-bios.bin`, where the core
   looks, and like all firmware is never bundled, downloaded or put in a backup.

4. **OpenGL ES, fixed.** `armsx2_renderer = OpenGL`, `armsx2_fast_boot = enabled` (the game, not the
   console's start-up screens), `armsx2_bios = auto`. The Vulkan renderer is not built, which also
   keeps its shader compiler out of the build.

5. **One more thing the shim keeps: the order a game is let go in.** The host destroys the context
   and then unloads the game. This engine, told its context is going, gives up everything it draws
   with at once while its emulation is still running, and the next thing it drew ended the process.
   Built with `CARTRIDGE_UNLOAD_FIRST`, the shim unloads the game first and destroys the context
   after. The other two engines keep the host's order.

6. **A loaded game is released before the interface is taken down** (this was wrong for GameCube
   too). An engine that draws for itself must be unloaded on the thread it ran on (ADR 0024, item
   3). From Android 10 the Activity's lifecycle is told of its end *before* `onDestroy`; Compose
   disposes of its views there, and the emulator's view takes that thread with it. `onDestroy` then
   queued the unload for a thread that no longer existed, waited ten seconds for it, and let the
   host unload from the main thread: with a PlayStation 2 game loaded that ended the process, and
   with a GameCube game it is the abort item 3 was about. `MainActivity.endSession` now runs from
   `onActivityPreDestroyed`, registered after the lifecycle's own callbacks so that it is told
   first. Where the view has already left its window the engine is unloaded from the calling thread
   instead of being queued for nobody.

7. **A game is handed over by its canonical path.** Android's private folder is reached through a
   link (`/data/user/0` for `/data/data`). This engine resolves links to find a program's folder
   and then no longer recognised the path it had been given as the same file, so a bare program
   never started and the console's own menu came up instead. A disc was unaffected.

8. **A CUE is started from its first track.** The engine reads a disc as one image and cannot read
   a sheet; `Core.sheets = false` says so and `LibretroEngine` hands it the track the importer
   already stores beside the sheet. `.isz` is no longer taken: this engine does not read it.

9. **No save states, as before, for a different reason.** The core names 68 MiB as the size of a
   state (the real one is a compressed archive inside that block) and makes it on its emulation
   thread while the thread that asked waits. Nothing here has measured how long that takes with a
   game. Until it has, the console stays `Snapshots.Never`: no Save State, Load State or resume
   checkpoint, and its game stays loaded and paused while the app is away (ADR 0024, item 8).

10. **Memory cards are the console's, not the game's.** The core keeps two 8 MB cards under
    `system/pcsx2/memcards`, shared by every PlayStation 2 game as two cards in one console were.
    Play! kept a folder per game; nothing is migrated, because Play! saves were its own format.

## What it took to build

ARMSX2 links eleven libraries its own Android recipe builds first (libpng, libjpeg-turbo, Zstandard,
LZ4, libwebp, SDL3, FreeType, PlutoVG, PlutoSVG, curl, and libpcap's headers). `armsx2.cmake` holds
that recipe with every tag replaced by the commit the tag was, without the shader compiler, and
with libpcap as headers and an empty archive (it is asked for by the configuration and never linked
on Android; building it needs flex and bison). `-Pps2Libraries=<prefix>` names a prefix that recipe
has already built.

- **Only the prebuilt-prefix path has been exercised.** On this machine the libraries were built
  once with the engine's own script and the build was pointed at them. The in-tree
  `ExternalProject` chain is written and has never run.
- The engine is built for Android 9 and later (`android-28`), where its own recipe puts it, and for
  4 KiB memory pages, which is what the Retroid Pocket 5 has: the page size its translators assume
  is fixed when it is built. The rest of the app still installs from Android 8; a PlayStation 2
  game there would fail to load its core.
- The configuration would fetch a patches archive from a "latest" address; that is switched off
  (`ARMSX2_EMBED_PATCHES=OFF`), so the engine's per-game patches are not present. Its own log says
  so at every start.
- The packaged core is 22,064,088 bytes; the debug APK is 54,361,790 bytes, 6.4 MB more than with
  Play!.

## Results on the device

Retroid Pocket 5, Android 13 (`RP5_V1.0.0.189_20251128_174732_user`), Adreno 650, OpenGL ES 3.2,
the night of 2026-10-12. BIOS: a v2.00 European console dump of the owner's, copied into the test
library's own system folder for the runs; the owner's library was not touched and has no BIOS yet.
The two games are the same two as in ADR 0024, started by `RealGameCheck` from the app's private
copies; one game is one observation.

| Game | Under Play! (ADR 0024) | Under ARMSX2 |
|---|---|---|
| the one on a 4.2 GiB DVD image | played; 51 to 53 pictures a second in its first scene, about 85 % of full speed | Logos, opening film, title, new game and the first scene with its dialogue. **A new picture on every screen frame (61 a second) in the film and in the first scene**; fewer only in eight seconds of fades and loads, where the game itself holds a picture. Sound 48,800 frames a second in and out against 48,000 nominal in every second but one (44,000, at a change of scene), none dropped. Slept 5 s and woke at 60 s: paused, still loaded, carried on. Paused and left cleanly |
| the Japanese first release of a game (4.3 GiB) | two pictures, then none, for ever | **Runs.** Logos, title, new game, and its real-time 3D opening with the credits over it: 61 runs a second, 30 to 31 of them new pictures and as many repeats, which is the game's own 30. Sound 48,800 in and out, none dropped. The confirm button (Circle, on Android's A) started the game. Slept 5 s and woke at 85 s: paused, still loaded, carried on. Paused and left cleanly |

Sound was counted, not heard. The pictures were looked at: characters, backgrounds and text are
whole in both games at the console's own resolution.

Also re-run on the same build, because the shim, the path a game is handed over by and the end of a
session all changed: the GameCube game (61 new pictures a second, sound whole, sleep survived) and
the first Dreamcast disc (31 pictures a second, sound whole, restored from its checkpoint after
sleep).

With original programs (`Ps2CoreTest`, three tests): the console without a BIOS explains itself and
takes one by its file table; and, on the owner's BIOS, the project's own MIPS program is started
from a disc image through `SYSTEM.CNF` and as a bare program, draws through the host's context,
offers no state, survives the app being left, and the app can be closed with it still loaded and
start it again in the same process. The two tests that need the BIOS are skipped on a device that
has none in `files/m1-owner-firmware`.

## Not shown

- Any other game. Two is two.
- A game's own save to a memory card, and loading it back. The cards are created (8 MB each,
  unformatted) and no game was taken far enough to write to one.
- *Sharper 3D* (`armsx2_upscale = 2x`) with a game. The sticks in a game. A second player.
- A long session, and what the engine's shader cache does to the first minutes of one.
- A CUE+BIN PlayStation 2 game, a `.chd` and a `.cso`: the owner's two are `.iso`, and the test
  program is an `.iso` and a bare program.
- Whether the app still needs heap pointer tagging off. Play! did (ADR 0024, item 6); ARMSX2's own
  app also turns it off, and the games were only run that way.
- The owner adding the BIOS through the interface on their own library. The dialog, the settings
  row and the import by content were run by the test with a stand-in file; the real file was put in
  place for the test library by hand.

## Consequences

- PlayStation 2 needs one more thing from the owner than it did: the BIOS, once. In return both
  games tried run, at full speed.
- Play!'s BSD licence text leaves `docs/licenses`; ARMSX2's GPL-3.0 text is added. The combined
  app already carried GPL-3.0-or-later obligations through LibretroDroid (LICENSING).
- Every PlayStation 2 game shares two memory cards, and they are not in a backup, as no core's own
  files are yet.
- `RealGameCheck` and the tests that need a required BIOS look for it in
  `files/m1-owner-firmware/<the core's own name for it>` in the app's private storage. Nothing of
  it is in the repository.
- The thread-dump diagnostic now reports the host's own render thread wherever it is, and matches
  this engine's library name; it was blind to both, which hid item 6 for a run.
- Leaving a game used to be the only tested way to end a session. Closing the app with one loaded
  is now tested for PlayStation 2 and GameCube.
