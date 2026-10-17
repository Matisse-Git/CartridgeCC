# Consoles

What Cartridge can play, how each console is defined, and what stands between the rest and the
shelf. Written 2026-10-11 with the console-registry increment (ADR 0022) and extended the same day
with the three consoles whose cores draw for themselves (ADR 0023). Revised 2026-10-12 after the
owner's own games were run on those three (ADR 0024) and the owner's cartridge models were added
(ADR 0025), and on 2026-10-13 when PlayStation 2 moved to an engine that runs the console's own
BIOS (ADR 0026). "Verified" always means the Retroid Pocket 5 named in [TESTING](TESTING.md);
compiled is not verified.

Every game runs inside Cartridge, in a Libretro core built from pinned source and hosted by
LibretroDroid on the emulator's own surface. No console here starts another app.

## Status

| Console | Core (pinned) | Game files | Owner's files | Status |
|---|---|---|---|---|
| Game Boy Advance | mGBA 0.10.5 | `.gba` | none | Playable. Verified with original programs and by the owner with games |
| Game Boy, Game Boy Color | mGBA 0.10.5 | `.gb` `.gbc` | none | Playable. Verified with original programs |
| PlayStation | PCSX ReARMed c8816799 | CUE+BIN folder, `.chd` `.pbp` `.iso`, PS-X `.exe` | BIOS optional (built-in stand-in runs only some games) | Playable. Verified with original programs; BIOS-backed games are the owner's check |
| Super Nintendo | Snes9x 1.63 | `.sfc` `.smc` `.swc` `.fig` | none | **Playable. Verified with an original program**: picture, input, battery memory, state, restart |
| Nintendo Entertainment System | FCEUmm 7a542dab | `.nes` `.unf` `.unif` | none | **Playable. Verified with an original program**, sound included |
| Genesis / Mega Drive | Genesis Plus GX 393f70cd | `.md` `.gen` `.smd`; `.bin` in a Genesis folder | none | **Playable. Verified with an original program**, sound included |
| Master System | Genesis Plus GX 393f70cd | `.sms` | none | **Playable. Verified with an original program**, sound included |
| Game Gear | Genesis Plus GX 393f70cd | `.gg` | none | **Playable. Verified with an original program**, sound included |
| Saturn | Beetle Saturn ec20ea05 | CUE+BIN folder, `.chd` in a Saturn folder | **BIOS required**: `mpr-17933.bin` (America, Europe) and/or `sega_101.bin` (Japan) | **Implemented; the core verified with an original stand-in image. Needs the owner's BIOS to start a game.** See below |
| Dreamcast | Flycast 2.7 (5aa091fd) | `.gdi` with its tracks (folder), `.cdi`, GD-ROM `.chd`; CUE+BIN and `.chd` in a Dreamcast folder | BIOS optional (`dc_boot.bin`; a built-in replacement starts most games) | **Playable with the three discs tried**: right speed, whole sound, input, states, sleep. Other games untried. See below |
| PlayStation 2 | ARMSX2 0076b5c3 (PCSX2 for ARM64) | DVD `.iso` and `.chd`; `.iso`, CUE+BIN and `.cso` in a PS2 folder | **BIOS required**: one 4 MiB dump from a console, any region | **Playable with the two games tried, on the owner's BIOS**: full speed, whole sound, input, sleep. No save states. Other games untried. See below |
| GameCube | Dolphin 114977f1 (Libretro fork) | `.iso` `.gcm` `.rvz` `.gcz`; `.ciso` in a GameCube folder | none (starts games directly) | **Playable with the one game tried**: full speed, sound, input, memory card, sleep. No save states. Other games untried. See below |
| Nintendo 64 | — | — | — | Not playable. Recognised and reported at import. Not attempted |

A console that is not playable is never a shelf, never imported and never offered in settings. Its
files are counted on the import result ("12 files for consoles Cartridge can't play yet: Wii").

"Implemented" and "playable" are kept apart on purpose, and so are "playable" and "the games
tried play". Saturn is implemented: its engine is in the app and ran a stand-in image, and no game
has been started. For Dreamcast, PlayStation 2 and GameCube the statements above are about the six
games the owner had imported on 2026-10-12, one observation each; nothing is known about any other
game until it is tried. PlayStation 2 is playable only once the owner has added the console's BIOS:
without it the console says what it needs, as Saturn does.

