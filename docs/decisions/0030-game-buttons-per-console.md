# ADR 0030: Game buttons per console

Date: 2026-10-18. Status: accepted; implemented, unit tested, device run below in [TESTING](../TESTING.md).

## Context

Controls had three button settings, all for every console: *Confirm button* (menus), *In-game A and
B* and *In-game L and R* (swaps of what a game is sent). A swap could not express what a console's own
pad needs. Saturn has two rows of three (A B C below, X Y Z above), and the RP5 has four face buttons
and shoulders. Settled with the owner on 2026-10-18.

## Decision

1. **Each console has its own game buttons**, per `GameSystem`, not per core. GB and GBA share mGBA
   but not buttons, and Genesis, Master System and Game Gear share Genesis Plus GX but not buttons.
   There is no every-console level and no per-game level.
2. **Confirm button stays the only global button setting.** It is for menus only and never changes
   what a game is sent. *In-game A and B* and *In-game L and R* are removed. Their stored values
   (`swapGameButtons`, `swapShoulders`) are dropped, not converted, including from an older backup.
3. **A console's page lists its own pad** (`GameSystem.pad`), each button by the name it has on that
   console, with the physical button that presses it. The lists come from the pinned cores' sources.
   They include a real extra a core reads by default (NES *A+B*, on L3), and leave out buttons only an
   arcade stick has (Dreamcast C and Z). FCEUmm's turbo A and B are not listed: the core keeps them off
   unless a core option turns them on, and Cartridge does not set it.
4. **The physical buttons** are A, B, X, Y, L1, R1, L2, R2, L3, R3, Start and Select. The D-pad and
   the sticks are not remappable. A physical button that none of the console's buttons uses sends
   nothing in that console's games.
5. **No two buttons share one.** Assigning a physical button that another of the console's buttons
   has makes the two trade places (`ButtonMap.assign`). Every button always has exactly one.
6. **The defaults are what each core was always sent**: every key from the physical button of the same
   name (the tables in [CONSOLES](../CONSOLES.md)). Nothing changes for an owner who doesn't touch the
   setting. A map equal to its default is not stored, so the console keeps following its default.
7. **Settings › Controls › Game buttons** has one row per registered console (*Default* or *Custom*).
   Each row opens that console's page. Choosing a button listens for the next physical button, which is
   assigned when let go. A D-pad direction is refused, and five quiet seconds keep the old one.
   Left/right steps through the twelve. *Use default buttons* forgets the console's map, and *Reset
   controls* forgets every console's. The paused console's page is also in Pause › Game Options.
8. **A change applies at once**: a game paused in the menu uses it when it resumes.
9. **Shortcuts read the physical buttons** before any mapping. Remapping a console never moves the
   pause shortcut or the quick save and load combos.
10. **Stored** as `buttons.<GameSystem>` = `A=B,B=A,…` (core key = physical button, `PadKey` names, which
    are stored ids). A stored map with an unknown button, a duplicate physical button or a missing button
    is ignored: the console has its default.
11. **Backups** carry the maps as an optional `buttons` object in the optional settings. Restoring sets
    the consoles it names and leaves the others alone.

## Consequences

- `ControllerRouter.gameButtons` replaces the two swaps. `MainActivity.inputChanged` sets it from the
  selected game's console. The D-pad and sticks are unaffected.
- Which physical position sends which key code is still the Retroid's own button-layout setting. The
  page names buttons by the key code they send, so press-to-assign is right whatever that mode is.
- Turbo buttons, the D-pad, sticks and per-game buttons remain possible later on the same map.
