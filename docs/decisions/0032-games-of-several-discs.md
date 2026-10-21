# ADR 0032: Games of several discs

Date: 2026-10-20. Status: accepted; implemented for PlayStation. Device results are in [TESTING](../TESTING.md).

## Context

Every disc was a game of its own (ADR 0017). A game's identity was the content hash of its one disc, and its
memory card, states, history and artwork hung off that id. A two-disc game was two shelf entries with two
memory cards. Disc 2 could not see disc 1's save, so it could not be played at all. The owner had hidden
discs 2 to 4 of each set by hand. `.m3u` playlists were counted and skipped. The scanner ignored the disc
control LibretroDroid 0.14.0 already offered (`changeDisk`, `availableDisks`, `currentDisk`, on the
original Libretro interface), and the project rules forbade showing multi-disc as a capability.

The owner's library has four sets on the RP5. Three are PlayStation: one of four discs with an `.m3u`
named without the region and written with `\r\r\n` line ends, one of three with an `.m3u`, and one of two
with no playlist, whose two discs each have a memory card. The fourth is a Saturn set of two discs with an
`.m3u`. There is also a two-disc Dreamcast set with no playlist.

What the pinned engines do, read from their sources: all five disc engines read an `.m3u`. PCSX ReARMed,
Beetle Saturn and Flycast ask the frontend which disc-control version it speaks and fall back to the
original interface. ARMSX2 and Dolphin offer only the extended interface. PCSX ReARMed writes the disc in the
drive into its states and puts that disc back when a state is loaded. Closing its drive holds the lid open
to the game for two seconds (`SetCdOpenCaseTime`), as a real console does.

## Decision

Settled with the owner on 2026-10-20 (the grilling session's questions 1 to 19):

1. **One game, several discs.** A game made of several discs is the record of its first disc. The game
   keeps that disc's id, so its saves, artwork, favourite and history stay where they were. The other discs
   leave the shelf. Every disc keeps its own record, private copy and files, untouched. Room version 8 adds
   `discs` (disc, game, number, how it was found, whether it was hidden before it joined) and
   `separated_discs`, and gives `games` the disc last in the drive (`disc`) and the count the engine
   reported for one file that holds several (`discCount`).
2. **How discs are found to be one game.** At each scan, folder by folder, two rules apply in order. An
   `.m3u` beside the discs comes first: only names in its own folder are followed, and any line end is
   read. Then names that are the same apart from `(Disc N)`, `(Disc N of M)` or `(CD N)`, on the same
   console. A scraped title is never used. A set that mixes consoles, or in which two files claim one disc
   number, stays separate. At most eight discs make a game (`domain/DiscSet`).
3. **The owner's grouping wins.** Details › Manage game has *Add a disc…* (any game of one disc on the
   same console) and *Separate discs*, each with one *Undo last disc change*. A game grouped by hand is
   marked `manual`; a disc separated by hand is listed in `separated_discs`. No later scan changes
   either.
4. **One memory card per game**, the game's own (`<id>.sram`, and `<id>.files` for a core with files of
   its own). Every disc reads and writes it. When discs join a game that has no card, the game takes a
   copy of the first card one of them has. Manage offers *Use disc N's memory card* for every other disc
   that has one. The card it replaces is kept in `saves/<id>.discs/`, and *Use the memory card from
   before* swaps it back. A disc's own card is never changed or deleted (`storage/DiscCards`).
5. **Hidden discs.** A hidden disc that joins a game is shown again, as a disc of that game. The game is
   hidden only if all its discs were. Separating the discs hides again each one that was hidden before it
   joined. Hiding the game hides all its discs, and a rescan never brings a disc back on its own.
6. **The shelf and Details.** One case per game. The title is the first disc's name without its disc tag,
   and the case's market is read from that name, never from the playlist's. Play history is the sum of
   all the discs'. Details lists the discs as *Disc N*, each with its file as the owner keeps it (folder
   and name), the console, the private copy's size and its tracks. It marks the disc last played, shows
   a number the set skips as *Not found*, and names the playlist the set came from. Pressing a disc starts
   the game on it, and a disc whose private copy is gone has its own *Reconnect* row. A game of one disc
   shows a single *File* row in the same form.
7. **Starting.** *Play* starts the disc last in the drive, disc 1 the first time. An engine that can
   change discs (`Core.discs`) is started from a playlist the app writes, `files/playlists/<id>.m3u`, with
   one absolute path per private copy. It starts on the chosen disc through the extended interface's
   `set_initial_image`. Every disc must be present: discs and states are numbered by their place in that
   list. One file that holds several discs (a PlayStation `.pbp`) is changed to its disc before the first
   frame. An engine without `Core.discs` is started from the chosen disc's own file.
8. **Changing disc.** The pause menu has *Change Disc* where the engine can change discs and has more than
   one. Choosing a disc opens the drive, puts that disc in, closes the drive, and resumes the game. For a
   moment `ui/GameBadge` names the disc. The disc in the drive is written to the library at every change
   and when the game ends or is left. There is no combo for it.
9. **States.** A state carries its disc: PCSX ReARMed records it and loading puts it back, so checkpoints,
   quick saves and listed saves need no envelope change. States a disc made while it was a game of its own
   are listed under the game as *From disc N on its own*. They are only loaded, never changed, and the
   disc they came from goes back in the drive afterwards.
10. **The host.** LibretroDroid answers `GET_DISK_CONTROL_INTERFACE_VERSION` with 1 and takes
    `SET_DISK_CONTROL_EXT_INTERFACE`, keeping its first seven functions as the original interface. It
    gains `setInitialDisk`, called between the core's creation and the game's load (`libretrodroid/UPSTREAM.md`).
11. **Backups** are version 2. They carry the discs and the separations, and a restore applies them unless
    the library already decided about any of those discs. A version 1 backup restores each disc as a
    game, as it was.
12. **Consoles one by one.** Grouping, the shared card and per-disc Details apply to every disc console
    now. *Change Disc* (`Core.discs`) is on only for PlayStation, the first console to change disc with one
    of the owner's games on the device. Saturn and Dreamcast follow, each after its own device check.
    PlayStation 2 and GameCube can now be offered the extended interface their engines ask for, and need
    the same check.

## Consequences

- `GameRequest` carries the discs and the disc to start on. `EngineStatus` carries the disc in the drive
  and how many there are. `EmulationEngine` gains `changeDisc` and `loadDiscState`.
- A rescan of a set reports one game, not one per disc. A playlist whose discs are not beside it is
  reported, and those discs are added on their own.
- Because PCSX ReARMed and the other engines now see version 1 of the interface, Flycast and Beetle Saturn
  register the extended interface instead of the original one. The functions the host uses are the same.
- Not covered by a test program: a game that accepts the swap at its own "insert disc 2" prompt. That
  needs a save near the end of a disc. It stays the owner's check until one of their games has done it.
- Not decided here: a multi-disc case model, multi-disc PS2 and GameCube games, and discs in subfolders a
  playlist names.