**What each console's games look like (ADR 0029, branch `feature/box-cases`).** A game is shown in
its retail case, wrapped in its ScreenScraper cover. The case comes from the market its file is named
for: US first (`(USA, Europe)` is US), then Europe, then Japan; `(World)` and an untagged name are US.

| Console | US | Europe | Japan |
| --- | --- | --- | --- |
| Game Boy, Game Boy Color | handheld box | handheld box | small Japanese box |
| Game Boy Advance | handheld box | handheld box | tall Japanese box |
| NES | NES box | NES box | Famicom box |
| Super Nintendo | SNES box | SNES box | Super Famicom box |
| Genesis, Master System | clamshell | clamshell | clamshell |
| Game Gear | Game Gear box | Game Gear box | Game Gear box |
| PlayStation | jewel case | jewel case | jewel case |
| Saturn | Saturn case | Saturn case | jewel case |
| Dreamcast | jewel case | DVD-style case | jewel case |
| PlayStation 2 | DVD case | DVD case | DVD case |
| GameCube | GameCube case | GameCube case | GameCube case |

The case is a registry field (`media`, `europe`, `japan`) and changes nothing about how a game is stored
or run. Sizes are approximate; see ASSET_PIPELINE.

## What "verified with an original program" covers

No commercial game, BIOS or logo is used by any test. For each of the five new cartridge consoles
`tools/probe/generate_console_probes.py` hand-assembles a small program in that console's own
machine code (65816, 6502, 68000, Z80). `ConsoleCoresTest` imports it through the production
picker path and plays it through the production interface on the device:

- the program's own colours reach the screen (white and red, read back from the presented surface);
- Android's A button reaches the console's pad and the program answers (white and green);
- the choice is in battery memory after a pause, at the offset the program wrote it;
- a tone the program holds is delivered as non-silent PCM (not the Super Nintendo program, which
  uploads no sound driver);
- Save State writes a state that names the core build; Load State brings the picture back;
- a new core instance, and a new Activity, start from the kept battery memory in their first frame.

The same test reads every option offered for a console back from the running core: each is a
variable that build declares, and a paused game takes a changed value.

It does not cover how any particular game plays, special cartridge chips, light guns, multitap,
or long sessions. Those are the owner's to try.

A state and battery memory are different things, and cores differ: Snes9x and FCEUmm put cartridge
memory inside a state, Genesis Plus GX does not, so on Genesis, Master System and Game Gear loading
a state never touches the save. Either way Cartridge never rolls a battery save back when a game is
*started* from a state (SAVE_RELIABILITY).

## Saturn

Beetle Saturn is built and wired in: disc identification, bundle import, the BIOS workflow, the
internal backup memory through the same battery path as a cartridge (32 KiB, `beetle_saturn_save_method
= libretro`), states, session recovery and a folder per game for the files the core writes itself.

What is true today:

