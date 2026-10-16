# ADR 0027: Downloaded shader presets

Date: 2026-10-16. Status: accepted; all four steps done.

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
2. `.glslp` loader and drawing, plus the separate check context. Done 2026-10-16: see "What the RP5
   showed" below and `docs/TESTING.md`.
3. The Update button: download, bounded extraction, atomic swap. Done 2026-10-16:
   `ShaderLibraryUpdater` with `GitHubShaderSource`. GitHub's API names the newest commit of `master`;
   if it is installed nothing is downloaded. Otherwise codeload's archive of that commit is read entry by
   entry: the repository's own top folder is dropped, only `.glsl`, `.glslp` and images by plain paths
   (letters, digits, `._+ -`, no hidden or `..` segment, at most 16 deep and 240 long) are written, within
   256 MB downloaded, 512 MB unpacked, 20,000 entries and 32 MB a file. The new library is written beside
   the old one and renamed into its place only when complete; a process killed mid-swap gets the old one
   back. The commit, its date and the counts are kept in the library. The button itself, disabled while
   a game runs, is step 4.
4. Settings, picker, per-console and per-game rows, the link to Screen effect, backup, docs, and
   the UX tour. Done 2026-10-16: Settings › Shaders, the picker pages, the Game options row, Screen
   effect rows that say a shader replaces them, the optional `shaders` backup section, and
   `LibretroEngine.reloadShader` for a paused game (docs/ux/SETTINGS_STRUCTURE.md).

Done means, on the RP5 with one of the owner's games per console (consoles without one are listed
as not run): crt-royale on a software-drawn console and on one that draws for itself, an LCD preset
with frame history on Game Boy Advance, a deliberately broken preset, each level of the
game/console/global chain winning once, and a real sleep with a shader active.

## What the RP5 showed (step 2, 2026-10-16)

The library's GLSL presets were written for desktop OpenGL. GLSL ES, which is all the RP5 has, allows
less, and RetroArch's own GL driver does nothing about it, so on a GLES device RetroArch refuses the same
presets. Checked on the RP5's Adreno 650 with the whole library at `libretro/glsl-shaders@148ce728`:

| How a pass is compiled | Presets that build and draw (of 621) |
| --- | --- |
| RetroArch's way only | 503 |
| also parameters as the shader's own constants, GLSL ES 3.00 for 1.10/1.20 sources, default precision, derivatives enabled | 524 |
| also global initialisers moved into `main` and int frame counters as floats (`GlslCompat`) | 544 |
| also the pass translated as desktop GLSL 3.30 by glslang and SPIRV-Cross, before the rewritten source | **605** |

Each later way is tried only when the ones before it fail. Precision qualifiers are read as highp
everywhere, as desktop GL reads them. Of the 16 still refused, two are broken in the library itself (a
missing pass file, a preset without passes); the others have errors no desktop driver should accept either
(a vec4 assigned to a vec3, no precision in a GLES branch, Cg's `float2`, a syntax error).

- **crt-royale's GLSL port needs the translator.** It empties `const` with a macro, so every one of its
  settings is a global initialised from others, and it passes ints where functions take floats. Rewriting
  the source by hand got half of it through and drew black; translated, all twelve passes build (3.3 s) and
  draw what they should, at 60 fps on the owner's Super Nintendo and PlayStation 2 games.
- **The Adreno 650's compiler gets one construct wrong.** crt-guest-dr-venom drew far too dark: read back
  pass by pass, its colour was right until `cmask1 *= TATE < 0.5 ? SlotMask(a) : SlotMask(b);`, which the
  driver evaluates to 0 although each call returns 1; written with `if`, it gives 1. Every pass is now given
  to the GPU with each `x op= cond ? f(a) : g(b);` written as branches (`GlslCompat.selectedCallsAsBranches`),
  which means the same; the final picture's mean on the check's test card went from 0.10 to 0.42.

## Consequences

- LibretroDroid's source and its changes are now this project's to keep; `UPSTREAM.md` lists every
  change from the tag. JitPack is no longer needed to build.
- The shader library is downloaded at run time from GitHub and is not in the repository or the APK.
  Its presets carry their own mixed licences; this build is for the owner's personal use.
