> Items 5 to 7 and the two PlayStation 2 results below are about Play!, which ADR 0026 replaced.
> Its statement that PCSX2's line needs a Vulkan surface was wrong for the build ADR 0026 uses.

# ADR 0024: What the owner's games showed on the engines that draw for themselves

Date: 2026-10-12. Status: accepted; amends ADR 0023, whose three consoles had only ever run original
test programs.

## Context

ADR 0023 put Flycast, Play! and Dolphin in the app and said plainly that no game had run on any of
them. The owner then tried games of their own on the Retroid Pocket 5 and reported:

- PlayStation 2: a black screen, with both games tried;
- Dreamcast: the picture looked right and the sound was badly wrong;
- GameCube: the sound was right and the picture was badly wrong.

All three were this host's doing or the way an engine's Libretro frontend meets it, and none of
them could have been seen with the test programs: those draw one flat picture a frame, make no
sound, and never write to memory the engine has compiled code from.

The games were the owner's own, already imported into the app's private storage. They were started
there by `RealGameCheck`, a device test that is skipped unless it is given a game, in the isolated
test library; nothing of them is in the repository, and they are not named here.

## Found, and what was done

Each of these cost at least one run on the device to find. In the order they were met (the last
two were not in the owner's report; the runs turned them up):

1. **Play! and Dolphin: the wrong picture, or none.** ADR 0023 bound the host's framebuffer before
   every frame, because Dolphin's test program drew to the screen otherwise. Both engines remember
   which framebuffer they bound and skip binding it again; with the host's bound behind their back,
   a game's scene was drawn into the host's picture instead of the engine's own target. That was
   the PlayStation 2 black screen and the GameCube corruption. The host's framebuffer is now bound
   only when the *screen* is bound, which is the one case the earlier fix was for.

2. **Dolphin: a game hung at its first frame.** Dolphin's Libretro frontend runs the emulated CPU
   on its own thread by default and stops it between frames. Two threads raced there: the request
   to stop was dropped when it arrived while the loop believed it had already stopped, and both
   then waited for each other for ever. The variable `dolphin_main_cpu_thread` is now fixed to
   `disabled`: one thread, no race. Measured afterwards with a game: 61 frames a second.

3. **Dolphin: the app closed when a game was left.** The host unloads a core from Android's main
   thread. Dolphin gives the thread it runs on a signal stack and, on unloading, frees "the current
   thread's", which on the main thread is not its own. The shim now exposes `cartridge_unload`, and
   `LibretroEngine` has an engine that draws for itself (`Core.draws`) unloaded on the thread it
   ran on before the host's own calls, which then find nothing left to do.

4. **Flycast: a game ran at twice its speed, and the sound broke up.** The host runs a core once
   for every picture the screen shows and plays whatever sound each run hands it, never waiting.
   These engines emulate until the game has drawn its next picture; a game that draws thirty a
   second advanced two sixtieths per run. Measured: 89,755 frames of sound a second from a core
   that says 44,100, which the host dropped in pieces. The shim now paces a core by its own sound:
   it holds the sound, hands the host one run's worth per frame, and does not run the core while
   enough is still waiting. Measured afterwards: the core ran 30 times a second, 44,140 frames of
   sound a second arrived, none was dropped.

   How much is "enough" depends on what a run brings. An engine that brings several runs' worth at
   a time is held back as soon as half a run's worth is waiting. One that brings a run's worth on
   average is keeping time by itself, and may do so in pieces larger than a run (Play! hands over
   990 frames at a time from its own thread against 735 a run): it is left alone until three runs'
   worth is waiting. With one threshold for both, Play! was held to about four fifths of its speed.

   Nothing held is thrown away to catch up. The first version dropped anything beyond a few runs'
   worth as a backlog. A game that is loading shows no picture for a third of a second and the
   engine then hands over that third of a second of sound from one run (16,318 frames in one call
   was measured); dropping it tore the music at every load, 25,179 frames in one second of one run.
   The core now rests until the sound has been played, which is the time the game itself stood
   still for. Measured afterwards over the same minute: none dropped.

5. **Play!: the app froze on a game that stopped drawing.** Play!'s frontend, once a frame, works
   through what the game sent the graphics chip *until the game shows a picture*, however long that
   takes. On this host that is the thread that owns the screen: a game that was loading or stuck
   took the whole app with it, pause and leaving included. `cartridge_play.cpp` now stands in for
   that one function at link time (`--wrap`, the engine's source untouched): it works through one
   refresh's worth, which the engine itself marks in the same queue, and returns, picture or not.

6. **Play!: a game crashed a few seconds after its intro.** Play! finds out which of a game's
   memory was written to by protecting it and reading the address of the fault; it compares that
   address with memory it took from the heap. Android tags heap addresses for apps that target
   Android 11 or later, a fault reports the plain address, the two never match, and Play! then
   ends the process (`exiting due to SIG_DFL handler for signal 11`). Play!'s own Android app turns
   the tagging off in its manifest; Cartridge's manifest now does the same
   (`android:allowNativeHeapPointerTagging="false"`), for the whole app, because it is a property
   of the process.

7. **Play!: pausing a running game never returned.** A state is captured by asking the emulation
   thread, which at that moment may be waiting for the screen's thread to work through its queue,
   which is the thread asking. With the test program the two never met. Play! is now
   `Snapshots.Never`, like Dolphin: no Save State, Load State, checkpoint or *Start from save
   state*. A game's own memory-card saves are unaffected.

8. **Sleep ended a PlayStation 2 or GameCube game.** Found by accident: the device's power button
   was pressed during two runs. When the app leaves the foreground a game is written to a
   checkpoint, its engine is stopped, and it is started from the checkpoint on return. A console
   with no states has no checkpoint, so the game was simply gone, with whatever it had not saved to
   its memory card. Putting a handheld to sleep is how it is put down. For a core whose `snapshots`
   is `Never` the game now stays loaded and paused behind its pause menu while the app is away
   (`CollectionCoordinator.keptWhileAway`); the host keeps its graphics context across the pause.
   It is lost only if Android ends the process. Consoles with states are unchanged: checkpoint,
   stop, restore, and back at the pause menu.

9. **Any console: a pause that met a vanished screen ended the game unsaved, or the app.** Pausing
   copies the last picture for the menu. Going to sleep, the screen's surface is sometimes gone
   before the pause is reached, and asking a surface that is gone for a copy throws. On the old
   path that was counted as a failed checkpoint and the session was stopped without one; on the new
   path for stateless consoles nothing caught it and the app closed. The copy is now skipped when
   the surface is gone and the pause goes on, since the pause is what writes the save.

## How it was found

Three things were added to see inside a running engine, all original and all in the debug paths
only:

- `SaveBridge.stats`: counters the shim keeps (host frames, core runs, pictures drawn and repeated,
  sound in and out and dropped, framebuffer binds, picture size), read once a second.
- `SaveBridge.dumpThreads`: where every engine thread is, when an engine stops answering. The
  device's own crash dumper needs root; this sends each thread a signal and walks its stack from
  inside the process, and the addresses are resolved on the host against the unstripped libraries.
  It is how the Dolphin deadlock and the Play! wait were read.
- `RealGameCheck`: starts one already-imported game, presses buttons at given seconds, saves a
  picture every few seconds and logs the counters; it can put the device to sleep and wake it, as
  the power button does, and check that the game is still there; a watchdog ends the process if an
  engine takes the main thread with it. It is not in any suite.

## Results on the device

Retroid Pocket 5, Android 13 (`RP5_V1.0.0.189_20251128_174732_user`). One game is one observation;
none of this is a statement about a console's library.

| Console | Which game | What it did |
|---|---|---|
| GameCube | the one game imported, a 3D game | Logos, title, menus; A and Start answered and it created its save file on the memory card. 61.9 pictures a second drawn over a minute (63.0 host frames), sound 33,669 frames a second in and out against 32,000 nominal (the start is counted in), none dropped. Paused, left, started again. Slept for 5 s and woke: paused, still loaded, carried on |
| Dreamcast | first disc of a two-disc game | Logos and title. Emulated 31.1 times a second for 62.6 host frames, which is the game's own 30; sound 46,024 in and 46,018 out against 44,100 nominal (start included), none dropped. Slept and woke: restored from its 27 MiB checkpoint, paused, and carried on |
| Dreamcast | second disc of the same game | The same figures over 45 s. Start on the title opened its menu (new game, continue) |
| Dreamcast | another game | Title, then its opening film: 45.6 runs a second (the film is 60 a second, the title 30), sound 46,640 in and 46,635 out, none dropped |
| PlayStation 2 | a game on a 4.2 GiB DVD image | Logos, opening film, title, new game, and the first scene with its dialogue; Start and the confirm button answered. 59.7 host frames a second over a minute, 51.8 of them new pictures; 61 in the film and menus, 51 to 53 in the first scene, so **that scene runs at about 85 % of full speed**. Sound in step with it (40,161 frames a second average), none dropped. Slept and woke: paused, still loaded, carried on |
| PlayStation 2 | the Japanese first release of a game, on a 4.3 GiB DVD image | **Does not work.** It starts, draws two pictures and never draws again. The engine keeps running at 61 frames a second with sound at about two thirds of its rate, the app stays responsive, and pause and leaving work. Play!'s own compatibility tracker lists this game's American release as playable and its European one as in-game, and has no entry for this release |

Sound was counted, not heard: frames in and out and frames dropped say whether the stream is whole
and at the right rate, not what it sounds like. That is the owner's to judge.

Play!'s own log, which would say what the second PlayStation 2 game is waiting for, cannot be
switched on in this build: with logging compiled in, its first log line runs before the frontend
has told it where its files go, and it stops there. That was tried.

## Not decided here

- **A more compatible PlayStation 2 engine.** Play! runs part of the library, below full speed where
  a game is demanding, and one of the two games tried does not draw at all. The engine the owner would want
  for this console is PCSX2's line, which needs a Vulkan surface (this host has only OpenGL ES) and
  the console's BIOS from the owner. That is a different host, not a setting, and a decision for
  the owner.
- **Speed settings for Play!** (frame skipping, a lower internal resolution): it offers neither.
- Whether Dolphin's two-thread mode can be made safe on this host. One thread held full speed in
  the game tried; a heavier game may not.

## Consequences

- The shim paces every engine that draws for itself. A change to it is a change to three consoles'
  speed and sound, which no test program exercises: re-run `RealGameCheck` with a game on each.
- Native heap pointer tagging is off for the whole app. It is a hardening measure for allocator
  bugs in native code, of which this app's own is small; the embedded engines are the bulk.
- PlayStation 2 has no states. Its tests check that none is offered.
- The engines' sources are still unchanged. One function of Play!'s is replaced at link time. A
  newer Play! that renamed it would otherwise link without complaint and bring the endless wait
  back, so the replacement also names the original (`__real_…`), which makes a rename a link error.
- A stateless game kept while the app is away holds the engine's memory (about 800 MB for the
  GameCube game tried). Android may end the app sooner for it; the game is then lost, as it would
  have been before at the moment the app was left.