- **It cannot start without the owner's BIOS.** There is no built-in replacement. The core picks the
  file by the disc's region: `mpr-17933.bin` for America and Europe, `sega_101.bin` for Japan.
  Cartridge accepts those two dumps by content (MD5 `3240872c…` and `85ec9ca4…`, the ones the pinned
  core's README names), through Settings › Gameplay › Saturn, the import result, the game's details,
  or the dialog shown when a Saturn game is started without one. Either file is a start; a game from
  the other region then fails and offers the missing file.
- **Formats:** a CUE with its BIN tracks, imported through a folder so the tracks can be read, and
  CHD in a folder named for Saturn (a CHD cannot be identified cheaply, so without the folder's name
  it is taken for PlayStation, as before). A CUE identifies itself: every Saturn disc begins with the
  console's name, and Cartridge reads that before the folder's name.
- **Controller:** the digital pad. The 3D pad, mission stick, wheel and light gun are not offered.
- **Run on the device without Sega's BIOS.** `SaturnCoreTest` supplies an original 512 KiB stand-in
  image (a few SH-2 instructions that draw striped lines) in a private test folder. On the RP5 the
  core loaded, ran and drew it (mean colour `f8 7c 7c`), exposed its 32,768-byte internal memory,
  wrote a 6,847,091-byte state and kept its own files (`game.bcr`, `game.smpc`) in the game's folder;
  the no-BIOS explanation was shown when the same disc was started without the image. **No game has
  been started**: that needs the owner's BIOS.
- **Speed is unknown.** Beetle Saturn is an accuracy-first software renderer with an interpreted
  SH-2 pair on arm64. On the RP5's Snapdragon 865 light 2D games are expected to hold speed and
  demanding 3D games are not; nothing here measures it. The pinned fork's new binary translators for
  the SCU and SCSP signal processors (added upstream in September 2026, on by default there) are
  fixed off: interpreters first, speed-ups once they can be measured on the device.
- Multi-disc games are separate entries; there is no disc change yet.

## Dreamcast, PlayStation 2, GameCube

All three are in the app (ADR 0023, 0026): Flycast, ARMSX2 and Dolphin, each the unchanged pinned
source, each drawing with the device's own OpenGL ES 3 inside Cartridge. Dreamcast and GameCube need
no file of the owner's to start; PlayStation 2 needs the console's BIOS. Each was first run on the
RP5 from an original program written for this project, and then, on 2026-10-12, from the owner's
own games, which is where every real fault showed (ADR 0024). PlayStation 2 ran on another engine,
Play!, until that night; what is said of it below is marked where it is history.

### What the owner's games did (2026-10-12)

The owner reported a black screen on PlayStation 2, broken sound on Dreamcast and a broken picture
on GameCube. All three were this host's doing and are fixed. Under Play! a second PlayStation 2
game still never drew; the owner then asked for an engine that uses the console's BIOS, and on
ARMSX2 both games run (ADR 0026). Each row is one game, started from the app's own private copy by
`RealGameCheck` on the RP5 (Android 13, `RP5_V1.0.0.189_20251128_174732_user`); the games are not
named here or anywhere in the repository.

| Console | Which game | What it did |
|---|---|---|
| GameCube | the one game imported, a 3D game | Logos, title, menus; it created its save file on the memory card. 61.9 pictures a second over a minute, sound whole (33,669 frames a second in and out, none dropped). Paused, left and started again. Survived a real sleep and wake |
| Dreamcast | first disc of a two-disc game | Logos and title at the game's own 30 pictures a second (31.1 runs for 62.6 host frames), sound whole (46,024 in, 46,018 out, none dropped). After a real sleep it came back from its checkpoint, paused, and carried on |
| Dreamcast | second disc of the same game | The same figures. Start on the title opened its menu |
| Dreamcast | another game | Title and opening film; sound whole |
| PlayStation 2 | a game on a 4.2 GiB DVD image | Logos, opening film, title, new game, first scene with dialogue: **a new picture on every screen frame (61 a second) in the film and in that scene**, sound whole (48,800 frames a second in and out, none dropped; one second at nine tenths of that, at a change of scene). Survived a real sleep and wake. *Under Play! the first scene ran at about 85 % of full speed* |
| PlayStation 2 | the Japanese first release of a game (4.3 GiB) | **Runs**: logos, title, new game and its real-time 3D opening at the game's own 30 pictures a second (61 runs, 30 to 31 new pictures), sound whole. The confirm button started it. Survived a real sleep and wake. *Under Play! it drew two pictures and never again* |

Sound was counted, not heard: whether it is whole and at the right rate, not how it sounds.

What had to change, in one line each (ADR 0024 has the how and the measurements):

- the host's framebuffer was being bound over the engine's own (the PlayStation 2 black screen and
  the GameCube corruption);
- Dolphin's two-thread mode deadlocked at a game's first frame, so it runs on one;
- Dolphin has to be unloaded on the thread it ran on, or leaving a game closed the app;
- these engines emulate until a game's next picture, so a 30-a-second game ran twice too fast with
  torn sound (the Dreamcast report); the shim now paces a core by its own sound and throws none of
  it away;
- a console without states lost its game when the device went to sleep; it now stays loaded and
  paused;
- on any console, a pause that arrived after the screen was gone ended the game unsaved;
- PlayStation 2 was moved from Play! to ARMSX2, which runs the console's BIOS (ADR 0026), and with
  it: this engine is unloaded before its context is destroyed, the reverse of the host's order, or
  leaving a game closed the app; and a game is handed to it by a path with no link in it, or a bare
  program never started;
- closing the app with a PlayStation 2 or GameCube game still loaded ended the process ten seconds
  later, because the thread the engine has to be unloaded on was already gone; a loaded game is now
  released before the interface is taken down.

### What was run on the device with original programs (2026-10-11)

| | Dreamcast | PlayStation 2 | GameCube |
|---|---|---|---|
| Test program (`tools/probe`) | SH-4, bare, on the built-in BIOS replacement | MIPS R5900, bare, on Play!'s built-in system software then; **on the owner's BIOS under ARMSX2 since 2026-10-12** | PowerPC, bare, started directly |
| How it draws | writes a 640×480 picture into video memory | sends the graphics chip one packet per frame through DMA: draw state and a screen-sized sprite | writes a 640×480 picture into the memory the video chip reads |
| Picture reached the screen | yes | yes | yes, with *Exact video output* on for that game |
| A button changes the picture | yes | not tested: the program reads no pad | yes |
| Left stick held right changes it | yes | not tested | yes |
| Save State, then Load State | yes, 27,652,536 bytes | then yes, 41,943,084 bytes; not offered since | not offered (see Saves) |
| The core's own files land in the game's folder | `game.A1.bin` (a memory unit) | not in the game's folder since ARMSX2: two cards under the system folder, shared | `User` |
| Started a second time in the same process | yes | yes | yes |
| Library returns intact afterwards (45 objects) | yes | yes | yes |
| **Started from a disc image**, as a game is | yes: a GDI sheet and three tracks, imported from a folder; the disc's header was read, the program it names found in the disc's file system and started from the header's own start-up code | yes: a small `.iso` in a folder named `ps2`; its `SYSTEM.CNF` was read and the program it names started | no: a GameCube disc is started by a loader program on the disc itself, which only Nintendo's discs carry |
| Its memory unit / card after the disc ran | `T-00000.A1.bin`, named for the disc's product number | `Mcd001.ps2` and `Mcd002.ps2`, 8 MB each, unformatted | — |
| The app closed with the program still loaded | not tested | yes, in 0.7 s, and started again in the same process | yes, in 0.8 s |

The three tests also run one after another in one process, so three different engines hand the
screen to each other and back to the library. Since 2026-10-12 the PlayStation 2 and GameCube tests
also leave the app and come back, and find the same game paused and still loaded. The PlayStation 2
column was re-run under ARMSX2 the same night: without a BIOS the console explains itself; with the
owner's, the same program is started both ways and draws. Those two tests are skipped on a device
that has no BIOS for them (TESTING).

What the programs could not show, and the games did: 3D drawing, speed and sound. Each program uses
the plainest way its console can put a picture on screen, makes no sound, and never writes to
memory its engine has compiled code from. Every fault in the list above was invisible to them.

Still not shown by anything:

- **Any game but the six.** Compatibility and speed are per game.
- **Sharper 3D** (twice the resolution). The setting reaches each core and the host's picture is
  resized to match what the core draws; no game has been played with it on.
- **How the sound sounds.** It was counted.
- **Long sessions**, a second player, and saving and loading from a game's own memory card (one
  game created its save file; none was loaded back).

