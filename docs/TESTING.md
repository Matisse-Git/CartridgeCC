# Tests and actual results

## Shader presets, step 3: the library download — 2026-10-16 (ADR 0027)

On top of `44e12ef`. Host: `ShaderLibraryUpdaterTest` (7) with made-up archives: install, up to date
without a download, a newer commit replacing the whole library, failures (an oversized file, a truncated
archive, two top folders, no presets, an oversized download) and a cancel each leaving the installed library
and no staging folder, paths that would leave the library never written, a swap cut short undone, and
which names are kept.

RP5 (same device and firmware as below), `ShaderLibraryUpdateTest` against GitHub on the device's network,
in a folder of the test's own: **passed**. The newest library, `libretro/glsl-shaders@148ce728` of
2026-10-11, installed in 4.5 s from a 26,967,623-byte archive: 621 presets, 1,463 files, 34,336,068 bytes.
crt-royale read from it passed the GPU check. Pressed again, it was up to date and downloaded nothing. A
download cancelled after 4 MiB and an update from a repository that does not exist ("The shader library
was not found on GitHub.") each left the installed library as it was; a last update put the newest back.

## Shader presets, step 2 — 2026-10-16 (ADR 0027)

Working tree on top of `2272768`. Device: Retroid Pocket 5, Android 13,
`RP5_V1.0.0.189_20251128_174732_user`, Adreno 650, OpenGL ES 3.2 (float framebuffers 32-bit, border
clamp, 16 texture units, as the host logs them). The library was pushed by hand to the app's private
`files/shaders/library` (`libretro/glsl-shaders@148ce728`, 621 presets); downloading it is step 3. A few
test presets of this work's own (a broken one, a float round trip, instrumented copies of crt-guest) sit in
its `cartridge-test` folder on the device and are not counted below.

**Host.** Both APKs build. Unit tests pass, among them `PresetReaderTest` (7) and `GlslCompatTest` (6).
Lint: zero errors. 10 asset checks pass. Read on the PC, 619 of the 621 presets are readable; the other two
are broken in the library itself.

**RP5.**

- **Regression: 60 of 60** in 665 s (`OK (60 tests)`): the 58 earlier tests and `ShaderPresetTest`. The
  shim now keeps sixteen texture units and the unpack state; the host is built with glslang and
  SPIRV-Cross.
- `ShaderPresetTest`: a broken preset is refused with the compiler's message; a preset reading the previous
  frame builds and draws; **605 of the 621 presets build and draw** in the check's own context (503 the way
  RetroArch compiles them, see ADR 0027), most in 10 to 100 ms, crt-royale in about 3.3 s, nnedi3's largest
  in about 10 s.
- Read back from the GPU: a float round trip (`pow(x, 10)` into a float framebuffer, then `pow(x, 0.1)`)
  gives its input back (0.4966 against 0.4965); crt-guest-dr-venom's final picture went from 0.10 to 0.42
  of full brightness once the Adreno fault was written around; crt-royale's from black to 0.41.
- `RealGameCheck` with presets, one of the owner's games each, 30 s, pictures looked at:

| Console | Preset | Result |
|---|---|---|
| Super Nintendo | crt-royale | CRT mask, scanlines and glow; 60.0 fps, no late frame |
| Super Nintendo | crt-guest-dr-venom | bright, scanlined; 60.0 fps, no late frame |
| Game Boy Advance | lcd-grid-v2-gba-color-motionblur (previous frames) | LCD grid; 60.0 fps; at second 10 crt-royale was checked in its own context in 1.9 s while the game was paused, and the game resumed |
| PlayStation | a deliberately broken preset | normal picture and the notice with the compiler's reason |
| Dreamcast | crt-royale, real sleep at 20 s | restored from its checkpoint and carried on; 57.7 fps, 2 late frames, as without a preset |
| GameCube | crt-royale, real sleep at 20 s | stayed loaded and carried on (1314 → 1436 core runs); 57.1 fps, 3 late frames, as without a preset |
| PlayStation 2 | crt-royale, real sleep at 20 s | stayed loaded and carried on (1396 → 1521); 60.0 fps |

- **No preset, nine consoles**, against the build with the prebuilt library (section below): every game
  presented at the same rate and the shim's counters agree; GameCube had 8 late frames against 6, Dreamcast
  3 against 3. **Not run with a real game:** NES, Genesis, Master System and Game Gear (no owner game on the
  device); Saturn was not run with a preset.

## LibretroDroid built from source — 2026-10-16 (ADR 0027 step 1, `libretrodroid/UPSTREAM.md`)

Working tree on top of `ef2d9f7`, versionCode 17. Device: Retroid Pocket 5, Android 13,
`RP5_V1.0.0.189_20251128_174732_user`, Adreno 650, OpenGL ES 3.2. The host library is LibretroDroid
0.14.0 built from the tag's source in the `libretrodroid` module instead of JitPack's AAR; nothing
is meant to look or behave differently.

**Host.** Both APKs build (debug APK 54,361,908 bytes, SHA-256 begins `e3793cfe4bd9bb29`). **90 host
tests pass.** Lint on the app: zero errors, the same 25 warnings. 10 asset checks pass. Against the
AAR: the same 39 classes with identical `javap -public` output, the same 34 exported JNI functions,
the same eight system libraries linked; the packaged `liblibretrodroid.so` is the module's own
(SHA-256 begins `f6aa365a0a30de8b`; the AAR's began `f49335917c7e2a4f`).

**RP5: the regression.** **58 of 58** in 509.2 s (`OK (58 tests)`), including `BrowseStabilityTest`,
`LibraryPacingTest`, `UxTourTest` and the Dreamcast, PlayStation 2, GameCube and large-disc tests.

**RP5: one of the owner's games per console** (`RealGameCheck`, 30 s each, Start and A pressed at
fixed seconds). `RealGameCheck` can now start a cartridge game by the start of its hash; it took
only disc bundles before. Saturn's run uses a copy of the owner's own `sega_101.bin`, put in the
app's private `files/m1-owner-firmware/` from the owner's library on the device; the library was
not changed. Every game reached Running and drew its title or menu on this build (pictures looked
at, not kept). The Dreamcast, GameCube and PlayStation 2 games were also put to sleep for 5 s at
second 20 with the power key's event: Dreamcast came back from its checkpoint and carried on;
GameCube and PlayStation 2 stayed loaded and paused and carried on (1316 → 1440 and 1392 → 1518
core runs).

The same nine games were run again on the source build and on a build of the same tree with the
AAR, sampling the game's SurfaceView presentation from SurfaceFlinger (`--latency` on its BLAST
layer, about 380 frames between seconds 12 and 19) and the shim's counters where the core has
them:

| Console | Presented, source build | Presented, AAR | Shim counters per second (source / AAR) |
|---|---|---|---|
| Game Boy, Game Boy Color, Game Boy Advance, PlayStation | 60.0 fps, max 16.85 ms, no late frame | 60.0 fps, max 16.83 ms, no late frame | none (these cores are not wrapped by the shim) |
| Super Nintendo | 60.0 fps, max 16.79 ms | 60.0 fps, max 16.85 ms | drawn 61.9 / 61.9, audio 33,009 / 33,009 |
| Saturn | 60.0 fps, max 16.78 ms | 60.0 fps, max 16.82 ms | drawn 61.8 / 61.8, audio 45,549 / 45,549 |
| Dreamcast | 58.3 fps, 2 late frames, max 150 ms | 58.0 fps, 3 late frames, max 133 ms | core 30.5 / 30.5, audio 45,101 / 45,158 |
| GameCube | 59.4 fps, 4 late frames, max 33 ms | 57.7 fps, 6 late frames, max 183 ms | core 61.5 / 61.7, repeated 7.5 / 7.6 |
| PlayStation 2 | 60.0 fps, max 16.82 ms | 60.0 fps, max 16.86 ms | core 61.9 / 61.9, repeated 2.8 / 2.8 |

The late Dreamcast and GameCube frames happen on both builds and come from those games, not from
the host. **Not run with a real game:** NES, Genesis, Master System and Game Gear; the owner has no
game for them on the device. Their cores ran only in the regression's probes.

## PlayStation 2 on the console's BIOS — 2026-10-13 (ADR 0026, [CONSOLES](CONSOLES.md))

Working tree on top of `ef2d9f7`, versionCode 17, nothing committed. Device: Retroid Pocket 5,
Android 13, `RP5_V1.0.0.189_20251128_174732_user`, Adreno 650, OpenGL ES 3.2. Everything below was
run on the night of 2026-10-12 into 2026-10-13 on one build: debug APK 54,361,790 bytes, SHA-256
begins `a2605ffd7f55427a`.

**Host.** Both APKs build. **90 host tests pass** (89 before; one new in `SystemRegistryTest`: a
PlayStation 2 BIOS is taken by its own file table whatever its region, and nothing else is). Lint:
zero errors, 25 warnings, the same 25. 10 asset checks pass. The native build was the incremental
one; the engine's eleven libraries came from a prefix built beforehand with its own recipe, and
**the in-tree build of those libraries (`armsx2.cmake` without `-Pps2Libraries`) has never been
run.**

**The BIOS the device tests use.** A PlayStation 2 game cannot be started without one, and none
can be in the repository. `Ps2CoreTest` and `RealGameCheck` look for it in the app's private
`files/m1-owner-firmware/pcsx2/bios/ps2-bios.bin` and copy it into the test library's own system
folder; without it the two tests that start something are skipped (reported as assumption
failures, not passes). For these runs a v2.00 European console dump of the owner's was put there
by hand. The owner's own library was not given a BIOS and was not touched.

**RP5: the regression, twice.**

| Run | Result | From the device's log |
|---|---|---|
| first | **57 of 58**; `LibraryGrowthTest` failed | `run finished: 58 tests, 1 failed`. After leaving a Game Boy Advance game the focused object's label was not on screen within 8 s (`unlabelled=1 uploads=2`). The runner's own summary was lost when the USB link dropped |
| second, same APKs | **58 of 58** in 509.7 s | `run finished: 58 tests, 0 failed, 0 ignored`; `OK (58 tests)` |

The `LibraryGrowthTest` failure was not reproduced: it passed three times alone and twice after the
classes that precede it, between the two runs. The same test failed the same way, and passed when
repeated, on 2026-10-09 and 2026-10-10, before any of this work. It is an intermittent fault in
label staging after a game is left, not something this increment explains or fixes; it is open.

58 is the 57 of 2026-10-12 with these changed:

| Test | What it does now | From the log |
|---|---|---|
| `Ps2CoreTest`, no BIOS (new) | A disc in a folder named `ps2`, with no BIOS in place: the import result offers **Add PlayStation 2 BIOS**; A on the game opens the setup dialog and the game stays on the shelf; Settings lists the missing file; a 4 MiB file that is not a BIOS is refused in words and nothing is written; an original stand-in with a file table and a version is taken as this console's and stored as `pcsx2/bios/ps2-bios.bin`. The stand-in is deleted again | passed |
| `Ps2CoreTest`, disc | On the owner's BIOS: the 128 KiB `.iso` is taken as PlayStation 2 by its folder, its `SYSTEM.CNF` is read and the program it names started and seen (red). New: the game is left; started again; **the Activity is closed with it loaded**, and a new Activity starts it once more | `Closed with the game loaded in 696 ms, and started it again` |
| `Ps2CoreTest`, program | On the owner's BIOS: the bare program draws; the three fixed variables and the default of *Sharper 3D* are read back from the running core; no state is offered or written; the app is left and returned to and the game is still loaded; the two memory cards exist under the system folder and nothing was written to a folder of the game's own; a second start | the core's own folders listed; passed |
| `GameCubeCoreTest` | New at its end: the Activity is closed with the program loaded | `Closed with the game loaded in 809 ms` |

Before the fix those two closes took ten seconds and then ended the process (ADR 0026, item 6).

**RP5: the owner's games, same build.** Started by `RealGameCheck` from the app's private copies
in the isolated test library; one game is one observation. A line of the shim's counters is logged
about once a second; the figures are the median line of each run after its first three seconds,
because a line that also saves a picture covers a little more than a second. Nominal sound rates
are 48,000 (PlayStation 2), 32,000 (GameCube) and 44,100 (Dreamcast) frames a second; a line covers
about 1.02 s.

