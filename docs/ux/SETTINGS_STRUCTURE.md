# Settings structure

One destination: START › Settings. Seven sections, grouped by what the player wants to do. Every row
below is wired to real behaviour; nothing is a placeholder. Row definitions live in
`shell/ShellPages.kt` (`settingsRows`, `controlRows`).

Rules:

- A row is a command, a switch, a choice (‹ value ›) or a link (›). Its value is always visible.
- Changes apply immediately and are stored immediately. There is no Save.
- The focused row's explanation appears in one help line under the list.
- A row that cannot act right now is dimmed and says why in the help line.
- A setting that waits for the next game start says so.

## Library

| Row | Kind | Effect | Stored |
|---|---|---|---|
| Add games | command | Folder picker → import | — |
| Add a single game file | command | Document picker → import one game | — |
| Refresh library | command | Fast rescan of every folder (shown when folders exist) | — |
| Refresh when Cartridge opens | switch, on | Quiet rescan at launch | `autoScan` |
| *Game folders* | | | |
| *folder name* | link | Refresh this folder · Stop using this folder · Choose this folder again (if unreadable) | Room `directories` |
| *Browsing* | | | |
| Sort games by | choice | Import order · Title A–Z · Recently played · Most played | Room `collection_state` |
| Hidden games | link | List; select a game to restore it | Room `removed_games` |
| *Backup* | | | |
| Back up collection | command | Writes a backup archive through the system file dialog | — |
| Include every save state | Off · On | Adds each game's list of saves, the quick save it last replaced and their pictures; the quick save is always included | `backupAllStates` |
| Restore from backup | command | Review page: Add to my collection · Also restore settings · Cancel | — |

Moved here from: Tools shelf (Add game, Folder, Rescan), Collection menu (Sort), Folders & search
(Add folder, Rescan, Auto-scan, Forget), Console settings (Export backup, Restore backup).
The favourites filter is now the Favorites shelf. Search is in the System Menu.

## Appearance

| Row | Kind | Effect | Stored |
|---|---|---|---|
| Layout | choice | Horizontal · Vertical · Grid; the focused game is kept | `layout` |
| Motion | choice | Full · Reduced | `reducedMotion` |
| Game start | choice | Fade · Iris · Shutter: how the screen closes when a game starts and opens once it has a picture, and the same on the way back. Shows "Cut" and cannot be changed under reduced motion | `launchTransition` |
| Accent colour | choice | Sand · Sage · Sky · Rose · Paper | `accent` |
| Button hints | choice | Until I know them · Always · Never | `hints` |
| *Status* | | | |
| Clock and battery | switch, on | Shows or hides the status at the top right | `showStatus` |
| Battery percentage | switch, on | The number beside the battery mark | `showPercent` |
| Time format | choice | Match Android · 12-hour · 24-hour | `clock` |
| *Sound* | | | |
| Insert and eject sound | switch, off | The contact sound; plays with full motion only | `mediaSound` |

"Until I know them" shows hints until five games have been started (`launches`), then hides them.

There is no separate Audio section: the application has exactly one sound of its own. Game audio
options are per console, under Gameplay.

## Gameplay

Options belong to a console. Each console that shares a core shares a heading. One option belongs
to every console and comes first.

**Every console**

| Row | Choices | Applies | Core |
|---|---|---|---|
| Start from save state | Off · On. On starts a game from a saved state; without one, or if it cannot be loaded, the game starts from the beginning | next start | frontend (`option.startFromState`); never sent to a core |
| Save to start from | Quick save · Newest save. Which state *Start from save state* uses; never an older one in its place | next start, every console | shell preference `startFromLatest`, sent as `cartridge_start_slot` |

A game can override it like any other option (`game.<content id>.startFromState`).

**Game Boy · Color · Advance**