### Dreamcast

- **BIOS optional.** Flycast's built-in replacement starts most games. `dc_boot.bin` (2 MiB, MD5
  `e10c53c2…`) can be added under Settings › Gameplay › Dreamcast and is used when present.
- **Formats.** A `.gdi` with its tracks, imported through a folder so the tracks can be read; `.cdi`;
  a GD-ROM `.chd`, recognised anywhere by what its header says it holds; a CUE with its BIN tracks,
  which identifies itself by the console's name on the disc; and any `.chd` in a folder named for
  Dreamcast. Whatever a sheet names is that disc's track: an older dump whose data tracks are
  `.iso` files is one game, not one game and two PlayStation discs (which is what the first device
  run of this made of it).
- **Controller.** The left stick is the stick. The two triggers are buttons: the host has no channel
  for how far a trigger is pressed.
- **Memory units** are files the core writes, one set per game, in that game's own folder.
- A state is 27 MiB. It is written when asked for and when the game is paused, not every few
  seconds during play as on the small consoles. Putting the device to sleep pauses, writes that
  state, and the game comes back from it at its pause menu (seen with a game).
- **Speed and sound.** Flycast emulates until a game shows its next picture, and most of this
  console's games show thirty a second. The shim holds the core to the pace of its own sound, so
  such a game is run on every other frame of the screen and its sound arrives whole.