| Console | Game | Seconds | Core runs /s | New pictures /s | Sound in /s | Sound out /s | Dropped | Outcome |
|---|---|---|---|---|---|---|---|---|
| PlayStation 2 | first of two (4.2 GiB DVD image) | 75 | 61 | 61 in the film and in the first scene; 25 to 54 in eight seconds of fades and loads, where the game holds a picture | 48,832 | 48,849 | 0 | logos, opening film, title, new game, first scene with its dialogue; slept at 60 s and carried on; paused and left. Sound came at its full rate in every second but one (44,032, at a change of scene) |
| PlayStation 2 | second of two (4.3 GiB, a Japanese release) | 110 | 61 | 30 to 31, and as many repeats | 48,832 | 48,849 | 0 | logos, title, new game on the confirm button, the real-time opening with its credits; slept at 85 s and carried on; paused and left |
| GameCube | the one imported | 45 | 61 | 61 | 32,640 | 32,595 | 0 | as on 2026-10-12; slept at 35 s and carried on |
| Dreamcast | first disc of a two-disc game | 45 | 31 of 61 host frames | 31 | 45,613 | 44,876 | 0 | as on 2026-10-12; slept at 35 s, restored from its checkpoint and carried on |

Under Play! on 2026-10-12 the first PlayStation 2 game drew 51 to 53 pictures a second in its first
scene and the second drew two pictures and no more. Pictures saved during these runs were looked
at: both games are whole, at the console's own 640×448.

**Sleep** in that table is a real one: `input keyevent KEYCODE_SLEEP`, five seconds,
`KEYCODE_WAKEUP`. Not process death, not a cold Activity start, not a reboot.

**What was not run, and should not be read into the above.**

- Any other PlayStation 2 game, and any PlayStation 2 game as CUE+BIN, `.chd` or `.cso`.
- A save to a PlayStation 2 memory card, or a load from one. Both cards exist and are unformatted.
- *Sharper 3D* with a game; the sticks in a game; a second player; a long session.
- The owner adding the BIOS to their own library through the interface. The dialog, the settings
  row and the import by content ran with a stand-in; the real file was put in place by hand, for
  the test library only.
- The app with heap pointer tagging on. It is off, as it was for Play!.
- Sound was counted, never heard.
- Closing the app with a Dreamcast game loaded: that console is stopped and written to a
  checkpoint when the app is left, so it does not reach the same path.
- An Android older than 10, where the earlier release of a loaded game is not registered and the
  old order applies; and an Android older than 9, where this engine would not load.

**Running a PlayStation 2 game of your own through it.** The game must already be imported, and a
BIOS must be in the test folder (it is not taken from your library):

```bash
adb push <your BIOS> /data/local/tmp/ps2-bios.bin
adb shell chmod 644 /data/local/tmp/ps2-bios.bin
adb shell run-as dev.cartridge mkdir -p files/m1-owner-firmware/pcsx2/bios
adb shell run-as dev.cartridge cp /data/local/tmp/ps2-bios.bin files/m1-owner-firmware/pcsx2/bios/ps2-bios.bin
adb shell am instrument -w -r -e class dev.cartridge.RealGameCheck -e game PS2 -e pick 0 \
  -e seconds 60 -e every 10 -e keys 20:BUTTON_START,26:BUTTON_A -e sleep 40 \
  dev.cartridge.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s "CartridgeCheck:*" "TestRunner:*"
```

The engine's own log of the last start is `files/m1-test-system/pcsx2/logs/emulog.txt` in the
app's private storage (`files/system/…` for the real library).

## Real games on three engines, four more cartridges — 2026-10-12 (ADR 0024, 0025, [CONSOLES](CONSOLES.md))

*PlayStation 2 in this section is Play!, which the section above replaced the same night.*

Working tree on top of `ef2d9f7`, versionCode 17, nothing committed. Device: Retroid Pocket 5,
Android 13, `RP5_V1.0.0.189_20251128_174732_user`, Adreno 650, OpenGL ES 3.2.

**Host.** Both APKs build (debug APK 47,922,986 bytes, SHA-256 begins `0ba92539d1d1bf2b`).
**89 host tests pass** (88 before; one new in `SystemRegistryTest` for cartridge shapes, and the
ones about states and label keys rewritten). Lint: zero errors, 25 warnings; the one new warning
says the manifest's pointer-tagging attribute is only read from Android 11, which is where tagging
exists. 10 asset checks pass over seven GLBs, and the Khronos validator reports zero errors and
zero warnings on each. `tools/import_models.py` reproduces the three earlier GLBs byte for byte.

**RP5, this build: the regression.** **57 of 57 passed**, read from the device's own log
(`run finished: 57 tests, 0 failed`), in about 8 min 20 s. The exact time is not recorded: the USB
link dropped during the run and took the runner's summary with it, and the device's power button
was pressed once inside the run. The same 57 tests as on 2026-10-11, with these changed:

| Test | What changed | From the log |
|---|---|---|
| `Ps2CoreTest`, program | No state is offered any more: the pause menu has no Save State or Load State and none is written. New: the app is left and returned to (`moveToState`), and the game is found paused, still loaded, its count of pictures carrying on | passed |
| `GameCubeCoreTest` | New: left and returned to in the same way while the program shows green, which only a button press makes it show; green again afterwards, so it is the same loaded program | passed |
| `UxTourTest` | Seven more fixture games on three more shelves, and four more captures (`04b`, `04c`, `04d`, `17c`) | passed; captures looked at |
| `BrowseStabilityTest` | unchanged test, seven templates behind it | `none on stage unlabelled` in all three layouts; 7, 7 and 6 label uploads |
| `LibraryPacingTest` | unchanged test, seven templates behind it | library of 40: 60.0 fps, median 16.66 ms, p99 at most 16.81 ms, no gaps, in all six cases |
| `ConsoleCoresTest` | checks each game is shown as its console's object | all five consoles verified as before |
| every test that counted the pool | 105 objects (seven templates of fifteen), where it was 45 | passed |

The stage was ready 145 to 202 ms after it was created, with seven templates loading.

**RP5, this build: the owner's games.** Six games the owner had imported, each started from the
app's private copy in the isolated test library by `RealGameCheck`, which is not in any suite. One
line of the shim's counters was logged every second and a picture saved every few seconds; the
figures are averages over the run. One game is one observation.

| Console | Game | Seconds | Host frames /s | Core runs /s | New pictures /s | Sound in /s | Sound out /s | Dropped | Outcome |
|---|---|---|---|---|---|---|---|---|---|
| GameCube | the one imported | 60 | 63.0 | 63.0 | 61.9 | 33,669 | 33,668 | 0 | title, menus, created its save file; paused and left cleanly |
| Dreamcast | first disc of a two-disc game | 60 | 62.6 | 31.1 | 31.1 | 46,024 | 46,018 | 0 | logos, title |
| Dreamcast | second disc of it | 45 | 62.8 | 31.4 | 31.3 | 46,092 | 46,092 | 0 | Start opened the title's menu |
| Dreamcast | another game | 45 | 63.5 | 45.6 | 45.5 | 46,640 | 46,635 | 0 | title, opening film |
| PlayStation 2 | first of two | 60 | 59.7 | 59.7 | 51.8 | 40,161 | 40,149 | 0 | film, title, new game, first scene; 51 to 53 pictures a second there |
| PlayStation 2 | second of two | 30 | 65.3 | 65.3 | 0.1 | 32,010 | 32,002 | 0 | **two pictures, then none; never recovers** |

The first second of every run holds more than a second's host frames and sound (the start), which
is why the averages sit a little above 60 and above each console's nominal rate (32,000 for
GameCube, 44,100 for the other two). Before the fixes the same counters read: 89,755 frames of
sound a second from the first Dreamcast disc, at 60 core runs; no picture at all from either
PlayStation 2 game; and on GameCube a deadlock at the first frame, then an abort on leaving.

**Sleep, with those games.** Evidence of a real screen sleep and nothing more: `input keyevent
KEYCODE_SLEEP`, five seconds, `KEYCODE_WAKEUP`, on the RP5 with a game running. Not process death,
not a cold Activity start, not a reboot.

| Console | After waking | Then |
|---|---|---|
| GameCube | paused, the same game still loaded | Resume: 1,297 core runs before sleep, 1,420 a second and a half after |
| PlayStation 2 | paused, the same game still loaded | Resume: 2,711 before, 2,835 after |
| Dreamcast | restored from its checkpoint, paused | Resume: running |

The first PlayStation 2 attempt closed the app: the pause reached the screen after its surface had
gone, and copying the picture threw. Fixed (ADR 0024, item 9) and run again, as above.

**What was not run, and should not be read into the above.**

- Any other game on these three consoles. Any game at all on Saturn.
- Sound was counted, never heard: the counters say it is whole and at the right rate.
- A game's own save was created once (GameCube) and never loaded back. No long session.
- *Sharper 3D* with a game. A second player. The sticks, in a PlayStation 2 game.
- What Android does to a stateless game kept loaded if it needs the memory: not provoked.
- The four new cartridges were seen on the device in the tour's captures and in the browse and
  pacing tests; nothing measures how they look beside each other, which is the owner's to judge.
- The first three engine tests' picture checks still use original programs; they prove a start.

**Running a game of your own through it.** The game must already be in the app (imported the
ordinary way); nothing about it is written to the repository.

```bash
adb shell am instrument -w -r -e class dev.cartridge.RealGameCheck -e game GAMECUBE -e pick 0 \
  -e seconds 60 -e every 10 -e keys 20:BUTTON_START,26:BUTTON_A -e sleep 40 \
  dev.cartridge.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s "CartridgeCheck:*" "TestRunner:*"
```

`game` is a console's name or the start of a content hash; `pick` chooses among several; `keys`
presses buttons at given seconds; `sleep` puts the device to sleep at one and wakes it; `stacks`
logs where every engine thread is. Pictures land in `files/diagnostics/` in the app's private
storage. Read the outcome from the device's log, as above: the USB link drops after a sleep.

## Dreamcast, PlayStation 2, GameCube — 2026-10-11 (ADR 0023, [CONSOLES](CONSOLES.md))

Working tree on top of `ef2d9f7`, versionCode 17, nothing committed. Device: Retroid Pocket 5,
Android 13, `RP5_V1.0.0.189_20251128_174732_user`, Adreno 650, OpenGL ES 3.2.

**Host.** Both APKs build (debug APK 42.3 MB with nine cores, SHA-256 begins `90ad5b8c61293560`).
**88 host tests pass** (85 before; three new in `SystemRegistryTest`, and the existing ones
extended). Lint: zero errors, 24 warnings, none in new files. 10 asset checks pass. The native build
is CMake 3.31.6 now; a full one took 19 min 42 s on this machine with the three engines' sources
already present.

**RP5, this build.** The full regression is **57 of 57 in 492.2 s**: the 51 earlier tests
unchanged, and six new ones.

| Test (suite) | What ran on the device | From the log |
|---|---|---|
| `DreamcastCoreTest`, program (`Dreamcast`) | An original SH-4 program on Flycast's built-in BIOS replacement: the picture it writes into video memory reached the screen through OpenGL ES; A switched it; the left stick held right switched it again and released returned; Save State, a change, Load State brought the saved picture back; its memory unit is a file in the game's own folder; a second start; the library intact | state 27,652,536 bytes; own files `game.A1.bin` |
| `DreamcastCoreTest`, disc (`Dreamcast`) | The same program as a GD-ROM image: a GDI sheet and three tracks in a folder, imported as one game under generated track names; Flycast read the disc's header, found the program it names in the disc's file system and started it from the header's start-up code; picture; A switched it; its memory unit carries the disc's product number | own files `T-00000.A1.bin` |
| `Ps2CoreTest`, program (`PS2`) | An original MIPS program on Play!'s built-in system software: the sprite it sends the graphics chip each frame reached the screen; Save State and Load State; its memory-card folder is under the game's own folder; a second start; then a second game in the same process, which got a folder of its own | state 41,943,084 bytes; own files `Play Data Files` |
| `Ps2CoreTest`, disc (`PS2`) | The same program on a 128 KiB `.iso` in a folder named `ps2`: taken as PlayStation 2 by the folder; Play! mounted it, read its `SYSTEM.CNF` and started the program that names; picture | — |
| `GameCubeCoreTest` (`GameCube`) | An original PowerPC program started directly on Dolphin (arm64 JIT, fastmem, dual core, OpenGL ES): with *Exact video output* on for that game, the picture it paints into the video chip's memory reached the screen; A switched it; the stick switched it and returned; the pause menu offers no state and writes none; its save folder is under the game's own folder; Dolphin's data was unpacked; a second start | own files `User` |
| `LargeDiscImportTest` (`Discs`) | An empty 2.2 GiB image, longer than a 32-bit count, picked as a single file: recognised as PlayStation 2 by its size, copied and hashed whole, listed, same length; the test removes its copy | imported in about 4.5 s |

