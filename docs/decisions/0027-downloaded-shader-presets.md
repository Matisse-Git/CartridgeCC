# ADR 0027: Downloaded shader presets

Date: 2026-10-16. Status: accepted; step 1 of 4 done.

## Context

The owner asked for RetroArch's shader library (crt-royale and the rest) to be downloaded with
one button and applied to games, with a setting per console and one per game. The four
"screen effects" the app has today (Standard, Sharp, LCD, Television) are LibretroDroid's built-in
shaders, chosen by the adapter-only option `cartridge_screen_effect`.

LibretroDroid was a prebuilt AAR from JitPack. Its native `ShaderManager` already draws multi-pass
chains, but takes only its own fixed list; nothing in its API accepts outside GLSL. The shim in
`cartridge_shim.c` touches drawing only for the three cores that draw for themselves, so it cannot
reach the software-drawn consoles.

## Decision

Settled with the owner on 2026-10-16:

1. **GLSL presets only** (`.glslp`, from `libretro/glsl-shaders`). Slang presets would need glslang
   and SPIRV-Cross on the device; they are left for later. No shader parameters in the first
   version: presets run with their defaults.
2. **One Update button downloads the whole library** at the newest `master` commit. It runs only
   when pressed, never on launch or in the background, and is disabled while a game runs. If the
   newest commit is the installed one it says so and downloads nothing; otherwise it shows the size
   and progress, can be cancelled, and works on any network. The archive is read through the
   bounded extractor and the new library replaces the old one only when it is complete, so a failed
   or cancelled update leaves the previous library working. The installed commit and date are
   shown.
3. **Resolution is game, then console, then a global default.** Each level holds "use the level
   above", Off, or a preset path inside the library (for example `crt/crt-royale.glslp`). Shader
   choices are stored per console (`GameSystem`), not per core, so a Game Boy and a Game Boy
   Advance can differ.
4. **Shader and screen effect stay separate rows.** When a game's shader resolves to a preset it
   replaces the effect, and the effect row says so; when it resolves to Off the effect works as
   before. They are never drawn on top of each other. Existing effect choices keep their keys and
   meaning.
5. **Settings › Shaders** holds the Update button and library status, the global default and one
   row per console; each game's Game options gets a Shader row. Picking a preset opens a page of
   the library's folders and then their presets, not a left/right cycle through about a thousand
   choices. A change made in a paused game shows when it resumes; there is no game running behind
   the menu to preview on.
6. **Every `.glslp` feature RetroArch's GLSL presets use is supported**: passes, scale types,
   float and sRGB framebuffers, mipmaps, wrap modes, aliases, LUT textures, frame counters and
   previous-frame history. A preset that needs anything missing is marked unsupported and never
   drawn half-working.
7. **A picked preset is checked before it is saved**, in a small GL context of its own that shares
   nothing with the game's and is destroyed afterwards: every pass compiled and linked, every LUT
   loaded. A failure shows a notice, keeps the picker open, marks the preset failed for this
   library commit, and leaves the previous choice in place. A saved preset that fails or is missing
   when a game starts gives the normal picture and one notice; the choice stays saved. Slowness is
   not detected; the owner judges it.
8. **Backups carry shader choices, not shader files**, in a new optional section so older backups
   still restore. Other console and game options stay out of backups as before.
9. **Aspect ratio and integer scaling are not part of this work**; they come after the owner has
   seen presets on the RP5.
10. **The shader runs in LibretroDroid's own final pass**, which every console's picture already
    goes through, including the hardware frames of Flycast, ARMSX2 and Dolphin. LibretroDroid is
    therefore built from its source in this repository (`libretrodroid/`, see its `UPSTREAM.md`)
    rather than taken as a prebuilt AAR.

## Steps

Each is its own commit, checked on the RP5 before the next starts.

1. **LibretroDroid from source with no visible change.** Done 2026-10-16: the `0.14.0` tag's
   source, its two submodules fetched by archive hash, and the minimum needed to build with NDK 27.
   Public Java API, exported JNI functions and linked libraries were compared with the AAR. Device
   results are in `docs/TESTING.md`.
2. `.glslp` loader and drawing, plus the separate check context.
3. The Update button: download, bounded extraction, atomic swap.
4. Settings, picker, per-console and per-game rows, the link to Screen effect, backup, docs, and
   the UX tour.

Done means, on the RP5 with one of the owner's games per console (consoles without one are listed
as not run): crt-royale on a software-drawn console and on one that draws for itself, an LCD preset
with frame history on Game Boy Advance, a deliberately broken preset, each level of the
game/console/global chain winning once, and a real sleep with a shader active.

## Consequences

- LibretroDroid's source and its changes are now this project's to keep; `UPSTREAM.md` lists every
  change from the tag. JitPack is no longer needed to build.
- The shader library is downloaded at run time from GitHub and is not in the repository or the APK.
  Its presets carry their own mixed licences; this build is for the owner's personal use.