### PlayStation 2

- **The engine is ARMSX2** (ADR 0026): PCSX2 with translators for ARM64, built from its own tree as
  a Libretro core and drawing with OpenGL ES 3.2. It replaced Play! on 2026-10-12, when the owner
  asked for an engine that uses the console's BIOS. (NetherSX2, which the owner named, is not open
  source: it is patches over a closed app. ARMSX2 is the open one of that family.)
- **It cannot start without the owner's BIOS.** There is no built-in replacement. Add one under
  Settings › Gameplay › PlayStation 2, from the import result, from a game's details, or from the
  dialog shown when a PlayStation 2 game is started without one. It is the 4 MiB file dumped from a
  console. Cartridge does not keep a list of them: it takes any file that carries the console's
  own file table and a version in it, which is how the engine decides too. Any region's BIOS
  starts any region's games (a Japanese game was started on a European one). Android's file picker
  cannot open another app's private folder, so a BIOS kept there has to be copied somewhere the
  picker reaches first.
- **Formats.** A DVD image is recognised by its size (`.iso`) or by what its `.chd` header says. A
  game pressed on a CD looks like a PlayStation disc from its first bytes, so `.iso`, CUE+BIN and
  `.cso` are taken as PlayStation 2 only in a folder named for it (`ps2`, `PlayStation 2`). The
  engine reads a disc as one image, so a CUE+BIN game is started from its first track; one with
  music tracks after the data track would play without them. `.isz` is no longer taken. Only
  `.iso` has been run.
- **Controller.** Both sticks, and the pad's buttons as buttons. Start and the confirm button
  answered in both games; the sticks have not been tried in a game.
- **Memory cards** are the console's, not the game's: two 8 MB cards the engine keeps in the app's
  private system folder (`system/pcsx2/memcards`), shared by every PlayStation 2 game as on the
  console. They are created at the first start; no game has been taken far enough to save to one.
  Saves made under Play! were in its own form and are not carried over.
- **No save states.** The engine names 68 MiB as the size of one and makes it on its own thread
  while the thread that asked waits; how long that takes with a game has not been measured, and a
  pause must not hang on it. So Save State, Load State, the resume checkpoint and *Start from save
  state* are not offered. A game's own saves go to its memory card as on the console, and putting
  the device to sleep keeps the game loaded and paused.
- **The engine's own start-up screens are skipped** (`armsx2_fast_boot`): a game starts directly.
- **The app is built for it in two ways that are not about PlayStation 2.** Android's tagging of
  heap addresses is off for the whole app, as this engine's own app has it (it was first turned
  off for Play!, which crashed without; whether ARMSX2 needs it was not tried). And the engine is
  built for Android 9 and later and for 4 KiB memory pages, which is the RP5; the rest of the app
  still installs from Android 8, where a PlayStation 2 game would not load.
- **Speed.** Both games tried hold full speed at the console's own resolution on the RP5's
  Snapdragon 865. That is two games. The engine's per-game patches are not packaged (its build
  would download them from a moving address), so a game that needs one to run right will not have
  it.

### GameCube

- **No BIOS.** Games start directly, without the console's menu.
- **Formats.** `.iso` and `.gcm` are recognised by the disc's own marker wherever they are, `.rvz`
  and `.gcz` by their headers; `.ciso` in a folder named for GameCube. A Wii disc is refused by name.