The six run one after another in one process in that regression, so each engine also takes the
screen after another one has had it, and a second Dreamcast game follows a first.

**What those programs are, and are not.** `tools/probe/generate_dreamcast_probe.py`,
`generate_ps2_probe.py` and `generate_gamecube_probe.py` hand-assemble a few dozen instructions each.
They prove that the engine is loaded, its processor translator runs, a picture gets from the
emulated video hardware through the engine's OpenGL ES renderer and Cartridge's shim to the screen,
that (two of three) Android's A and the left stick reach the emulated pad, and that (two of three) a
disc image is mounted and started the way a game's is. They draw nothing in 3D, make no sound and
are not games. **No commercial game has been started on Dreamcast, PlayStation 2 or GameCube,
nothing was measured for speed, and the PlayStation 2 pad was not exercised** (its program reads
none). Sharper 3D on the three was not looked at. A GameCube disc image was not started: a real one
is the only kind there is.

**Checked on the host first.** The SH-4 and PowerPC programs were run by two small interpreters
written for the purpose (not committed) before the device was blamed for a black screen: both wrote
the right video and controller registers and the right pixels, and both answered a button and the
stick. The nine faults then found on the device are listed in ADR 0023. Two were Cartridge's own
(a sheet's `.iso` tracks listed as games; one controller port named instead of four); the rest were
in what the host assumes of a core. None was in an engine.

**Measured by the way.** A GameCube state is 90,722,762 bytes (hence none is offered). The app's
heap limit on the RP5 is 256 MB. Dolphin logged on this device: `Missing OGL Extensions:
PinnedMemory GSInstancing ClipControl DepthClamp`, and that it could not create a shared context
for background shader compilation.

**What the tests leave on the device.** The five engine tests and the large-import test remove the
bundles, saves and session folders they make (`TestGames`; checked: the same number of bundles and
save files before and after a run of all six). That was added after the regression above, to the
test APK only; the app APK is the one named. Runs made before it, while the consoles were being
brought up, left 17 test states of 27 to 40 MiB each (about half a gigabyte) in the app's private
`files/saves` on the RP5. They belong to no game in any library and can be deleted; nothing here
deleted them.

**Not exercised.** Fetching Play! and Dolphin from the network into a clean build: on this machine
their sources were supplied with `-Pps2Source` and `-PgamecubeSource`. A `.cdi`, `.rvz`, `.gcz` or
`.chd` of a real disc: their recognition is tested on the host from headers, their loading is the
engines'. Free-space refusal on a full device.

## Consoles — 2026-10-11 (ADR 0022)

Working tree on top of `ef2d9f7` with the Android-apps work, versionCode 17. Two builds are
described and they are kept apart. Everything this section says was "written and not yet run" has
since run: `SaturnCoreTest`, the option read-back in `ConsoleCoresTest` and `Ps1DeviceTest` on the
new disc importer all passed in a 51-of-51 regression (442.4 s) on the build that followed, and
again in the 57-test regression above.

**Final build (host only).** Both APKs build (debug APK 28.5 MB with six cores, SHA-256 begins
`532b4a439832b842`); **85 host tests
pass** (79 before, six new in `SystemRegistryTest`); lint has zero errors and 25 warnings, none in
new files; 10 asset checks pass. The RP5 was no longer attached when this build was ready, so
**nothing in it has run on the device**. That covers three things written after the device run
below: Saturn (`SaturnCoreTest`, two tests), the option read-back block added to
`ConsoleCoresTest`, and the disc importer that now serves PlayStation and Saturn
(`DiscImporter`, replacing `Ps1Importer`; PlayStation identities are computed in the same order
from the same bytes, but `Ps1DeviceTest` has not been repeated on it). Run
`tools/Test-Device.ps1 -Suite All` (51 tests expected) before relying on any of the three.

**Build before Saturn (host and RP5).** Registry refactor, the shared core shim and the Snes9x,
FCEUmm and Genesis Plus GX cores: 84 host tests passed. On the connected RP5 (Android 13,
`RP5_V1.0.0.189_20251128_174732_user`) the full regression was **49 of 49 in 434.8 s**: the 48
earlier tests unchanged, which exercise Game Boy, Game Boy Color, Game Boy Advance and PlayStation
through the refactored registry, importer, options, firmware store and save bridge, and the new
`ConsoleCoresTest`.

- **The registry, on the host.** `SystemRegistryTest`: the four stored console names, their order,
  media, label sizes, library files and state revisions are what they were; every definition is
  complete; no folder name, private extension, library file or revision is claimed twice; files are
  accepted or refused by their first bytes with the named console's own reason (`NES␚`, `SEGA` at
  0x100 and 0x101, the copier header, `MComprHD`, size floors); `.md` and `.exe` are tried but not
  announced outside their console's folder and `.bin` is a Genesis game only inside one; a shared
  container follows the folder and PlayStation otherwise; a disc's first sector names Saturn, and
  names Dreamcast and Sega CD as unplayable; single-file and bundle paths; every core's state
  envelope and cross-core refusal; options per core with nothing leaking between cores; firmware by
  content for PlayStation and both Saturn regions; provider platform names; shelf order.
- **Five consoles on the RP5.** `ConsoleCoresTest` generates nothing on the device: it takes the
  programs from `tools/probe/generate_console_probes.py`, gives each a fresh identity, imports it
  through the production single-file picker path and plays it through the production interface.
  For each of Super Nintendo, NES, Genesis, Master System and Game Gear it read the presented
  surface until the program's white-and-red checkerboard was there, sent Android's A, read
  white-and-green, paused and found `0x43` and the choice in the battery file at the program's
  offsets, saved a state and found the core's revision in its envelope, toggled back, loaded the
  state and saw the saved picture, toggled again, returned to the library, started the game again
  and saw the kept choice in the first picture, changed it, recreated the Activity and saw it again.
  The mesh pool stayed at 45 objects; the five consoles appeared as five shelves in registry order.
  From the device log of the passing run:

  | Console | Battery memory read back | Non-silent PCM frames |
  |---|---|---|
  | Super Nintendo | 8192 bytes | 0 (the program loads no sound driver) |
  | NES | 8192 bytes | 19167 |
  | Genesis | 4 bytes (Genesis Plus GX reports memory up to the last written byte) | 18398 |
  | Master System | 2 bytes | 19871 |
  | Game Gear | 2 bytes | 19135 |

- **What that run found.** The first C++ core linked without the C++ runtime: with a space in the
  project path CMake calls clang by its 8.3 name, which hides the `++` the driver reads its mode
  from (fixed with `--driver-mode=g++`). Genesis Plus GX keeps cartridge memory out of a state, so
  after Load State the picture is the saved one and the save is not; the test states that per core
  instead of expecting one behaviour. Genesis Plus GX gives an unlisted Master System image the
  Sega mapper, and with it cartridge RAM, only above 48 KiB, so that program is 64 KiB. An exact
  pixel fingerprint was fragile for the NES picture, whose size settles a frame after start; the
  test reads the mean colour instead, which is also the stronger claim.
- **Not covered by any of this.** Commercial games on the new consoles, special cartridge chips,
  second controllers, the Super Nintendo's sound, long sessions, heat and battery, and everything
  about Saturn on hardware. Saturn speed on the RP5 is unmeasured.