| Row | Choices | Applies | Core |
|---|---|---|---|
| Screen effect | Standard · Sharp pixels · Handheld LCD · Television | at once | frontend shader |
| Audio smoothing | Off · On | at once | `mgba_audio_low_pass_filter` |
| Frame skipping | Off · Every other frame · Two of every three | at once | `mgba_frameskip` |

**PlayStation**

| Row | Choices | Applies | Core |
|---|---|---|---|
| BIOS file | Added / Not added; select to choose a file | next start | private `system/` |
| Screen effect | Standard · Sharp pixels · Handheld LCD · Television | at once | frontend shader |
| Sharper 3D | Off · On | next start | `pcsx_rearmed_neon_enhancement_enable` |
| Colour smoothing | On · Off | next start | `pcsx_rearmed_dithering` |

**Super Nintendo**, **Nintendo Entertainment System**, **Genesis · Master System · Game Gear**,
**Saturn** (added 2026-10-11; the section is generated from the console registry, one heading per
core in registry order)

| Heading | Row | Choices | Applies | Core |
|---|---|---|---|---|
| Super Nintendo | Screen effect | Standard · Sharp pixels · Handheld LCD · Television | at once | frontend shader |
| | Reduce sprite flicker | Off · On | at once | `snes9x_reduce_sprite_flicker` |
| Nintendo Entertainment System | Screen effect | as above | at once | frontend shader |
| | Reduce sprite flicker | Off · On | at once | `fceumm_nospritelimit` |
| Genesis · Master System · Game Gear | Screen effect | as above | at once | frontend shader |
| | Reduce sprite flicker | Off · On | at once | `genesis_plus_gx_no_sprite_limit` |
| | Audio smoothing | Off · On | at once | `genesis_plus_gx_audio_filter` |
| Saturn | BIOS file · America and Europe | Added / Not added; select to choose a file | next start | private `system/mpr-17933.bin` |
| | BIOS file · Japan | Added / Not added; select to choose a file | next start | private `system/sega_101.bin` |
| | Screen effect | as above | at once | frontend shader |

