# ADR 0031: Freeze and fast-forward

Date: 2026-10-18. Status: accepted; implemented. Device results are in [TESTING](../TESTING.md).

## Context

The only way to stop a game was the pause menu (L3 + R3, Start + Select or Back), which dims the game and
covers half of it. There was no way to run a game faster. The owner asked for both, each on a controller
combo, with an icon in the top-right corner that shows the fast-forward speed. The defaults are L3 + B
and L3 + A. LibretroDroid already had a whole-number frame speed (`GLRetroView.frameSpeed`: the core runs
that many times for each frame shown) and a switch for its sound (`audioEnabled`). Neither was used.

## Decision

Settled with the owner on 2026-10-18:

1. **Freeze (L3 + B)** stops the game where it is, with no menu and nothing dimmed. A pause badge sits in
   the top-right corner. The same combo continues the game. The pause menu is unchanged. A freeze is a
   pause in every other way: it stops the frames, keeps the copied frame on screen, and writes the battery
   save and checkpoint, as any pause does. A frozen game is `Paused` with `EngineStatus.frozen`; the shell
   shows no page for it and keeps the router in gameplay mode.
2. **Fast-forward (L3 + A)** switches between normal speed and the speed chosen in Settings › Controls ›
   *Fast-forward speed*: 2× to 8× in whole steps, 2× by default, one speed for every console. The badge
   shows the speed. Sound is off while fast, for now, as the owner asked. Above 1× the host would raise its
   playback rate, and the self-drawing engines are timed by their own sound.
3. **Both combos are recorded** like the quick save and quick load combos (ADR 0028), under the same
   rules. No combo may equal, contain or be contained in another or a pause shortcut. *Reset quick
   buttons* restores all four. `input/QuickCombo` lists them, and the router hands each one to one action.
4. **Fast-forward is a capability of the engine** (`Core.fastForward`). It is off by default for the
   engines that draw for themselves (Flycast, ARMSX2, Dolphin). Those are timed by their sound in the shim
   and have not been run faster on the device. There L3 + A says *Fast-forward isn't available for …* and
   nothing changes. A core gets it once one of the owner's games shows it works. Freeze works everywhere.
5. **How they combine.** Freezing keeps the speed, and the game goes on at that speed. The pause menu,
   sleep, leaving the app and ending the game always return to normal speed with nothing frozen. The menu
   opened over a frozen game keeps the frame it was frozen on. While frozen, only the combos and Back do
   anything: quick save keeps the game frozen, quick load lets it go on, and fast-forward waits. Frozen
   time is not play time; fast-forwarded time counts as the real time it took.
6. **The badge** (`ui/GameBadge`) is drawn over the game, never into it, so a state's thumbnail or a
   PixelCopy never contains it. The pause bars and arrows are drawn, not typed, because some of Android's
   fonts show those characters as emoji.
7. **Combos that start with L3** reach a game that uses L3 as a short press before the combo is complete,
   exactly as the quick save combo always has. A combo without L3 can be recorded instead.

## Consequences

- `EmulationEngine` gains `freeze()` and `speed(frames)`; `pause()` also turns a freeze into the menu.
- `ConsoleSettings` holds a map of the four combos (stored under their old keys plus `freezeCombo` and
  `fastForwardCombo`) and `fastForwardSpeed`. Stored combos that would press one another all give way
  to the defaults.
- The view's speed and sound are reset by the host whenever a new game is loaded.
- Not decided here: fast-forward on the self-drawing engines, a hold-to-fast-forward mode, sound while
  fast, and rewind.