- **Controller.** The left stick, C-stick, A, B, X, Y, Z and Start. L and R are buttons, pressed
  fully or not at all.
- **Memory card** is a folder of save files under the game's own folder.
- **No save states.** One state of this console measured 90,722,762 bytes on the RP5, more than the
  app can safely hold twice, so Save State, Load State, the resume checkpoint and *Start from save
  state* are not offered for GameCube. A game's own saves are unaffected, and putting the device to
  sleep keeps the game loaded and paused.
- **One thread.** Dolphin's Libretro frontend can run the emulated processor on a thread of its
  own; on this host that mode deadlocked at a game's first frame, so it is fixed off. The game tried
  held full speed without it. A heavier game may not.
- **Exact video output** (Settings › Gameplay › GameCube, or per game). Off, Dolphin shows what the
  game's graphics processor copied out, which is what games do and is faster. On, it also reads the
  picture back from the console's memory, which homebrew that paints that memory itself needs, and a
  few games. The test program is such homebrew.
- Dolphin's own data (replacement fonts, sound-processor tables, per-game settings, its presentation
  shader; 1.1 MB packed) is built from the pinned source, packaged, and unpacked into the private
  system folder the first time a GameCube game starts.
- On this device Dolphin reported it could not create a second graphics context for compiling
  shaders in the background, so shaders are compiled when first needed. Expect short pauses the first
  time a game shows something new.

### All three

- **A game is copied into private storage**, like every other. For a DVD that means the disc's size
  again: 4.4 GiB for a single-layer PlayStation 2 game. The import says so before it starts if the
  space is not there. An empty 2.2 GiB image was imported and listed in 4.7 s on the RP5.
- One player. No disc change for multi-disc games; each disc is its own entry.
- Shown in their retail case (ADR 0029).
- **Leaving the app or sleeping.** Dreamcast is written to a checkpoint and restored from it, like
  the small consoles. PlayStation 2 and GameCube have nothing to restore from, so their game stays
  loaded and paused for as long as Android keeps the app; if Android ends the app in the meantime,
  the game is back at its last memory-card save.

### What made them work on this host

LibretroDroid 0.14.0 was written for cores that hand it a finished picture. For a core that draws
for itself it shares one graphics context with the core, copies the core's framebuffer to the screen
only inside the core's own "frame ready" call, sizes that framebuffer from a nominal size, and never
plugs a controller in. Five things followed, each found on the RP5 and each handled in Cartridge's
own shim (`cartridge_shim.c`) or adapter rather than in an engine's source:

1. Flycast left a vertex buffer bound, so the host's copy to the screen drew nothing: black.
2. Dolphin remembers what it bound and skips repeating it; the host rebinds the screen, its own
   program and texture. Everything the host's copy touches is now saved before it and put back after.
   Since a shader preset can make that copy read up to sixteen textures and upload lookup images
   (ADR 0027), all sixteen texture units, their samplers and the pixel-unpack state are kept too.