**Dreamcast**, **PlayStation 2**, **GameCube** (added 2026-10-11, ADR 0023; PlayStation 2's rows since 2026-10-12, ADR 0026)

| Heading | Row | Choices | Applies | Core |
|---|---|---|---|---|
| Dreamcast | BIOS file | Added / Not added; select to choose a file. Optional | next start | private `system/dc/dc_boot.bin` |
| | Screen effect | as above | at once | frontend shader |
| | Sharper 3D | Off · On | next start | `reicast_internal_resolution` 640x480 / 1280x960 |
| PlayStation 2 | BIOS file | Added / Not added; select to choose a file. **Required**: a game opens the setup dialog without it | next start | private `system/pcsx2/bios/ps2-bios.bin`; any region's dump, taken by its own file table |
| | Screen effect | as above | at once | frontend shader |
| | Sharper 3D | Off · On | next start | `armsx2_upscale` 1x / 2x |
| GameCube | Screen effect | as above | at once | frontend shader |
| | Sharper 3D | Off · On | next start | `dolphin_efb_scale` 1 / 2 |
| | Exact video output | Off · On | next start | `dolphin_xfb_to_texture_enable` enabled / disabled |

*Start from save state* is listed for every console that can make a state; GameCube and
PlayStation 2 cannot, so it is absent there, in their games' options and in their pause menus. *Exact video output* is read
back on the device by `GameCubeCoreTest`: with it on, a program that paints the video chip's memory
is shown, and the variable is `disabled` in the running core. *Sharper 3D* on these three reaches
the core and the host's picture follows the size the core draws; no 3D picture has been looked at.

A BIOS row of a console that cannot start without it is marked until the file is added. The same
file can be added from the import result, the game's details and the dialog a Saturn game shows
when started without one.

Then: *A game can use its own choices* (information), and, when a session is saved, **Discard
saved session**.

The help line for each option begins with its console's name, so scope is stated at the point of
reading: "PlayStation: Draws 3D at twice the original resolution…".

### Scope

```
default  →  console setting  →  this game only
```

- **Console** values are set here.
- **Game** overrides are set from the game: Details › Game options, or Pause › Game Options.
  Each row there reads *Console setting* or *Only this game*; left/right steps through
  "console setting" and every choice; **Use console settings** removes all overrides for the game.
- There is no global level above consoles: the two cores share no option, so a global switch would
  be a duplicate of the console one.

Stored in the presentation preferences: `audioFilter` and `frameSkip` keep their original names
and types (they are part of backups); new console values are `option.<id>`; overrides are
`game.<content id>.<id>`. Resolution is `GameOptions.resolve`; the adapter receives the result
once per session start and live options again when changed while paused.

Firmware and memory-card variables (`pcsx_rearmed_bios`, `_memcard1`, `_memcard2`) are fixed by the
save design and cannot be displaced by an option.

## Shaders

RetroArch's GLSL shader presets (ADR 0027), drawn by the host in place of the Screen effect.

| Row | Does | Notes |
|---|---|---|
| Update shaders | Asks GitHub for the newest commit of `libretro/glsl-shaders`; downloads it only if it is not installed | value: *Installed* / *Not downloaded* / *Checking…* / *12.3 MB of 26.0 MB*; help line: commit, date and preset count. While it runs the row reads **Stop updating** and cancels. Dimmed while a game runs. A failure or a stop keeps the library that was there |
| Every console › Shader (›) | Opens the picker for every console | |
| Consoles › one row per console (›) | Opens the picker for that console | value: what that console's games get; help line: *This console's own*, *From every console* or *No shader is chosen* |

The **picker** is a page: *Use the every-console shader* (or *Use the console's shader* for a
game), *No shader*, then the library's folders, then a folder's presets. Choosing a preset builds it
and draws two frames in a GL context of its own first: if that fails, the reason is shown, the page
stays open, the preset is marked *Didn't work on this device* for this library commit, and the
previous choice is kept. A preset an update removed shows as *name (missing)*; its games then start
without it, with one notice.

```
every console  →  console  →  this game only        (No shader at any level stops there)
```

A game's own choice is the **Shader** row in Game options. A game in the pause menu picks up a change
when it resumes. While a shader applies, that game's **Screen effect** row reads *Replaced by
shader*, and Gameplay's console Screen effect rows say on which consoles a shader replaces them; the
effect still stores its value for when the shader is off. Stored as `shader.global`,
`shader.console.<GameSystem>` and `shader.game.<content id>` (`off` or `preset:<path in the
library>`); Reset settings clears the first two, as with other console options. The library itself
is in the app's private `files/shaders/library`.

## Controls

| Row | Choices | Stored |
|---|---|---|
| Confirm button | A · B goes back / B · A goes back. Menus only, every console | `swapMenuButtons` |
| Stick sensitivity | Low · Medium · High (dead zone 50%, 35%, 20%) | `deadZone` |
| Pause shortcut | L3 + R3 · Start + Select | `alternateShortcut` |
| Quick save buttons | a recorded combo, L3 + Y by default: hold the buttons together, let go of one | `quickSaveCombo` (key codes) |
| Quick load buttons | a recorded combo, L3 + X by default | `quickLoadCombo` |
| Reset quick buttons | command: L3 + Y and L3 + X again | |
| After Quick Save, Quick Load or Load | Return to game · Stay in menu | `returnToGame` |
| Game buttons › one row per console (›) | *Default* / *Custom*; opens that console's page | `buttons.<GameSystem>` |
| Reset controls | command; also resets the quick buttons, the row above and every console's game buttons | |

**A console's game buttons** (ADR 0030) are a page of its own pad's buttons, each by its name on that
console (Saturn A, B, C, X, Y, Z, L, R, Start; PlayStation Cross, Circle, …), with the physical button
that presses it. Choosing a row listens for one button, assigned when let go. A D-pad direction is
refused, and five quiet seconds keep the old one. Left/right steps through A, B, X, Y, L1, R1, L2, R2,
L3, R3, Start, Select. A physical button another of the console's buttons has trades places with it.
*Use default buttons* (its help line lists the default) forgets the console's map. The defaults are
what each core was always sent (docs/CONSOLES). A physical button no console button uses sends nothing
in that console's games. The D-pad, the sticks, the pause shortcut and the quick buttons are not part
of it. A change reaches a paused game when it resumes. Stored as `buttons.<GameSystem>` = `A=B,B=A,…`
only while it differs from the default, and part of a backup's settings (`buttons`).

A combo needs two or more gamepad buttons and may not equal, contain or sit inside the other combo or
either pause shortcut; the recorder says why it refused one and keeps listening, and five quiet seconds
keep the old one (ADR 0028). None of these four rows is part of a backup's settings.

Also reachable while paused (Pause › Game Options › Controls), where it is the only settings
section offered. Pause › Game Options also has *<console> buttons*, the paused console's page.

## System

| Row | Kind | Effect |
|---|---|---|
| Offer Cartridge as home screen | switch | Enables the HOME alias; Android still asks before changing the default |
| Choose home app | command | Opens Android's home-role request or home settings |
| Android settings | command | Opens the system Settings app |
| *This device* | | |
| Storage used | information | Games · Artwork · Saves, measured when Settings opens |
| About & licences | link | Version, components, and each licence notice in a reader |

## Advanced

| Row | Effect |
|---|---|
| Check every game file | Re-reads and re-hashes every game in every folder (the former "Verify all ROM files") |
| Show frame rate | switch, off. Prints the frames the library stage handed to the display in the last full second, beside the clock. Stored as `showFrameRate`; nothing is drawn over a running game |
| Reset settings | Appearance, controls and gameplay return to defaults. Games, saves, folders, overrides for individual games and the HOME role are not touched |

## Where the old settings went

| Before | Now |
|---|---|
| Tools › Motion, Sound, Menu shortcut | Appearance › Motion, Sound; Controls › Pause shortcut |
| Collection › View | Appearance › Layout |
| Collection › Sort, Show favorites, Reset filters | Library › Sort games by; Favorites shelf; B clears a search |
| Collection › Remove entry, Undo | Details › Manage game › Hide; System Menu › Restore hidden game; Library › Hidden games |
| Folders & search | Library (folders); System Menu › Search |
| Console settings › Controller | Controls |
| Console settings › Game settings | Gameplay, by console; Game options, by game |
| Console settings › HOME candidate, Choose default home | System |
| Console settings › First-run setup | Removed: the empty library is the welcome |
| Console settings › About & licences | System › About & licences |
| Console settings › Resume / Discard saved session | System Menu › Continue; Gameplay › Discard saved session |
| Console settings › Export / Restore backup | Library › Backup |
| Console settings › Android device settings | System › Android settings |
| Console settings › Import PS1 BIOS | Gameplay › PlayStation › BIOS file; also offered on import and on a failed launch |
| Scraper › Region & language, Import pack | Unchanged, inside Artwork & Info |

## Not offered, and why

| Setting from the brief | Reason |
|---|---|
| Light / automatic theme, background style, display scale, animation speed | Not implemented; a switch would do nothing |
| Aspect ratio, integer scaling | The emulator view exposes no control for them in this integration |
| Rewind, fast-forward | Not integrated in the adapter |
| Core selection | One embedded core per console |
| Remapping the D-pad or sticks, per-game buttons, turbo buttons, touch controls | Not implemented (game buttons are per console, ADR 0030) |
| UI sounds, music, latency | One sound exists |
| Debug logs, cache management, database maintenance | No user-facing implementation |
| Search within settings | About thirty rows in six sections; not warranted |
