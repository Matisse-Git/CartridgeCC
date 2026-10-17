# ADR 0028: Quick saves and a list of states

Date: 2026-10-17. Status: accepted; implemented and run on the RP5 with the original probes.

## Context

Each game had one manual state, `saves/<id>.state`. Save State overwrote it from the pause menu,
Load State loaded it, and *Start from save state* began a game from it. The owner asked for one
quick save and quick load, plus a list of saves with a thumbnail for each.

## Decision

Settled with the owner on 2026-10-17:

1. **The existing state is the quick save**, byte-for-byte at the same path. Older saves, backups and
   *Start from save state* keep working with no migration.
2. **Quick Save and Quick Load** are the pause menu's second and third rows. They also have combos that
   work during play: **L3 + Y** saves and **L3 + X** loads, by default. The combos are recorded in
   Settings › Controls by holding the buttons. Every button pressed while one is still held joins the
   combo, and letting go of the first ends it. A combo needs at least two gamepad buttons, never Home,
   Back or volume. It may not equal, contain or be contained in the other combo or either pause shortcut.
   Five quiet seconds keep the old combo. *Reset quick buttons* restores the defaults. Like the pause
   chord, each button reaches the game until the combo is complete. The game is then told that every
   button it holds is up, and hears nothing more until the combo's buttons are let go. A change of input
   mode while they are held (the operation's busy lock) keeps that latch; pause, focus loss and
   disconnection clear it.
3. **The list** is a *Save States* page reached from the pause menu. It shows *New save*, then the quick
   save, then the quick save it last replaced (*Before the last quick save*, loadable only), then saves
   newest first. Each row shows its thumbnail, date, time and size. Choosing one opens Load / Overwrite /
   Delete. The quick save and the one it replaced offer Load only. Load, Overwrite and Delete each ask
   once; Quick Save and Quick Load never ask. Saves are unlimited and never removed automatically. There
   is no Rename.
4. **After Quick Save, Quick Load or Load** is a setting (Settings › Controls): *Return to game* (default)
   or *Stay in menu*. New save and Overwrite always leave the menu open.
5. **Thumbnails** come from the screen the player saw, shader included. A paused game uses the frame the
   pause menu already holds (PixelCopy), and a combo copies the live screen before the state is made.
   Even black bars beside the picture are cut away (`domain/pictureBounds`). The picture is scaled to
   180 px high and stored as a JPEG. A screen that cannot be copied gives a save without a picture, never
   a failed save.
6. **During play**: a combo shows a 1.5-second notice and the game keeps running. A core whose state is
   only consistent at rest (`snapshots = Paused`, Dreamcast) stops its frames for the capture and goes on;
   no session transition happens. A core with `snapshots = Never` says *Can't save states on …*.
7. **Start from save state** gets *Save to start from*: *Quick save* (default) or *Newest save*, for every
   console. "Newest" is the newest of the quick save and the list. If that one cannot be loaded, the game
   starts from the beginning with one notice; it never falls back to an older state.
8. **Backups** always carry the quick save, as before. *Include every save state* (Settings › Library,
   off by default) adds the list, the previous quick save and every thumbnail. The archive's 1 GiB limit
   still applies.

## Storage

`storage/StateSlots` owns `saves/<id>.slots/`, which holds `previous.state`, `quick.jpg`,
`previous.jpg`, and `<uuid>.state` / `.jpg` / `.properties` for each listed save. A listed save is
published by its record, which is written last and carries the system, core revision, time, size and
SHA-256. A state without a record is an interrupted write and is never listed. Overwrite publishes a new
save before the old one is removed. A record from another core build is listed as such, can't be loaded,
and is never deleted automatically. The quick save first copies the state it replaces to `previous`, and
removes its old picture before the new state is written, so a picture never shows another moment.
Collection removal keeps the folder, like every other save.

## Consequences

- `EmulationEngine` gains `saveSlot`/`loadSlot`. `saveState`/`loadState` are the quick pair and now work
  while running as well as paused.
- The pause menu's existing tags are unchanged (`menu-1` Quick Save, `menu-2` Quick Load); *Save States*
  is `menu-5`.
- Device tests that go on in the pause menu after saving start with `stayInMenu`;
  `SaveStatesDeviceTest` (`-Suite Saves`) covers the default.
- Backups made with every state use the kinds `slot` and `slot-image`. A build from before this ADR
  refuses such a backup ("Unsupported backup data"); one made without them is unchanged.