3. The host leaves the screen bound when it calls the core. A frame that only shows a picture
   (Dolphin showing the video chip's memory) then drew to the screen and was wiped. The host's
   framebuffer is now bound before a frame wherever the screen is bound, and only there: binding it
   over an engine's own framebuffer is what broke real games (ADR 0024).
4. Dolphin says nothing on a frame with no new picture (so did Play!), and the host swaps the
   screen's buffers regardless: flicker to black. Silence is now reported as a repeated frame.
5. Flycast and Dolphin start with empty controller ports. After loading, the pad is plugged into the
   first and the other three are named as empty: Flycast fits a pad with its memory unit only once
   it has heard about all four, and with one port named the second Dreamcast game of a session had
   no memory unit.

And three more that came with ARMSX2 (ADR 0026), which draws on a thread of its own in a second
context that shares the host's:

6. Told that the context is going, it gives up everything it draws with while its emulation still
   runs. The host destroys the context first and unloads the game second; for this engine the shim
   turns the two round.
7. It resolves links to find a program's folder and then does not know the path it was given as
   the same file. Android's private folder is reached through a link, so every game is now handed
   over by its canonical path.
8. An engine that draws for itself is unloaded on the thread it ran on, and from Android 10 that
   thread is gone before `onDestroy` is reached, because Compose has disposed of the emulator's
   view by then. A game still loaded when the app closes is now released one step earlier. This
   one was GameCube's too.

**Using a separate emulator app** remains what it was: Cartridge lists every installed Android app
one press of B from the library (ADR 0021), so another emulator can be opened from the console.
Starting a particular game in another app is not implemented, because those apps accept a game only
through their own undocumented activities and extras.

## One definition per console

`domain/GameSystem.kt` is the only place a console is declared. An entry carries:

| Field | Used by |
|---|---|
| `name` (the stable id) | Room, backups, the scrape journal, the stored shelf |
| `title`, `maker`, `generation` | shelves, details, import results |
| `media`, `europe`, `japan` | which case shows its games and the cover's proportions; the last two name the case of another market, chosen by how a game's file is named (ADR 0029) |
| `core` | which packaged library runs it, its options, firmware and state binding |
| `formats` (extension, shared, claim, content check, proof) | scanning, discovery counts, the importer |
| `extension`, `bundle`, `maxBytes` | where and how its private working copy is kept |
| `folders` | recognising a console's folder by name |
| `fingerprint`, `provider` | whether and under which platform name artwork is looked up |

`domain/Core` carries the library file, the revision that binds states and checkpoints, the heading
of its shared options, its firmware (private file name, size, and either the dumps it accepts or
what a file's own contents have to show), whether that firmware is required, the variables the save
design fixes, and whether it writes files of its own. For the engines that draw for themselves it
also says: whether the pad has sticks (`analog`), whether the pad has to be plugged in
(`emptyPorts`), which packaged data it unpacks (`data`), when the whole machine may be captured
(`snapshots`: always, only while paused, or never), that it is unloaded on the thread it ran on
(`draws`), and whether it can read a CUE sheet or is started from the sheet's first track
(`sheets`).

Nothing else in the app names a console to decide behaviour. Adding one is: a core in
`cpp/cores.cmake` through `cartridge_core()` (or, for an engine with a build of its own,
`cartridge_hardware_core()` in this tree or `cartridge_external_core()` beside it), an entry in
`Core` if the core is new, an entry in `GameSystem`, its options in `GameOptions`, its licence text
in `docs/licenses`, a probe and a device test. `SystemRegistryTest` fails if an entry is incomplete
or collides with another.

### How a file finds its console

1. Its extension. A cartridge extension is that console's game anywhere, and so are `.gdi`, `.cdi`,
   `.gcm`, `.rvz` and `.gcz`.
2. What a shared container proves about itself comes next and outranks the folder: a GameCube disc
   carries Nintendo's marker; an `.iso` longer than any CD is a PlayStation 2 DVD; a `.chd` says in
   its header whether it packs a DVD (PlayStation 2) or a CD-style disc longer than any CD
   (Dreamcast's GD-ROM). A Wii disc is refused by name.
3. A CUE's first data sector overrules the folder too: Saturn and Dreamcast discs say so. A Sega CD
   disc is refused by name instead of being listed under PlayStation.
4. Otherwise a shared container (`.cue`, `.chd`, `.iso`) goes to the console its folder is named
   for; a folder named for a console Cartridge cannot play is counted and left alone.
5. With nothing else to go on, a disc is PlayStation's, as it was before these increments.
6. `.exe` and `.md` are tried quietly; `.bin` only in a Genesis folder; `.cso`, `.ciso`,
   `.dol` and `.elf` only in their console's folder, because other programs, text files, disc
   tracks and another handheld's games share those extensions. The first bytes decide; a refusal
   outside the console's own folder is not reported.

The first 512 bytes and the file's length are looked at before anything is copied (`NES␚`, `SEGA`
at 0x100, `MComprHD` and the unit and length a CHD states, `PS-X EXE`, the Game Boy colour flag,
Nintendo's disc markers, an ELF's processor), so a wrong file costs nothing.

## Buttons

A game receives Android's key codes, which LibretroDroid hands to a core as the pad button of the
same letter; each core then maps that pad to its console. Read from the pinned sources:

| Android key | Super Nintendo | NES | Genesis | Master System, Game Gear | Saturn |
|---|---|---|---|---|---|
| A | A | A | C | 2 | B |
| B | B | B | B | 1 | A |
| X | X | A (rapid) | Y | — | Y |
| Y | Y | B (rapid) | A | — | X |
| L1 | L | — | X | — | Z |
| R1 | R | — | Z | — | C |
| L2, R2 | — | — | — | — | L, R |
| Start | Start | Start | Start | Pause (Master System), Start (Game Gear) | Start |
| Select | Select | Select | Mode | — | — |

| Android key | Dreamcast | PlayStation 2 | GameCube |
|---|---|---|---|
| A | B | Circle | A |
| B | A | Cross | B |
| X | Y | Triangle | X |
| Y | X | Square | Y |
| L1 | C (arcade sticks) | L1 | — |
| R1 | Z (arcade sticks) | R1 | Z |
| L2, R2 | L, R triggers, fully pressed | L2, R2 | L, R, fully pressed |
| L3, R3 | — | L3, R3 | — |
| Start | Start | Start | Start |
| Select | — | Select | — |
| Left stick | the stick | left stick | control stick |
| Right stick | — | right stick | C-stick |

On those three the D-pad is the console's D-pad and the sticks are the console's sticks; on every
other console the D-pad and the left stick are both the console's directions. Game Boy, Game Boy
Advance and PlayStation are as they were ([PS1_PROGRESS](PS1_PROGRESS.md)). Which physical button sends which
key code is the Retroid Pocket 5's own button-layout setting, not Cartridge's; Settings › Controls
› In-game A and B / In-game L and R swap them for every console, and per-console remapping is not
offered. L3 + R3 is the pause shortcut on every console and reaches a game first if pressed one
after the other; on the NES the first of them is that core's A+B button. L3 + Y quick saves and
L3 + X quick loads during play on every console that can make a state (ADR 0028), with the same
pass-through; on Dreamcast the engine stops for the moment of the capture. GameCube and PlayStation 2
answer the combos with *Can't save states*.

## Saves at a glance

| | Battery / memory card | States | Extra |
|---|---|---|---|
| GBA, GB, GBC | Libretro memory, ≤128 KiB, checkpointed | bare mGBA state (unchanged) | MBC3 clock |
| PlayStation | first card as Libretro memory, 128 KiB | bound to the core build | second card off |
| Super Nintendo | Libretro memory, ≤128 KiB | bound to the core build | S-RTC region exposed, untested |
| NES | Libretro memory (battery carts) | bound to the core build | Famicom Disk System not offered |
| Genesis, Master System, Game Gear | Libretro memory, ≤64 KiB | bound to the core build; excludes the save | Sega CD not offered |
| Saturn | internal 32 KiB as Libretro memory | bound to the core build | cartridge backup and clock files in the game's own folder |
| Dreamcast | memory units, files in the game's own folder | 27 MiB; when asked and when paused | console settings (`dc_nvmem.bin`) in the system folder, shared by all games |
| PlayStation 2 | two memory cards in the system folder, shared by all its games | none (68 MiB each by the engine's own figure; untried) | the engine's settings, shader cache and log beside the cards |
| GameCube | memory card, a folder of saves in the game's own folder | none (87 MiB each) | Dolphin's settings and shader cache beside it |

Files are `<content hash>.sram`, `.rtc`, `.state` in `files/saves`; a core's own files are in
`files/saves/<content hash>.files/`, and PlayStation 2's in `files/system/pcsx2/`. A hash names one
game on one console, so two consoles never share a save. Backups carry the first three kinds; a
core's own files are not in a backup yet, which for Dreamcast, PlayStation 2 and GameCube means
**their memory cards are not in a backup**.

A state is bounded at 64 MiB. On the small consoles one is also written every 15 seconds of play,
as the checkpoint a session is resumed from after the app is closed. Where a state is tens of
megabytes that would stutter, so those consoles write it only when the game is paused or left.
A console with no states at all (PlayStation 2, GameCube) is not stopped when the app is left: its
game stays loaded and paused, because there would be nothing to start it from again.