Android apps, 2026-10-11 (ADR 0021; working tree on top of `ef2d9f7`, versionCode 17, debug APK
SHA-256 begins `547ab9a8e084a7d5`): both APKs build; 79 host tests pass (the 64 before and 15 new);
lint has zero errors and 24 warnings, as before and none in the new files; 10 asset checks pass. On the connected
RP5 (Android 13, `RP5_V1.0.0.189_20251128_174732_user`) the full regression on that build is
**48 of 48 in 389.5 s**: the 44 earlier tests, unchanged, and four in `AppsLibraryTest`. Two test
classes were extended after that run (Android's own change report; B from the empty library) and
run again with `SurfaceHandoffTest` on the same app build: 6 of 6 in 70.3 s.

- **Where B leads, on the host.** `ShellNavigationTest` builds the production `ConsoleShell` over
  the production `CollectionCoordinator` with a fake engine and repository. B from the library at
  rest opens Apps and B again returns with the same selection, shelf, focused game and viewport
  generation. With the system menu, details, a sheet on details, settings two levels deep, a dialog
  or a library search open, B closes that and Apps is not opened. Android's Back closes the same
  things and does nothing at the root. With a game running the shell reports `Gameplay` (the router
  then never asks it) and refuses Apps even when asked directly; paused, B resumes.
- **The list, on the host.** `AppLibraryTest`: locale order where byte order would differ, one entry
  per launcher activity, a stable order for apps that share a name, grid movement at every edge,
  focus restored from the stored id, focus handed to a neighbour when the focused app is removed
  (from the end and from the middle), search without case or accents that keeps the found app when
  cleared, a failed read that is reported only when there is nothing to show, overlapping reads
  folded into one, and a refused or throwing launch that re-reads instead of failing.
- **On the RP5, with a controller.** `AppsLibraryTest.backOpensAppsFromTheLibraryAtRestAndNowhereElse`
  sends real key events to the production Activity: START then B, X then B and Android's Back leave
  it on the library; B opens Apps; the D-pad crosses the grid and stops at its edges; the focused
  tile is the one drawn selected after scrolling; A and a tap both start it; B returns to the same
  game at the same position with the same viewport generation. A probe game is then started: two
  presses of B leave it running with nothing open; paused, B resumes; after Return to Library, B
  opens Apps again. `theRouterKeepsAndroidBackApartFromTheControllersBackButton` checks the roles
  with real `KeyEvent`s in every input mode, including the confirm/back swap.
- **Android itself.** `installedAppsAreListedFromAndroidAndSettingsOpensAndReturnsToAnUntouchedConsole`
  uses the device's launcher service. It listed 38 entries from 38 packages in 41 ms (first call in
  the process), equal to the set `PackageManager` returns for MAIN + LAUNCHER, none of them
  Cartridge, no duplicates, in collator order; `adb shell cmd package query-activities`, which
  package visibility does not filter, also lists 38, so the `<queries>` declaration hides nothing
  here. Thirteen icons were drawn at 126 px in 68 ms. Toggling one of this build's own debug-only
  activities made Android report the package as changed, and the open list was read again; the
  component was put back and `SurfaceHandoffTest`, which uses it, passed afterwards. A on Settings
  brought Settings to the front (the console's Activity left RESUMED, the active window's package
  was Settings', no session was touched). Android's Back returned to the same Activity instance,
  still on Apps, still focused on Settings and drawn so, with the library's game and viewport
  generation unchanged and its 3D surface still present.
- **Apps that change, and a rebuilt console.** `appsThatChangeWhileShownKeepFocusValidAndARebuiltConsoleReturnsToThem`
  removes the focused app (its neighbour takes focus, the count drops), installs one (it appears in
  order, focus stays), refuses a launch (the message names the app and no package), searches on Y,
  clears with B, recreates the Activity (Apps is open again on the same app; an app without an icon
  still has a tile), then empties the list (*No apps to show*, A reads again, B returns).
- **Seen.** `UxTourTest` now ends in Apps and saves `25-apps` and `26-apps-search`; they and the
  eight library and welcome captures that gained the `B Apps` hint are in
  [ux/screenshots](ux/screenshots). The tour draws invented apps. The grid with the device's real
  icons was captured once to the device and looked at, not committed: adaptive and legacy icons,
  two-line names and the focused tile read correctly at 1920×1080. Looking found one defect: in the
  Vertical layout the new hint had larger gaps than its neighbours, because a hint that is also a
  touch control is 12 dp taller. The same was already true of *Clear search* there. Fixed in the
  list, not in the hint, and captured again.
- **Not covered.** Returning with the Home button while Cartridge is the default HOME app (no test
  changes the owner's HOME choice). A real install or uninstall of another app: the change report
  was exercised with Cartridge's own package, and list changes with a fixture. Android reclaiming
  the process while an app is in front: `recreate()` is a rebuilt Activity, not a killed process.
  The owner's library was not opened. Analog-stick feel on the grid.


Start from save state, 2026-10-10 (the commit after `afcd58e`, merged to `master`;
versionCode 17, debug APK SHA-256 begins `5b5a105dfb2a6956`): both APKs build; 64 host tests pass;
lint has zero errors and 24 warnings; 10 asset checks pass. On the connected RP5 (Android 13,
`RP5_V1.0.0.189_20251128_174732_user`) the full regression on that build is **44 of 44 in 373.1 s**.

- **The option.** `GameOptionsDeviceTest` plays the original Game Boy probe, whose A button flips its
  picture and one byte of battery memory. It saves a state on the flipped picture, flips back and
  leaves. With the option off the game starts from the beginning. Turned on once under Settings ›
  Gameplay it is stored for both core families, and the next start shows the picture the state was
  saved on, with no notice. The battery byte read back afterwards is the one saved last, not the
  state's. A per-game override returns that game to a fresh start while the console setting stays
  on. With 4 KiB of junk in place of the state the game still starts from the beginning, the notice
  appears, and the file is byte-identical afterwards. With no state there is nothing to report.
- **Two defects found by this run, neither in the new option.** The first two full runs were 43 of
  44, both in `LibraryGrowthTest`. (1) A label never appeared after a recreation; repeating the test
  with its predecessors reproduced a `ConcurrentModificationException` in the label cache. Since
  `afcd58e` labels decode three at a time, and under the Compose test dispatcher their results can
  be delivered on the decoding threads, two at once. The cache is now synchronized; that test and
  `BrowseStabilityTest` then passed twelve times in a row. In the installed app results are
  delivered on the main thread, so this should not have shown there; that is reasoning from the
  dispatcher, not something measured. (2) The test pressed Refresh Library before the row existed:
  the folder list loads asynchronously and the library is now ready sooner after a recreation.
  `ToolShelf` waits for a control to appear before using it, as a player would.
- **Not covered.** A PlayStation state at a start (the code path is shared, the PS1 revision check
  and core are not exercised by this test), and any commercial game.


Console feel, 2026-10-10 (ADR 0020; commit `afcd58e`,
versionCode 17, debug APK SHA-256 begins `7cc3c7ca787c9f4d`): both APKs build; 62 host tests pass;
lint has zero errors and 24 warnings; 10 asset checks pass and the Khronos validator reports zero
errors and zero warnings for the three GLBs. On the connected RP5 (Android 13,
`RP5_V1.0.0.189_20251128_174732_user`) the full regression is **43 of 43 in 354.9 s**: the 38
earlier tests, four in `LaunchExperienceTest` and `LibraryPacingTest`. That run was on the build
before a one-line lint fix (`createBitmap`); the eleven launch, collection, GB/GBC and PS1 tests
were run again on the final build and pass.

- **The lag, measured before and after.** `LibraryPacingTest` runs the production activity with a
  mixed shelf, 40 decoded image labels and the D-pad held at the console's 110 ms repeat, and reads
  presentation times from SurfaceFlinger. Before the change, at `a4e52ce`: 40 games, 60.0 fps in
  every phase; 800 games (`-e games 800`), 54.7 fps with the D-pad held, p95 33.28 ms, 29 doubled
  frames in six seconds. After: 800 games, 60.0 fps held in all three layouts with no doubled
  frame; 3,000 games, 60.0 fps held in all three layouts with no doubled frame. While the library
  rendered, the GPU read 54 % busy at 305 MHz, its lowest clock, against a 670 MHz maximum, with the
  imported models on screen. The models were not changed.
- **Starting and leaving a game.** `LaunchExperienceTest` records every coordinator state through a
  launch and a return: the screen closes while the library stage is still whole, is never open on
  the hand-off or on a core that is not yet running, and on the way back is never open on a library
  that has not drawn. It also reads the Game start setting back from preferences after recreation.
  Real-time captures of all three transitions at about 60 ms intervals were looked at once: Fade
  and Shutter were right the first time, Iris went straight to black because emptying a path reset
  its fill rule, and was corrected and captured again. The stage reported ready 93–222 ms after it
  was created; the probe game's picture was seen 4–77 ms after the core ran.
- **Focus.** The same test focuses the disabled Load State row in the pause menu and finds it
  selected; the captured frame shows its fill and mark at full strength with the words dimmed.
- **Shelves and the stick.** L1/R1 from a console whose third game was focused, to PlayStation, on
  to its third disc, back, forward and on to All Games: first game every time. A right stick
  injected as `AXIS_Z`/`AXIS_RZ` turns the object past 60°, tips it, and it is back at zero within
  six seconds of release; moving the focus returns it at once; it does nothing behind the system
  menu and works beside Details.
- **Frame rate readout.** Off by default; turned on from Settings › Advanced it shows 55–61 on this
  60 Hz panel and disappears when turned off.
- **Not covered.** The owner's own library was not opened, so its real size and artwork are still
  untested by me; the readout above is there for that. No commercial game was started: the wait for
  a non-blank frame is unit-tested and the probe games show a picture at once, so the white opening
  frames of a real Game Boy Advance game have not been seen being hidden. The right stick was
  injected, not pushed. The device's navigation pill is visible in the refreshed screenshots; it
  also shows over the unchanged profiling activity, so it is the device's state, not this change.


Media models and browse stability, 2026-10-10 (commit `a4e52ce`,
versionCode 17, debug APK SHA-256 begins `1f7fcb556a98c31c`): both APKs build; 59 host tests pass,
two of them new layout-window tests; lint has zero errors and 24 warnings; 11 asset checks pass and
the Khronos validator reports zero errors and zero warnings for all four GLBs. On the connected
RP5 (Android 13, `RP5_V1.0.0.189_20251128_174732_user`) the full regression is **38 of 38 in
276.3 s**: the 37 existing tests and the new `BrowseStabilityTest` (`Test-Device.ps1 -Suite Browse`).

- **The flicker.** `BrowseStabilityTest` puts 30 original titles of all four systems on one shelf
  and steps along it with the D-pad in each layout while an observer reads the renderer on every
  presented frame. Horizontal and Vertical: 194 frames each, 9 steps out and 9 back, 7 label
  uploads each way, which is exactly the 7 games that entered the window. Grid: 137 frames,
  4 rows down and back, 6 uploads each way. In no frame was an object on stage without its own
  game's label. Before this change every step past three games released and re-uploaded all three
  visible labels and showed the template label in between; that was read from the code, not
  measured with this test, which did not exist then.
- **Labels on the new objects.** `RenderRefinementTest` (asymmetric four-colour label on the GBA
  cartridge), `CollectionUiTest`, `CollectionLayoutDeviceTest` and `Ps1DeviceTest` sample label
  colours from the real GPU surface and pass unchanged, so orientation and placement hold.
- **Rate.** `RenderPacingTest` on the same build: median 16.66 ms in every phase; p95 16.91 ms at
  rest, 16.76 ms rapid navigation, 16.73 ms insertion, 16.76 ms vertical, 16.74 ms grid; no gaps
  in 2,940 presented frames; CPU submission p95 2.83 ms (2.47 ms with the generated models),
  3,241 of 3,241 frames submitted.
- **Seen.** The 26 tour screenshots were captured again and looked at; docs/ux/screenshots holds
  them. A separate one-off capture of the insertion phase showed the new cartridge entering the
  receiving slot with clearance on both sides.
- **Not covered.** The owner's own library was not opened. With scraped artwork a label takes
  longer to decode than the typeset ones used here, so on a held D-pad an arriving object can
  appear a moment late; it cannot appear with another game's label. The look of the models under
  the studio light is the owner's call.


Console-experience redesign, 2026-10-10 (commit `a4e52ce`, versionCode
17): both APKs build; 57 host tests pass; lint has zero errors and 24 warnings; 12 asset checks
pass. On the connected RP5 (Android 13, `RP5_V1.0.0.189_20251128_174732_user`) the full regression
is **37 of 37 in 262.5 s** — the 35 existing tests, reaching controls through the new interface,
plus `GameOptionsDeviceTest` and `UxTourTest`. Render pacing is unchanged: median 16.66 ms, p95
16.73–16.78 ms, no gaps over 2,941 presented frames, CPU submission p95 2.47 ms. Details, the
screenshots the tour captured, and what still needs the owner's hands are in
[ux/IMPLEMENTATION_STATUS](ux/IMPLEMENTATION_STATUS.md).

Notes for whoever runs these next:

- Tests reach controls through `androidTest/ToolShelf.kt`: `ui.tool("choose-game")`, `"insert"`,
  `"rescan"`, `"hide"` and so on map each job to where it now lives, and `ui.home()` returns to the
  library with on-screen controls. Row tags are ids (`system-add`, `settings-export`,
  `option-playstation.sharp3d`), not positions.
- Opaque pages pause the 3D stage. Observe the renderer from the library (`ui.home()` first).
- An import with something to report stays open as a dialog; `home()` dismisses it. Scan outcomes
  are read from `shell.lastScan` and `shell.scansFinished`, not from the status message.
- The focused object stands at the left while a sheet describes it, including the label preview.
  Pixel checks use `MainActivity.labelCentre()`.
- `DebugCollectionActivity` uses its own firmware folder. Before this, two `Ps1DeviceTest` cases
  failed on this device with both the old and the new build, because a real BIOS had been imported
  into the app after the last recorded regression and those diagnostics assume the built-in BIOS.
  The owner's file was not modified.
- `tools/Test-Device.ps1 -Suite Tour` and `-Suite Options` were added. Under Windows PowerShell 5.1
  the script stops at its first `adb push` (stderr progress becomes a terminating error); the runs
  above used the same `am instrument` command through adb directly. PowerShell 7 is unaffected.
- `UxTourTest` writes `files/ux-tour/*.png` on the device:
  `adb exec-out run-as dev.cartridge cat files/ux-tour/01-welcome.png > 01-welcome.png`.

PS1 increment, 2026-10-10: the source-built arm64 PCSX core, scoped disc imports and actual native
video/audio/card/state/recreation tests run on the connected RP5. Both APKs build, 48 host tests and
35 device regression tests pass; lint has zero errors/26 warnings, 12 asset checks and four GLB
validators pass. The focused PS1 suite passes 3/3 in 25.173 s; full regression is 213.877 s with
50 handoffs. See [PS1_PROGRESS](PS1_PROGRESS.md) for dated results,
failures corrected, exact scope and reproducible `Test-Device.ps1 -Suite PS1` / `-Suite All` commands.
Owner BIOS-backed commercial games, disc label review, PAL compatibility, controller mode/audio latency,
and longer play remain explicit physical checks. Historical M0–M6 results below retain their dates.

## M0 host results — 2026-10-09

Validated on Windows with Microsoft OpenJDK 21.0.8, Gradle 8.13, SDK 35, NDK 27.2.12479018 and
SDK CMake 3.22.1. Initially no Android device was attached; physical-device results follow below.

| Check | Actual result |
| --- | --- |
| `:app:assembleDebug` | Passed, real arm64 APK and source-built mGBA core |
| `:app:assembleDebugAndroidTest` | Passed; device-test APK compiled and subsequently executed on RP5 |
| `:app:testDebugUnitTest` | 5 tests, 0 failures/errors |
| `:app:lintDebug` | Passed, 0 errors, 13 warnings; dependency-update/orientation/arm64-only and API-style warnings remain |
| `python tools/test_assets.py` | 4 structural GLB tests passed |
| `tools/Build-Probe.ps1` | Original ARM diagnostic assembled and exported successfully |
| mGBA ELF inspection | AArch64 shared library, Libretro symbols exported, LOAD alignment 0x4000 |
| Default CMake FetchContent path | Passed; pinned source downloaded and SHA-256 verified |
| Device controller/render/audio/handoff | See the dated RP5 results below |

The first compile error (ClearOptions colour array type) and AndroidX dispatch-hook lint error were
corrected. The narrowly scoped RestrictedApi suppression is explained at that public Activity hook.
Upstream mGBA compiler warnings are recorded in build logs; they did not prevent linking. Older shell
dependencies are intentionally pinned, not silently upgraded to the current SceneView toolchain.

Unit tests cover illegal/duplicate lifecycle operations, error recovery to a retry, selection clamping,
spring retargeting, held-axis repeat without new events, save isolation, overwrite and path rejection.
Asset tests check binary chunk/range/index validity, physical metre-scale depth, normals/winding and
the embedded, separable label texture. They do **not** prove GPU decoding or visual quality.

Reproduce:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
python tools/test_assets.py
```

Reports: `app/build/test-results/testDebugUnitTest/`, `app/build/reports/tests/testDebugUnitTest/`,
`app/build/reports/lint-results-debug.html`. Local build logs are ignored under `.tools/`.

## Physical RP5 preparation

Enable Developer Options and USB debugging, connect USB, and authorize this computer on the device.
Do not claim the hardware is an RP5 based on an emulator or a product-page assumption.

```powershell
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
adb shell getprop ro.product.cpu.abilist
adb shell wm size
adb shell dumpsys input
adb shell cat /proc/meminfo
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.cartridge/.MainActivity
```

Record firmware, button layout, controller ID/source/key codes, display size/refresh rate, ABI, and
available RAM in a dated result entry. Keep personal device logs out of Git.

## Physical RP5 results — 2026-10-09

The owner enabled USB debugging and authorized this PC. ADB identified Retroid_Pocket_5/kona,
Android 13/API 33, firmware `RP5_V1.0.0.189_20251128_174732_user`. ABI includes arm64-v8a. Filament
identified Adreno 650 and GLES 3.2. The physical panel reports 1080×1920 and 60.000004 Hz; the app's
landscape screenshot is 1920×1080. `/proc/meminfo` reports 7,885,048 KiB total RAM (~7.5 GiB).

The built-in Retroid Pocket Controller reports vendor/product 2022:3001 and gamepad/joystick sources.
The device key-layout file maps D-pad, A/B, L1/R1, Start/Select and L3/R3 to standard Android keys;
left-stick X/Y and HAT_X/HAT_Y are present. Physical face-button naming still depends on the selected
Retroid layout mode; do not assume every device uses the same preference.

| Device check | Actual result |
| --- | --- |
| Install app + instrumentation APKs | Passed on physical RP5 |
| Genuine cartridge GLB | Visible in an ADB screenshot, with textured label, sides and neighbouring instance |
| Physical D-pad and left stick | Owner confirmed both work smoothly, including held/reversed/rapid navigation |
| Initial three-cycle real surface test | Passed, 1 test, 2.170 s |
| Final 50 real Filament ↔ mGBA cycles | Passed, 1 test, 20.490 s; pause/save/load/resume/stop each cycle |
| Reproducible Test-Device.ps1 helper | Executed successfully with 3 cycles, 1 test, 2.698 s |
| Actual emulator framebuffer and input | PixelCopy verified gray → green on A, gray on B; first-buffer NO_DATA retried within a bounded wait |
| Visible save-state restoration | Saved green, changed to gray, loaded state and observed green from the GPU surface |
| Missing/invalid ROM recovery | Missing private path and 192-byte zero-filled file reported Error, stopped to Idle, then all 50 valid sessions ran |
| Audio/ordinary Compose pause menu | Owner confirmed gameplay, audio, pause/resume, eject and state save/load through the ordinary app |
| Collection memory snapshot | 177,901 KiB PSS (~174 MiB); a single snapshot is not a leak or performance verdict |
| Frame pacing, battery, latency | Not profiled; the device's FPS overlay is not a measured frame-time distribution |

Final fixture post-teardown PSS: cycle 1 = 144,711 KiB (~141 MiB), cycle 10 = 171,732 KiB (~168 MiB),
cycle 50 = 184,809 KiB (~180 MiB). These snapshots increase during the short run; they do not establish
a plateau or prove a leak. Follow up with longer runs, managed/native allocation and GPU profiling.
The restored ordinary library process was 170,488 KiB PSS (~166 MiB). No app-specific Java fatal
exception or native fatal signal was observed in the captured regression logs.

The first test attempt timed out while the device was Dozing. After `KEYCODE_WAKEUP`, the three-cycle
test passed. The debug fixture now requests KEEP_SCREEN_ON. The stronger pixel test initially queried
before EGL's first swap and received ERROR_SOURCE_NO_DATA; only that status is retried. This was a test
synchronization fix, not a mocked rendering fallback. Personal screenshots/logs remain ignored in `.tools/`.

## Real surface instrumentation probe

The debug-only `SurfaceProbeActivity` alternates a rendered Filament cartridge and the real packaged
mGBA view three times by default (or up to 50). Each cycle waits for a 3D frame, tears Filament down,
waits for a core frame,
pauses, saves/loads, resumes, persists RAM/stops, and detaches. `SurfaceHandoffTest` checks completion
and captures failures. The first cycle samples real pixels, routes A/B and checks visible state restoration.
The fixture also probes missing/invalid ROM recovery before valid sessions. It requires a foreground
arm64 device with GLES 3; compilation alone is not a pass. Post-teardown PSS at cycles 1/10/50 is reported
for comparison, without claiming that short-lived samples establish long-term memory stability.

The original diagnostic assembly has no proprietary logo/BIOS/data. It fills GBA VRAM, latches green
on A and resets gray on B, and enables a square-wave tone. It needs mGBA's HLE BIOS; no retail hardware
boot claim is made. It is generated only in ignored `.tools/probe/`, never bundled in the app.

On Windows:

Build both APKs first, unlock/authorize the device, then use the reproducible helper:

```powershell
.\tools\Test-Device.ps1 -Suite All -Cycles 50
```

Pass `-Sdk C:/path/to/Android/Sdk` and optionally `-Serial <serial>` when needed. It generates the original
probe, installs both APKs, pushes the fixture, wakes the screen, runs instrumentation and checks the
JUnit result (ADB's process exit code alone is insufficient). `-Suite Surface` is the default and runs
one test; `-Suite Collection` runs two ordinary UI tests; `-Suite Scrape` runs five parser/migration/chooser
tests; `-Suite Library` runs two folder/durable-queue tests; `-Suite Battery` runs two battery-save tests;
`-Suite Render` runs two GPU orientation/settings/presentation tests; `-Suite All` runs all fourteen tests.
`-Suite Live` opts into one real API test using
a fresh candidate cache, requiring device internet. It retrieves no commercial image or ROM. Logs stay in
ignored `.tools/device-test.log`. Collection tests stub only document-picker results, then use the real
ContentResolver, Room database, Filament and mGBA. Their database/cache is isolated from the owner's library.
Equivalent individual commands:

```powershell
.\tools\Build-Probe.ps1 -Sdk "$env:ANDROID_HOME"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push .tools/probe/cartridge-probe.gba /data/local/tmp/cartridge-probe.gba
adb shell run-as dev.cartridge cp /data/local/tmp/cartridge-probe.gba files/cartridge-probe.gba
adb shell input keyevent KEYCODE_WAKEUP
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

After installing both APKs, the stress variant can be run directly (add `-s <serial>` to `adb` when
multiple devices are attached; keep actual serials out of committed scripts):

```powershell
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e cycles 50 -e class dev.cartridge.SurfaceHandoffTest dev.cartridge.test/androidx.test.runner.AndroidJUnitRunner
```

If this workspace's local SDK is used, omit `-Sdk` to use `.tools/sdk`. On another OS, assemble the
same `.S` using its NDK clang `--target=arm-none-eabi -march=armv4t -nostdlib -fuse-ld=lld
-Wl,-Ttext=0x08000000`, then use `llvm-objcopy -O binary`. Do not use an unknown downloaded ROM.

For manual testing, copy the generated probe to a user-visible location via ADB and choose it through
the ordinary SAF picker. A passing instrumentation probe still needs manual audio/input/pacing checks.

## Manual acceptance checklist

- [x] Actual original GLB visible under warm lighting, correct label orientation and thickness; 3D
  rotation exposes sides/contacts. Focused cartridge larger, neighbours visible.
- [x] D-pad/left-stick navigation, holding/reversing and rapid scrolling confirmed by owner.
- [ ] Quantified neutral/dead-zone/long-hold tests, 20 seconds of rapid scrolling and retargeting at edges.
- [ ] Touch arrows/Insert/picker work. Cancelled SAF selection leaves the same cartridge focused.
- [x] Assign original probe/user-owned .gba; A starts real mGBA frames in the same Activity/application.
  Probe A/B input and owner gameplay confirmed. No external emulator intent or menu.
- [ ] Verify every gameplay A/B/L/R/Start/Select mapping in each supported Retroid layout mode.
- [x] Owner confirmed audible game audio. Long-duration crackle/timing/input latency measurement remains open.
- [x] Owner confirmed L3+R3 pause/resume and eject in the ordinary app.
- [ ] Check alternate Start+Select, Back, held-input release, every menu D-pad/A/B mapping, overlay
  readability/z-order during resizing and stopped audio/frame progression while paused.
- [x] GPU probe proved visibly changing save/load; owner also confirmed ordinary app state save/load.
- [x] Original SRAM diagnostic proves battery continuity across eject/relaunch and backgrounding,
  restoration before the first frame, automatic checkpoints and failed-write retry. No state file used.
- [ ] Owner-game battery continuity, real reboot/process death and sleep/suspend. Missing state leaves
  gameplay paused with an error; corrupt/incompatible data reports failure.
- [x] 50 native handoffs passed, with missing/invalid ROM recovery and no crash.
- [ ] Ordinary UI 50-cycle insertion/ejection stress: no duplicate session/thread or stale surface, same
  selected cartridge restored, no crash. Inspect logs for native/GL errors.
- [ ] Deleted/unreadable ROM, too-large/wrong-extension file and invalid core build: truthful loading
  failure, safe cleanup and selection preserved; subsequent valid game still launches.
- [ ] Backgrounding, sleep/suspend and focus loss while Preparing, Running, Paused and saving. M0
  intentionally stops sessions and persists RAM on background. No assertion of seamless resume.
- [ ] Activity recreation and surface destruction/recreation; pause overlay resizing; switch back
  repeatedly. Core context-loss recovery must be fixed before advancing if it is observed.
- [ ] External controller unplug/disconnect while buttons held releases all input and pauses.
- [ ] Ten-minute collection/gameplay runs: measure CPU/GPU frame time, audio timing, PSS/native/GL
  memory, thermals and battery. Compare memory after 1/10/50 handoffs; no continuing Filament loop
  behind gameplay. RP5 60 FPS is a measured target, not a host-build assertion.

Use Android Studio profiler/Perfetto for pacing/CPU/GPU and `adb shell dumpsys meminfo dev.cartridge`
for snapshots. SurfaceView frames are not fully characterized by Compose frame metrics alone.
Record median/p95/p99 frame times and observed jank, not just an average FPS.

## M0 acceptance

Buildability, genuine Android GLB rendering, physical controller navigation, real internal GBA frames
and repeated native surface handoff pass on the RP5. The owner confirmed ordinary gameplay/audio/menu/
save-load/eject. **M0's five technical feasibility criteria passed.** The broader lifecycle/performance
checklist above remains M1 hardening work; this is not production-readiness acceptance.

## First M1 increment results — 2026-10-09

Same physical RP5/firmware as above. Version 0.1.0-m1-dev, versionCode 2. This adds Room 2.7.2,
KSP 2.1.20-1.0.32 and the matching Room Gradle schema plugin. Both APK builds, unit tests and lint
pass. Eleven unit tests (five domain, six coordinator) have zero failures/errors; four GLB checks pass.
Lint has zero errors and 20 warnings, including pinned-dependency updates, orientation, arm64-only,
debug-fixture backup and API-style suggestions. The Room plugin emits Gradle 9 deprecation notices;
the supported/pinned build uses Gradle 8.13.

Executed `Test-Device.ps1 -Suite All -Cycles 50`: **OK (3 tests)** in **30.628 s**. The native test
retains framebuffer/input/state/error recovery and 50 surface cycles. Two new tests run ordinary
production UI in DebugCollectionActivity with isolated on-disk Room databases:

- Choose/import an original probe; insert, pause, save/load, resume, background to CREATED, return
  and relaunch. Recreate while Running, restore the collection, cancel insertion on background,
  retry, pause and eject. This proves stop-and-return lifecycle recovery, not seamless game restoration.
- Assign two original diagnostic ROM identities and red/blue label images. PixelCopy of the actual
  SurfaceView proves independent labels; Fit/Crop/rotation recipe, Activity recreation, cancel,
  identical-ROM reimport and invalid-image recovery preserve choices. Close the database and reopen
  it with a new Activity; stored slots, focus and recipe restore and the real red label renders.

The picker response alone is stubbed with Espresso Intents; the file provider is debug-only and
nonexported. Native emulation and rendering are never stubbed. UI tests wait for Compose before polling
the independently clocked SurfaceView. The initial texture upload failed until GEN_MIPMAPPABLE usage
was added, and the initial selection-pixel assertion read a stale Compose update; both were fixed and
the final full suite rerun. Local logs/fixtures remain ignored. No new long-term PSS/pacing result is claimed.

The owner manually confirmed physical X/Y label selection, controller navigation, Fit/Crop/rotation,
label placement/application, switching cartridges and reopening persistence. Manual asymmetric scan
orientation/fidelity across multiple images and broader controller/input/performance checks remain.
The checklist above records M0 scope; these new tests cover a subset of its previously open lifecycle
items, not every listed suspend/save/context-loss combination. M1 remains in progress.

## API scraper increment results — 2026-10-09

Version 0.1.0-m1-scrape, versionCode 3, same RP5/firmware. Both APK builds, unit tests and lint pass;
13 host tests (five domain, six coordinator, two scrape-queue) have zero failures/errors. The queue
tests cover scope deduplication, no automatic writes, cancel/retry and checked-field-only acceptance.
Four structural GLB checks pass. Lint has zero errors and 20 existing warnings.

`Test-Device.ps1 -Suite All -Cycles 3`: **OK (6 tests)** in **15.824 s** on the final reviewed build.
The earlier 50-cycle native test remains the recorded stress result; this increment repeats three
native handoffs plus ordinary collection/session tests and three new scraper tests:

- Provider parsing preserves regional aliases/descriptions, rejects unsupported image hosts and
  keeps flat labels separate from box art. Fixture data/artwork is original.
- Create the actual exported v1 SQLite schema, seed ROM/label/focus, then open through production
  Room migration v2. Independently merge metadata and extra media; close/reopen and preserve identity,
  original title, label, focus and accepted choices. The fixture initially assumed every entity had an
  indices array; optJSONArray now follows the exported schema. No destructive fallback is used.
- Ordinary Compose scraper UI selects a year from record A, previews/applies A's label on real
  Filament, then accepts a developer and box art from record B. Activity recreation retains separate
  provenance and original ROM title. Only provider responses and SAF picker results are stubbed;
  database, files, UI, 3D upload and emulator regression are real. Isolated test data avoids owner writes.

`Test-Device.ps1 -Suite Live`: **OK (1 test)** in **1.851 s**, with a fresh cache on the final build.
The earlier live probe also passed in 2.116 s. Real RP5 internet/API returned the GBA
record and EU/JP/US support-texture URLs. This probe downloads no artwork and is separate from the
deterministic suite; service failures are reported rather than hidden with a fixture fallback.

The owner then confirmed **real remote thumbnails, applying a scraped label and persistence after
reopening all work**. This closes the initial actual-provider artwork proof; it does not establish
complete Socket coverage, disc support or directory-scale scraping.

Reproduce the scraper regression and optional live contract independently after building:

```powershell
.\tools\Test-Device.ps1 -Suite All -Cycles 3
.\tools\Test-Device.ps1 -Suite Live
```

Remaining physical scraper checks: controller-only bulk review/skip/variant selection, interrupted
network and rate-limit retry, cancel during download/write, apply label+metadata then disable Wi-Fi
and cold-launch/play, asymmetric regional-label fidelity, and accepting/replacing each media category.
Queue restoration after process death and accepted-version undo are not implemented. Longer collection/
gameplay profiling, SaveRAM continuity, sleep/context loss and held-controller disconnection remain
the M1 checklist above. M0 remains passed; M1 is not declared complete.

## Larger library / durable queue results — 2026-10-09

Version 0.1.0-m1-library, versionCode 4, same physical RP5 and firmware. Both APKs compile, all
16 host tests pass (eight coordinator, five domain, three queue), four GLB structural checks pass,
and lint has zero errors/21 warnings. The extra warning is a String.toUri style suggestion; remaining
warnings are the existing pinned-version/orientation/ABI/backup/API-style items. No broad suppressions.

`Test-Device.ps1 -Suite All -Cycles 50`: **OK (9 tests)** in **41.485 s**. The final reviewed build,
including linear snapshot mapping and a bounded journal reader, then passed the same nine tests with
three native cycles in **23.428 s**. The provider API adapter/dependency pins are unchanged; the separate
actual API/artwork evidence is in the preceding entry.

New physical tests:

- A debug DocumentsProvider presents nested original diagnostic files, a duplicate, an invalid short
  .gba and ignored text. Choose the folder through the ordinary Compose action (picker/grant alone
  stubbed). Five new content identities plus one existing game become six slots; duplicate/invalid
  counts are correct. Repeat Rescan and reopen the database with zero added duplicates.
- Apply blue original artwork to game six. PixelCopy reads real GPU label pixels beyond the original
  three slots, across Activity recreation and database closing/reopening. Insert that sixth ROM into
  actual mGBA, pause/eject, restore focus and blue pixels. Renderer/emulator are never mocked.
- Cancel a scrape during its second provider request, recreate the Activity and reopen the on-disk
  journal. Resume retries only pending work; completed candidates reload from disk. Next-title review
  index and independently accepted year metadata survive another recreation. The provider responses
  are original fixtures, not live service calls.
- Seed the exact exported v2 schema with scraped label/metadata/media provenance and focus; upgrade
  through v2→v3, append/deduplicate games and register a directory without changing previous choices.
  The existing v1 migration test now upgrades through v1→v2→v3. All schema JSON is versioned.

The first compile caught a nullable-Unit return from journal reset. The first device run crashed before
tests because DocumentsProvider requires exported=true and MANAGE_DOCUMENTS protection; its debug
manifest was corrected and the full suite rerun. It has no DOCUMENTS_PROVIDER discovery filter, so it
doesn't appear in the system picker. Only the old single-document fixture is nonexported; the new
tree fixture is protected by the system-only permission, not generally accessible. The production
app requests no such permission and release builds contain neither fixture.

Reproduce after the normal build:

```powershell
.\tools\Test-Device.ps1 -Suite Library
.\tools\Test-Device.ps1 -Suite All -Cycles 50
```

Manual owner folder/grant/bulk-resume validation was requested on the installed build. Automated results
substitute picker/grant/provider responses as stated; physical reboot and full process-kill recovery
have not been measured. Still required: persistable folder grants after device reboot, revoked/missing
roots, read-only/cloud provider behavior, disk-full and cancellation during large copies/journal writes,
hundreds of real games with rapid navigation, offline cold-launch/play, held inputs/disconnection,
sustained CPU/GPU/audio/frame pacing/memory/battery, and suspend. One durable batch is implemented;
directory scan cursors and unapplied previews/checkbox drafts do not survive recreation. M1 remains open.

## Legacy duplicates and reversible removal — 2026-10-09

Owner validation confirmed folder import and saved batch resume work. The owner reported three copies
of one game and no removal control, and called the scraping review flow tedious. Its UX redesign is
explicitly deferred to a later version. Aggregate inspection confirmed seven assigned entries but five
distinct ROM hashes; two assignments were redundant. No personal titles, art, ROMs or database copies
are committed.

VersionCode 5 compacts entries and collapses legacy hashes without merging different-content versions
with the same title. Room v4 migration adds removal exclusions. B / Collection offers Remove and Undo;
X / Add .gba restores exclusions after reopening. All source files, working ROMs, saves and accepted
enrichment are retained. Undo's previous position is Activity state, so it is single-step/session-only.

Both APKs compile; **17 host tests** (nine coordinator, five domain, three queue), four GLB checks and
lint pass, with zero lint errors/21 warnings. **All 10 RP5 tests passed in 24.810 s** with three native
handoffs. New/extended checks:

- Upgrade the exact v3 SQLite schema containing three assignments of one hash and a second hash with
  the same title. Collapse to two, preserve focused identity and label, remove/reopen, suppress rescan
  re-addition, restore original order, prevent duplicate legacy assignment, empty the collection, and
  explicitly re-add with its label. Existing v1/v2 tests now migrate through v4.
- On game six, save a real mGBA state, eject, remove through the production UI, rescan, and Undo.
  PixelCopy proves the original blue GPU label returns at the original position. Repeat removal,
  recreate, rescan, then Add restores the same identity/label. Save-state bytes and original/private
  ROM files remain intact. All test data uses isolated databases and original diagnostic files.
- The saved-batch test also asserts the target manifest exists before the first provider request.
  Manifest/candidate/reset writes reject older IO operations across Activity instances.

Final review added the same stale-operation guard to journal resets. Both APKs/17 host tests/lint
were rerun successfully; the two focused LibraryGrowth/ScrapeResume RP5 tests passed in **9.379 s**
on that final source, and the helper reopened the production application. The full ten-test result
above covers the unchanged collection/migration/rendering code.

The first removal device run caught an exact-three-label requirement when the collection contained
one game. CartridgeSurface now pads its fixed label pool and places inactive instances outside the
far clip; the full suite then passed. No failure was masked or acceptance gate relaxed.

The installed owner's database upgraded to v4: five assigned entries, five unique hashes, zero
duplicate assignments, and unchanged game/enrichment counts. Focus preservation is proved by the
isolated migration tests; the earlier owner snapshot and later inspection were not simultaneous.
Manual Remove/Rescan/Undo validation is requested separately. Reboot, disk-full/process-kill timing,
large-library profiling, source grant recovery, SaveRAM continuity and sustained suspend/audio/memory
checks remain as listed above. M0 remains passed; M1 is in progress.

## Battery boot barrier and checkpoints — 2026-10-09

Version 0.1.0-m1-saves, versionCode 6, installed on the same physical RP5: Android 13/API 33,
arm64-v8a, Adreno 650/GLES 3.2, landscape 1920×1080 at 60 Hz, firmware
`RP5_V1.0.0.189_20251128_174732_user`. Both APKs compile; **19 host tests** (nine coordinator,
five domain, three queue, two SaveStore), four GLB structural tests and lint pass, with zero lint
errors/21 existing warnings. The original C11 JNI bridge exports its three SaveBridge methods and
has ELF LOAD alignment 0x4000. No dependency pins changed.

`Test-Device.ps1 -Suite Battery`: **OK (2 tests)** in **28.462 s**.
`Test-Device.ps1 -Suite All -Cycles 50`: **OK (12 tests)** in **70.337 s** on the final source.
The helper reopened the ordinary application after installing and testing version 6.

New physical checks use an isolated Room database and unique ROM hash. The original 296-byte ARM
diagnostic latches SRAM byte zero once at boot, turns green on A and gray on B. It is generated in
ignored `.tools/probe/`, never bundled. The ordinary Compose UI, engine and mGBA core are exercised;
GPU PixelCopy observes the actual framebuffer. Owner ROMs, library choices, labels and saves are untouched.

- Save a real 32 KiB battery file, eject and reinsert: the boot latch reads the restored value before
  retro_run. Repeat with changed RAM, then background/reopen and insert. No save-state file exists.
- Oversized native restoration is rejected without changing memory. An oversized stored battery file
  fails preparation, retains its original bytes and permits a fresh session after fixing the fixture.
- Observe the production 15-second checkpoint while Running. A directory at only the fixture's unique
  save destination forces a write failure; the app shows a warning, keeps RAM and succeeds after retry.
  This simulates an unwritable destination, not physical disk exhaustion.
- Deliberately skip graceful shutdown, release fixture native resources and recreate the Activity;
  the latest checkpoint boots green. This is abrupt fixture teardown, **not actual OS process death,
  force-stop or reboot**. Ordinary background shutdown is tested separately.
- Host tests reject oversize and empty-state writes/reads without replacing previous data. Competing
  SaveStore instances publish only complete byte arrays and leave no temporary files.

The first native build exposed missing C++ runtime linkage; this small public-C-ABI bridge now uses
C11 without another C++ runtime. The Windows competing-write test exposed AccessDenied during
replacement; shared-directory serialization fixed it. All required checks were rerun successfully;
no test was skipped or acceptance gate relaxed.

Reproduce with the configured JDK and local SDK:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools/gradle-home'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
python tools/test_assets.py
.\tools\Test-Device.ps1 -Serial <serial> -Suite Battery
.\tools\Test-Device.ps1 -Serial <serial> -Suite All -Cycles 50
```

`Build-Probe.ps1 -Probe Battery` generates just the new diagnostic; `Test-Device.ps1` generates and
copies both fixtures automatically for All. Logs remain ignored under `.tools/`.

Owner-game battery saving is requested separately on the installed build. Still required: real OS
process death, reboot, physical sleep/suspend, actual disk-full/power-loss behavior, sustained memory,
audio/frame timing and controller disconnection. Sudden termination may lose progress since the latest
completed checkpoint; scheduler delays and storage errors can extend that interval. No comparative PSS,
frame-pacing or leak-free claim is made from this run. M0 remains passed; M1 is in progress.
See SAVE_RELIABILITY.md and ADR 0007 for implementation and source API reasoning.

## M2 media, lighting, motion and presentation — 2026-10-09

Version 0.2.0-m2-studio, versionCode 7, same RP5/Android 13/API 33/arm64/Adreno 650/GLES 3.2,
1920×1080 at 60 Hz and firmware `RP5_V1.0.0.189_20251128_174732_user`. M2 is active at the owner's
request, retaining M1's longer stability gates. Both APKs build; **21 host tests** (nine coordinator,
five domain, three queue, two SaveStore, two motion) and **eight asset tests** pass. Lint reports
**zero errors/22 warnings**; the additional warning is the intentionally pinned matching Filament utils
version. Both GLBs pass Khronos Validator 2.0.0-dev.3.10 with **zero errors/zero warnings**, no ignored
issues. Cartridge/receiver/audio regeneration is byte-for-byte identical on this host.

**All 14 RP5 tests pass with 50 native handoffs in 113.466 s on the final source.** The preceding full
run passed in 113.482 s before synchronous-handler hardening of the setup barrier. The targeted native
invalid-load/existing-SRAM proof passed with three cycles in 2.815 s. Version 7 is installed/open.

The new ordinary-UI test starts with Tools closed, explicitly opens it to choose original files, then
imports moderate red/green/blue/yellow quadrant print swatches onto the actual shared mesh. GPU PixelCopy
verifies all four positions (no vertical/horizontal flip), with existing label upload/apply gates.
Motion off snaps focus to the selected index. Motion and alternate shortcut both persist across
Activity recreation; the isolated debug preferences are reset afterward. The owner database/art/ROMs
are untouched. Existing independent-label/folder/removal/queue/save/native checks all remain in All.

CollectionProfileActivity is debug-only and uses three instances with original ORBIT FIELD labels.
It matches the immersive 1920×1080 viewport, waits for assets and one second of warmup, then renders
10 seconds at rest, 20 seconds of retargeting every 150 ms, and six seconds of repeated insertion/ejection.
The test polls SurfaceFlinger every second, deduplicates actual presentation timestamps (column two),
rejects zero/pending fences and applies 0.5-second margins at phase boundaries. Median/p95/p99 below
measure actual presentation, rather than Compose frame callbacks or just accepted beginFrame calls.

| Original viewport phase | Frames | Median ms | p95 ms | p99 ms | Intervals >25 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Rest | 540 | 16.664 | 16.752 | 16.828 | 0 |
| Rapid navigation | 1,140 | 16.664 | 16.750 | 16.818 | 0 |
| Insertion/ejection | 300 | 16.665 | 16.739 | 16.823 | 0 |

Separately measured CPU submission p95: **1.526 ms**, **2,161/2,161 callbacks submitted**. Performance
assertions require >100 real presentation samples per phase and p95 ≤1.5 reported refresh periods;
the threshold was not changed. This is a short isolated viewport test, not all owner artwork workloads,
whole-app input latency, ten-minute sustained thermals or a memory-stability assertion.
After restoring the ordinary owner library, one aggregate snapshot was **183,874 KiB PSS (~179.6 MiB)**,
including **8,908 KiB GL mtrack**. It is not a comparative leak/plateau measurement.

Initial failures were fixed and rerun: empty-Compose-rule helper type/import mistakes; legacy chooser
helpers needed to open Tools; Compose updates needed flushing before independently clocked GPU sampling;
UiAutomation passes argv directly, so shell quotes were literal. Fully saturated green was desaturated
by the filmic highlight mapper; original mid-tone print swatches retain the same quadrant assertions.
The recreation check exposed a genuine toggle race: compute the next value once before emitting Flow,
then persist that value. No assertion was skipped or performance gate relaxed.

A repeated native run exposed SIGSEGV in mGBA save-memory access after a rejected ROM with an existing
battery file. Pinned GLRetroView can emit SurfaceCreated after initializeCore swallows a load error;
async error delivery can arrive later. The adapter now primes its private lifecycle scope observer and
checks successful RenderLifecycleObserver registration using a synchronous Main Handler queued after
upstream's synchronous post. It restores SRAM only after that success barrier, still before retro_run.
The fixture now always seeds battery bytes for the invalid-ROM recovery test, then performs valid
sessions. All final 50 cycles and original battery tests pass. ADR 0007 records the implementation
dependency that must be re-audited when changing the frontend version. No crash is hidden by deleting
the save, weakening invalid-ROM recovery or substituting mock frames.

Reproduce after the normal build:

```powershell
python tools/generate_cartridge.py
python tools/generate_sound.py
python tools/test_assets.py
node tools/validate_assets.cjs
.\tools\Test-Device.ps1 -Serial <serial> -Suite Render
.\tools\Test-Device.ps1 -Serial <serial> -Suite All -Cycles 50
adb -s <serial> logcat -d -s CartridgePacing:I '*:S'
```

See ASSET_PIPELINE for the pinned validator installation and offline studio-generation command.
Only original source/assets and aggregate results are committed; screenshots/private device logs are
ignored. [Android 13 FrameTracker](https://android.googlesource.com/platform/frameworks/native/+/android-13.0.0_r1/services/surfaceflinger/FrameTracker.cpp)
and [UiAutomationConnection](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/core/java/android/app/UiAutomationConnection.java)
were inspected for timestamp ordering and direct argv execution.

Owner review of material/slot/motion and optional sound is requested on the installed build. Still
required: speaker/headphone contact/audio timing, asymmetric real-scan fidelity, manual close-up art
direction, longer real-library/gameplay CPU/GPU/PSS/audio/thermals/battery/latency, physical sleep/reboot,
OS process death and held-controller disconnection. M0 remains passed; M1/M2 sustained acceptance stays
open. Scripted mesh refinement does not imply manual production Blender polish.

## M3 library management — 2026-10-09

Final version 0.3.0-m3-library / code 8, same RP5 Android 13 / API 33 / firmware
`RP5_V1.0.0.189_20251128_174732_user`, Adreno 650 / GLES 3.2 / 1920×1080 landscape at 60 Hz.
JDK/Gradle/SDK/NDK and dependency pins remain unchanged. No test result below implies a device reboot,
OS process-death, SD-removal or sustained thousand-game workload result.

| Check | Actual result |
| --- | --- |
| Both APK builds + host tests + lint | BUILD SUCCESSFUL, final required task group 14 s |
| Host tests | 27, zero failures/errors: coordinator 12, query/clock 3, domain 5, motion 2, saves 2, scrape queue 3 |
| Lint | 0 errors / 23 warnings; added URI KTX style warning, no broad suppressions |
| Structural assets | 8 pass; M2 production GLBs/KTX/WAV unchanged |
| Focused management suite | 2 pass / 8.046 s before final picker/history hardening |
| Final All suite | 16 pass / 119.638 s, 50 real native handoffs, final picker/history source |
| Installed app | code 8 / 0.3.0-m3-library, MainActivity launched after suite |

The controller/details test injects ordinary L1/R1/A/B keys and a D-pad hold through a display frame.
It uses the production UI/Room/Filament/native core with isolated original ROMs. It edits title/year,
sorts a second-imported title ahead of the first while preserving focused hash, filters favorites,
recreates, persists an empty search, resets, runs internal emulation, pauses/ejects and recreates to
check saved playtime/last-played. Missing metadata renders "Not set" and does not block launching.

The v4 schema fixture upgrades explicitly to v5, indexes a nested original DocumentsProvider and
checks a second scan performs **zero additional ROM reads** for seven positively stamped documents.
Verify all reads all seven. A failing nested query cannot mark unseen files missing. A subsequent
complete scan detects the deleted original, retains a playable cache and accepted metadata/favorite.
Deleting that test cache makes launch fail; restoring its original and rescanning repairs the same
hash. Changed bytes under the same document name create a new hash and retain the old enrichment.
Two overlapping roots deduplicate; forgetting both keeps seven library entries and metadata.
Existing migration tests now exercise the full v1/v2/v3→v5 chains without destructive fallback.

Host tests separately verify stable ties/same-title distinct hashes, search/favorites intersection,
canonical focus persistence, empty-view recovery, pause-time exclusion and per-game failed-history
retry across two sessions. Initial count failures came from legacy empty-slot fixtures; updated
fixtures preserve the same behavior assertions. The first device edit test used key-down/up within
one frame; an 80 ms hold matches navigation's Choreographer polling. All failures were corrected.

The unchanged isolated M2 presentation gate also passes: 540 rest / 1,140 navigation / 300 media-motion
intervals; presentation p95 16.732188 / 16.731927 / 16.726042 ms, respectively, zero >25 ms gaps.
CPU submission p95 1.411094 ms, 2,161/2,161 submitted. It samples 36 seconds after warmup with phase
margins; this is a renderer regression gate, not large-library/thermal/audio/input-latency profiling.

Reproduce with the existing ignored local toolchain (or configured SDK/JDK):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools/gradle-home'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
python tools/test_assets.py
.\tools\Test-Device.ps1 -Serial <device-serial> -Suite Manage
.\tools\Test-Device.ps1 -Serial <device-serial> -Suite All -Cycles 50
```

Before calling full M3 acceptance complete, physically review a real collection with:

- R1 details/L1 favorite; rapid navigation after each sort/filter; no-match and reset recovery.
- Local metadata edits/clears and scraped label/media/metadata provenance retained after relaunch.
- Automatic discovery of a new ROM on open; opt-out; explicit Rescan/Verify all; cancel while scanning.
- Persisted grants across reboot, revoked/reselected roots, removable SD absence, overlapping roots.
- Same hash added/moved/recovered without duplicate entries or loss of saves, favorites or accepted labels.
- Missing original with/without working copy, wrong-ROM recovery rejection and reselecting the exact ROM.
- Large-library input/CPU/memory/private-storage profiling; disk-full/index/history write failures.
- Longer gameplay, controller disconnection, sleep/background/process death, thermal/battery/audio checks.

Scrape packs, selected-subset/preferred-region workflows and the deferred scraper review redesign remain
open. Existing live API proof is historical; no new provider API/schema/dependency changes were made.

## M3 scraper review — version 9, 2026-10-09

The preceding version-8 enrichment deferrals are historical. Selected/missing-label scopes, saved
region/language preferences, current-title-first review, protected fill-missing and persistent undo
are now implemented; see M3_SCRAPE_REVIEW/ADR 0010. M0 remains passed; full M3, M1 sleep/reboot and
M2 sustained/artistic/thermal gates remain open. No new dependency/native assets or thresholds changed.

On the connected **Retroid Pocket 5**, Android 13/API 33, arm64/Adreno 650, 1920×1080@60 Hz,
firmware **RP5_V1.0.0.189_20251128_174732_user**:

| Check | Actual result |
| --- | --- |
| Final required Gradle build, test APK, unit tests and lint | Build successful; 31 host tests, 0 failures; lint 0 errors / 24 warnings |
| Original asset checks | 8 pass |
| Focused Review suite | 4 pass in 7.968 s |
| Final All suite (`-Cycles 3`) | 19 pass, 0 failed/ignored, 105.499 s |
| Earlier All run (`-Cycles 50`) | Native 50-handoff assertion passed; 18/19 tests passed, one old UI test omitted scrolling to the new off-screen action |
| Fresh real provider URL/metadata/region probe | 1 pass in 1.882 s; no commercial artwork/ROM download |
| Final app installation | Version 9 installed and MainActivity launched |

New tests prove selected hashes are published before a provider request, only the current title is
searched, source-tagged ranking retains alternatives/unknown order, resumed review reads current
accepted data, fill-missing protects manually edited values and no-op filling does not erase undo.
Production Room/Filament on RP5 verify persisted Japan/French preferences, selection of a target hidden
by favorites, actual label texture readiness, draft recreation and restoration of saved query/focus,
preferred-label apply, French synopsis fill while keeping a local year, reopened undo and one search
across repeated recreation. The selected original fixtures contain no commercial labels/ROMs.

Schema 5→6 reopening tests atomically restore enrichment without rewinding source URI, favorite,
playtime/last-played, query or a save-state file. Label recipe and extra-media undo are also checked.
Existing v1/v2/v3/v4 upgrades now reach v6. File journal v1/untagged candidate compatibility and v2
tag/outcome round-trip pass. Resume cancels a gated second request after accepting first-title metadata;
reopening repeats only the unfinished request and preserves the first result/choice.

Corrected failures: host scheduling initially used actual IO outside the test scheduler; production
still defaults to IO while tests inject a deterministic dispatcher. Memory journal now agrees with the
file journal's empty-batch behavior. A new UI test now opens Tools; an older test scrolls the review list
to its action. No assertions were dropped or performance thresholds weakened. Test databases, original
provider fixtures and private preferences are isolated from the owner's collection.

The final existing 36-second original-art viewport regression sampled 540 rest / 1,140 rapid-navigation /
300 insertion-ejection intervals. Presentation p95 was **16.735937 / 16.746094 / 16.731145 ms**, with
zero gaps above 1.5 refresh periods. CPU submission p95 **1.540365 ms**, 2,161/2,161 submitted. This
remains an isolated short regression, not proof of sustained large-library/gameplay/input latency.
Opaque review pauses rendering; actual 3D draft resumes it. Gameplay/context ownership is unchanged.

Reproduce with the configured local SDK/JDK (see README):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools/gradle-home'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
python tools/test_assets.py
.\tools\Test-Device.ps1 -Serial <device-serial> -Suite Review
.\tools\Test-Device.ps1 -Serial <device-serial> -Suite All -Cycles 3
.\tools\Test-Device.ps1 -Serial <device-serial> -Suite Live
```

Live is opt-in, account-free metadata/URL probing; availability/coverage may change. Offline test suites
use only original fixture art and diagnostic ROMs, with production Room/SAF/Filament/mGBA. Tests report
JUnit results explicitly because `am instrument` may exit zero on failures. The 24th lint warning is KTX
preferences-edit style; synchronous commit stays on IO and checks its failure result, without suppression.

Still required on RP5: owner/controller review with actual titles and long batches; D-pad/analog rapid
review navigation and X selection; API ambiguity/missing flat labels/partial timeouts; cancellation at
preview/download/commit; restart/reboot/revoked SAF grants; large-library storage and input profiling;
storage-full/write-failure injection. Existing real sleep, controller disconnection, sustained memory,
audio/thermal/battery/input-latency checks remain open. Scrape packs/multi-console scopes are not shipped.

## M3 offline pack increment — version 10

Both APKs and required checks pass: 34 host tests, 8 assets, lint 0 errors/24 warnings. Two focused
RP5 Pack tests passed in 3.521 s; full suite **21/21 passed in 108.076 s** with three native handoffs.
Android 13/RP5 firmware is unchanged. Version 10 installed/launched. Original fixture PNGs and a provider
that throws on every network call prove SAF import, real GLB preview/apply, metadata/manual protection,
source provenance, offline resume and persistent single-step undo. Bad image checksum and unknown version
leave no retained pack. Host tests reject traversal/absolute/data-bearing directory paths and a small ZIP
that expands past the 12-MiB entry bound. The portable PC helper's original PNG/homebrew-hash ZIP round
trip passed and contains only pack.json plus its image, never the ROM. A test-fixture JSON parenthesis
error was corrected before hardware testing; behavior assertions were not weakened.

Reproduce with required Gradle/asset commands, then `Test-Device.ps1 -Serial <serial> -Suite Pack` or
`-Suite All -Cycles 3`. SCRAPE_PACK_FORMAT defines limits and the PC helper. M3 functional slices are
implemented; very large actual collections, revoked/reboot SAF grants, storage-failure injection and
shared sustained hardware gates remain open. Continue to M4 under the owner's through-M6 authorization.

## M4 layouts — version 11, 2026-10-10

Required Gradle tasks/build/test APK/lint pass; **37 host tests**, **eight asset checks**, lint 0 errors /
24 warnings. The final RP5 All suite passed **23/23 in 138.594 s**, `-Cycles 3`. Earlier `-Cycles 50`
passed the native fifty-handoff assertion and 22/23 tests; the remaining collection-menu overflow was
fixed before the final run. Version 11 is installed/launched on the same Android 13/RP5 firmware.

Two new RP5 cases cover twelve original homebrew games/labels and a separate 500-original-record
collection. They verify three layouts, focus/layout persistence, actual GLB label preview/pixels,
internal gameplay/pause/eject, D-pad navigation to the scrollable View action and synthetic stick
dead zone/held repeat/reversal. The scale case makes 240 rapid moves and recreates the nine-instance
pool with bounded 3/9-label windows. Its PSS sample was 236,859 → 258,807 KiB, a short process sample
that includes accumulated test/native allocations, not a memory-plateau or distinct-artwork claim.

The original-art SurfaceFlinger benchmark now runs 54 seconds. Presentation p95: rest **16.752500**,
horizontal rapid movement **16.742135**, insertion/ejection **16.741823**, vertical **16.748073**,
nine-object grid **16.744531 ms**. Samples: 540/1,140/300/420/540 frames; zero intervals exceeding
1.5 refresh periods in those windows. CPU submission p95 1.732031 ms; 3,241/3,241 submissions. The
unchanged p95 acceptance threshold remains 1.5 refresh periods. Startup, sustained gameplay, thermal
behavior, 500 distinct label textures and input-to-photon latency are not established by this test.

Failures corrected: neutral stick/key releases between frame polls now reset repeat immediately;
the collection menu scrolls within the screen, follows controller focus and orders actions consistently.
Touch tests scroll to actual off-screen actions. No behavior assertions or thresholds were removed.
Debug presentation preferences are isolated per fixture database, preserving owner settings.

Reproduce with required Gradle/asset commands, then `Test-Device.ps1 -Serial <serial> -Suite Layout`
or `-Suite All -Cycles 3`; fifty handoffs use `-Cycles 50`. See M4_PROGRESS/ADR 0012. Physical owner
layout/stick review, dense distinct artworks, sustained grid profiling and shared sleep/reboot/storage/
controller-disconnection/thermal/audio gates remain open. M5 is the next active engineering increment.

## M5 actual results — 2026-10-10

Version 12: both APKs build; 40 host tests, ten structural asset tests, lint (zero errors) and all three Khronos GLB validations (zero errors/warnings) pass. Focused GB/GBC tests pass in 10.807 s. Full RP5 suite: OK (25 tests), 148.971 s, three handoffs; a preceding longer run passed 50 handoffs but found two obsolete scraper test indices, subsequently corrected and verified. Native masked save-buffer and live RTC synchronization were required for immediate post-state-load checkpoints. GB/GBC original diagnostics prove frame pixels, A input, state, battery cold boot and halted MBC3 RTC restoration. No commercial ROM/BIOS/logo is bundled. See M5_PROGRESS for scope, commands and remaining real-cartridge/peripheral/RTC-drift gates.

## M6 setup/settings actual results — 2026-10-10

Version 13 builds both APKs with 42 host tests and lint passing. Environment suite: OK (2 tests), 6.381 s on the connected RP5. Setup/recreation, persistent independent mappings, native GB button behavior and paused/startup core variables, notices, and optional HOME resolver eligibility pass. The test restores the previous alias setting and never launches the chooser or changes the default role. Default HOME selection and reboot require an owner choice. Sleep/backup/stability work is still active.

## M6 recovery actual results — 2026-10-10

Version 14 builds both APKs with 44 host tests and lint passing. Focused Recovery suite: OK (1 test), 8.366 seconds. It proves original GB execution/frame identity after synthetic background, actual Android screen sleep/wake (PowerManager interactive false), paused native output capture, cold Activity launch and integrity-failure recovery/discard. The device is awakened in finally. This is not a reboot or abrupt-process-death result. It found/fixed released AndroidView reuse, upstream timing-only FrameRendered readiness and paused SurfaceView buffer loss. Full-state generations have unit coverage for replacement, invalid input preservation, corruption, path and core-revision rejection.

2026-10-10, M6 recovery v14: full RP5 suite passed all 28 tests with `-Cycles 50`, 182.603 seconds. Focused recovery passed in 8.366 seconds with real screen sleep/wake. Host tests: 44; lint and both APKs pass. Abrupt process termination, reboot and sustained stability remain separate tests.

M6 backup v15: 3 RP5 tests passed in 3.394 seconds. `tools/Test-Device.ps1 -Suite Backup` exercises round trip, protected merges, malformed archives and real console SAF callbacks. Full suite now expects 31 tests; the new full run remains pending. Backup state fixtures test exact bytes and compatible core revision, not arbitrary cross-core compatibility.

See CONSOLE_RELIABILITY for separate-process, real reboot and sustained-gameplay commands/results. The quick full suite expects 32 tests after the actual-axis settings regression. The holding process test is intentionally terminated; only its fresh-process verification is a JUnit pass. The long test is excluded from routine `-Suite All` runs.

M6 sustained RP5 result: `Test-Stability.ps1 -Minutes 10` passed, one test, 636.783 seconds. Ten full play/save/load/sleep/wake/eject/reinsert cycles, no session/save warning failures. Native callback rate 60.003–60.009 FPS; PSS 242.7–294.8 MiB, first 266.5 and last 268.6; thermal NONE and battery 28.5°C with USB power. See CONSOLE_RELIABILITY; this is not an unplugged battery-life or universal stability claim.

M6 reboot: real RP5 restart, changed boot ID and completed boot confirmed; fresh-process paused frame/battery recovery passed, 1 verification test in 3.346 seconds. Default-HOME choice was preserved. Do not confuse this with selecting/testing Cartridge as Android's default launcher.

M6 final scale regression: the first expanded run passed 31/32; diagnostics isolated an IO state-copy race that cleared one-shot renderer readiness. Fix reads current state after the checkpoint lookup returns. Focused Layout now passes 2 tests in 15.679 seconds with eight consecutive 500-title recreations; assertions and timeout remain intact. Full results follow after the rerun.

Final corrected v16 full RP5 suite PASS: 32 tests, 189.118 seconds, 50 native handoffs and eight 500-title recreations. No skipped/dropped assertions. Host tests 44; lint/both APKs/10 asset checks/3 GLB validators pass. Logs remain ignored under `.tools`.

Final live provider probe PASS: 1 test, 3.794 seconds, real GBA/GB/GBC metadata and flat-label URLs, no image or ROM downloads. Ordinary `-Suite All` remains offline; `-Suite Live` is explicit.
