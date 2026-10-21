# Project Cartridge: commercial strategy report

Prepared 2026-10-20 from the repository at commit `768f8c9` plus the uncommitted multi-disc work in the
working tree (48 modified files, ADR 0032). No source code was changed to write this report.

> **Read this first.** Everything in this report about law, visas, tax and licences is **preparation material
> for your conversations with professionals. It is not legal, immigration or tax advice.** Where something
> depends on facts I cannot see (your contract, your employer, current agency practice) I say so. Market
> numbers about competitors are the ones you supplied; I have not verified them.

---

## Executive summary

**What the code actually is today.** The app is a polished, single-process Android frontend (Kotlin/Compose +
Filament 3D) that embeds 9 Libretro cores through an in-tree copy of LibretroDroid. It plays 13 consoles: GB,
GBC, GBA, NES, SNES, Genesis, Master System, Game Gear, PS1, Saturn (needs BIOS; you're playing a game of yours
on it, though `docs/CONSOLES.md` still says no game has run), Dreamcast, GameCube and PS2 (needs BIOS). **Nintendo 64 is not implemented.** It's only recognised at import
(`docs/CONSOLES.md`). Genesis, Master System and Game Gear are implemented but were missing from your list. Every
result so far comes from **one device** (Retroid Pocket 5, Snapdragon 865).

**Top 3 recommendations**

1. **Don't take any income until Phase 0 is finished, and expect your employer to be the hardest gate.** You
   work for a game company and this is an emulator, so the conflict-of-interest question matters as much as the
   副業 rule. One housekeeping item: **all 42 commits are authored with your work address,
   `faes.matisse@grev.co.jp`**, left over in your global git config from remote work. You've confirmed the
   machine is personal and the work was done on free time and days off. The address is still the first thing
   someone reviewing ownership would notice, so switch it and explain it up front (§3.1).
2. **The current build can't be sold as closed source.** Two separate reasons:
   - **LibretroDroid (GPL-3.0-or-later) is compiled into the same app and process as your frontend.** If you
     distributed the app today, the whole APK would have to be GPL.
   - **Snes9x and Genesis Plus GX ban commercial use outright.** That covers Patreon builds, including free
     builds used to promote a paid tier.

   Adopt **Option 1** (a closed frontend plus a GPL "player" process in the same APK) and swap those two cores
   for GPL ones. **Don't adopt Option 2.** It leaves the GPL host in your process, and Google Play forbids
   downloading native code from outside Play (§1, §5).
3. **Shrink the Japan-specific legal surface before anything is public, then launch narrow.**
   - Dolphin's binary contains **hard-coded Wii decryption keys**.
   - Flycast's binary contains **arcade-cartridge decryption code**.
   - ScreenScraper's terms restrict non-free apps.
   - The repo history still holds branded and ripped 3D models.

   Fix or decide on each of these (§2). Then run a Patreon alpha for handheld owners only, and ship a Play
   Store 1.0 only after touch controls (or a controller-only listing), play-in-place storage and device tiers
   (§8, §9).

**Your next 5 actions this week (legal and employer prep first)**

1. **Read your 雇用契約書 and 就業規則** with the checklist in §3.3. Note the exact 副業 approval procedure,
   any IP-assignment or invention clause, and any 競業避止 or conflict-of-interest wording. Photograph or copy
   the relevant articles for your own records.
2. **Fix the git identity and keep your evidence.**
   - Set a personal email in your global git config, since this is your personal machine.
   - Don't rewrite the old commits; you'll explain them instead (§3.1).
   - Note which days off cover the weekday commits and work (Fri 9 Oct afternoon, Tue–Thu 13–15 Oct, Tue 20
     Oct), from your leave records, while they're easy to find.
3. **Book a 行政書士 consultation.** Bring the one-paragraph description in §4.1 and the questions in §4.2. Ask
   specifically about 資格外活動許可 for selling your own software, and about the 特定商取引法 seller display.
4. **Draft (but don't send) the employer disclosure** using §3.4. Send it only after the 行政書士 has told you
   what the immigration side needs, so you ask your employer for the right thing once.
5. **Do the engineering prep that earns nothing and is safe now.** On a branch, replace Snes9x with a GPL SNES
   core and Genesis Plus GX with GPL Genesis/SMS/GG cores, and start the player-process split (§5). Both are
   needed whatever the lawyers say, and neither involves income.

---

## 1. Licence audit

### 1.1 How the cores are loaded today

- **Built from pinned source, one shared library per core.**
  - `app/src/main/cpp/CMakeLists.txt` fetches each core by archive hash or commit: mGBA there, and the others in
    `pcsx.cmake`, `cores.cmake`, `flycast.cmake`, `armsx2.cmake` and `dolphin.cmake`.
  - It builds each one into its own `lib*_libretro.so` for arm64.
  - Gradle packages them in the APK's `lib/arm64-v8a/`, and `useLegacyPackaging = true` extracts them to
    `nativeLibraryDir` (`app/build.gradle.kts`).
  - In the current debug APK (45 MB) the PS2 core is 22 MB, Flycast 19.6 MB, Genesis Plus GX 13 MB and Dolphin
    12.9 MB.
- **Your code is statically linked *into* every core.** `cartridge_shim.c` is compiled into each core
  (`cores.cmake`: `cartridge_core()`, `cartridge_hardware_core()`, `cartridge_external_core()` via
  `core_wrap.cmake`), and it renames the core's `retro_*` callbacks. `mgba_frontend.c.in` and
  `pcsx_frontend.c.in` wrap upstream frontends. These files become part of GPL/MPL binaries when you
  distribute them.
- **dlopen'd in-process.** At game start `app/src/main/java/dev/cartridge/emulation/LibretroEngine.kt:97`
  resolves `File(nativeLibraryDir, coreFile)` and hands it to LibretroDroid's `GLRetroView`.
  `libretrodroid/src/main/cpp/core.cpp:39` then calls `dlopen(path, RTLD_LOCAL | RTLD_LAZY)`. This all happens
  in the **same process** as Compose and Filament. `app/src/main/cpp/save_bridge.c` (`libcartridge_saves.so`)
  re-opens the already-loaded core with `RTLD_NOLOAD` to read and write its memory.
- **LibretroDroid is a Gradle module of the app.** `implementation(project(":libretrodroid"))` puts its
  GPL-3.0 Kotlin/Java classes in the same dex as your frontend, and `MainActivity`/`LibretroEngine` call them
  directly.
- There is **no separate process** (`AndroidManifest.xml` has one activity, no `android:process`) and **no
  runtime download** of cores. The only runtime download is the shader library (§1.3).

**Consequence (to confirm with a lawyer):**

- Under the FSF's reading, which most practitioners treat as the safe assumption, your frontend plus
  LibretroDroid is one combined work. Distributing it means licensing the frontend under GPL-3.0-compatible
  terms and publishing its source.
- On top of that, Snes9x and Genesis Plus GX can't be distributed commercially at all.
- The repo's own `docs/LICENSING.md` § "Release gate" already reaches the same conclusion.

### 1.2 Component table

"Commercial OK" means the licence permits selling or charging. "Closed-source OK" means you could combine it
with your closed frontend without opening the frontend. "Repo" means I read the licence text in the repository.
"LICENSING.md" means I'm relying on the repository's own audit and didn't re-read upstream. "Unverified" means
neither.

| Component | Used for | Licence (source of the claim) | Commercial OK? | Closed-source OK? | Action needed |
|---|---|---|---|---|---|
| **LibretroDroid 0.14.0** (in-tree, modified: `libretrodroid/`, changes in `libretrodroid/UPSTREAM.md`) | The host: core loading, GL renderer, audio, input, disc control, shader final pass | GPL-3.0-or-later (repo: `libretrodroid/LICENSE`, source headers) | Yes | **No.** It's in your process and dex today | **Move into the GPL player** (§5). Publish its source with your changes |
| mGBA 0.10.5 | GB, GBC, GBA | MPL-2.0 (repo: `docs/licenses/mGBA-MPL-2.0.txt`) | Yes | Yes for *other* files. MPL is per-file copyleft | Publish mGBA source plus `mgba_frontend.c.in`. Goes in the player anyway |
| blip_buf (inside mGBA) | Audio resampling | LGPL-2.1-or-later (repo) | Yes | Only with relinking materials | Covered once it's in the GPL player |
| inih (inside mGBA) | Config parsing | BSD-3-Clause (repo) | Yes | Yes | Keep the notice |
| PCSX ReARMed `c8816799` | PlayStation | GPL-2.0-or-later (repo text; headers per LICENSING.md) | Yes | No | Player side; offer source |
| **Snes9x 1.63** | Super Nintendo | **Snes9x licence: personal, non-commercial use only** (repo: `docs/licenses/Snes9x-non-commercial.txt`, lines 172–184: commercial use "includes … using Snes9x as a promotion for your commercial product"; some components say "under no circumstances will commercial rights be given") | **No** | No | **Remove from every distributed build, free tiers included.** Replace with **Beetle Supafaust** (GPL-2.0, Mednafen-based, built for ARM handhelds; licence and speed unverified) or **Mesen-S / Mesen 2** (GPL-3.0; unverified). bsnes (GPL-3.0) is accurate but heavy. Every Snes9x variant (2002/2005/2010) has the same licence and isn't a fix |
| FCEUmm `7a542dab` | NES | GPL-2.0(-or-later per LICENSING.md; repo has GPL-2.0 text) | Yes | No | Player side |
| **Genesis Plus GX `393f70cd`** | Genesis, Master System, Game Gear | **Non-commercial: "Redistributions may not be sold, nor may they be used in a commercial product"** (repo: `docs/licenses/Genesis-Plus-GX-non-commercial.txt:12`). Bundles Nuked OPN2/OPLL (LGPL-2.1+), Tremor (BSD) | **No** | No | **Remove.** Genesis: **ClownMDEmu** (AGPL-3.0, portable C; unverified) or **BlastEm** (GPL-3.0; check that its arm64 path isn't x86-JIT-only; unverified). Master System/Game Gear: **Gearsystem** (GPL-3.0; unverified). **PicoDrive is not an option**: it is also non-commercial |
| Beetle Saturn `ec20ea05` | Saturn | GPL-2.0 (repo text; Mednafen) | Yes | No | Player side. Don't switch to Yabasanshiro, which is non-commercial |
| Flycast 2.7 `5aa091fd` | Dreamcast | GPL-2.0-or-later (repo text; `core/emulator.cpp` header in the pinned checkout says "version 2 … or any later version"). Submodules: libchdr, rcheevos (MIT), asio (BSL-1.0), libjuice (MPL-2.0), tinygettext (zlib), VIXL (BSD-3) (LICENSING.md) | Yes | No | Player side; collect per-library notices (LICENSING.md says they haven't been) |
| ARMSX2 `0076b5c3` (PCSX2 for ARM64) | PlayStation 2 | GPL-3.0 (repo text). Links its own build of libpng, libjpeg-turbo, zstd, LZ4, libwebp, SDL, FreeType, PlutoVG/SVG, curl, plus 3rdparty fmt, rapidyaml, SoundTouch (LGPL-2.1), FreeSurround (GPL), Dear ImGui, rcheevos and others (LICENSING.md) | Yes | No | Player side; **collect the notices, which aren't collected yet** |
| Dolphin (libretro fork `114977f1`) | GameCube | GPL-2.0-or-later, "in aggregate … compatible with the GPLv3" (repo: `docs/licenses/Dolphin-GPL-2.0-or-later.txt`). 23 bundled externals, including a second mGBA | Yes | No | Player side; collect notices. **See §2.1 (Wii keys)** |
| Dolphin `Data/Sys` subset (`dolphin-sys.zip` asset) | GameCube fonts, DSP tables, per-game settings, shaders, `codehandler.bin` | Fonts: Droid Sans derivatives, **Apache-2.0** (verified: `GC/font-licenses.txt` in the built zip). DSP ROM/coef: Dolphin's own free replacements (GPL; unverified per file). GameSettings `.ini`: Dolphin, GPL. `codehandler.bin`: Gecko cheat handler, **licence unverified** | Yes (except unverified) | No | Keep in the player. **Consider dropping `codehandler.bin`**: cheat-code support isn't used and its provenance is unclear |
| `cartridge_shim.c`, `save_bridge.c`, `*_frontend.c.in`, `cartridge_core.T` | Your glue inside and beside the cores | Yours | — | Not once linked into GPL cores | Publish them as part of the player under GPL-3.0-or-later |
| Oboe `b15f5e39` | Audio output (in LibretroDroid) | Apache-2.0 (repo) | Yes | Yes | Notice |
| libretro-common (5 files) | VFS etc. in LibretroDroid | Per-file permissive (MIT-style) notices (repo: `docs/licenses/libretro-common-file-notices.txt`) | Yes | Yes | Keep file headers |
| glslang (Vulkan SDK 1.4.363.0) | Shader translation | BSD-3-Clause plus MIT/Apache files (repo) | Yes | Yes | Notice |
| SPIRV-Cross (same tag) | Shader translation | Apache-2.0 (repo) | Yes | Yes | Notice |
| libchdr, Zstandard, LZMA SDK, dr_flac | CHD and compression in cores | BSD-3, BSD-3, public domain, MIT-0 (repo) | Yes | Yes | Notices |
| Filament, gltfio, filament-utils 1.72.1 | 3D shelf renderer | Apache-2.0 (repo) | Yes | Yes | Ship Filament's NOTICE and its bundled third-party notices (unverified list) |
| AndroidX (Compose BOM 2025.04.01, activity-compose 1.10.1, lifecycle 2.8.7, material3) | UI | Apache-2.0 (LICENSING.md) | Yes | Yes | Notices |
| Kotlin 2.1.20, kotlinx-coroutines 1.10.2 | Language/runtime | Apache-2.0 | Yes | Yes | Notices |
| Room 2.7.2 (+ KSP 2.1.20-1.0.32 at build time) | Database | Apache-2.0 | Yes | Yes | Notices |
| JUnit 4.13.2, AndroidX Test, Espresso Intents | Tests only, not shipped | EPL-1.0 for JUnit (unverified); Apache-2.0 | n/a | n/a | None |
| Gradle wrapper 8.13, AGP | Build | Apache-2.0 | n/a | n/a | None |
| Blender, cmgen, Khronos glTF Validator | Asset tools, not shipped | GPL (Blender, tool only; output is yours), Apache-2.0 | n/a | n/a | None |
| **13 case models** (`app/src/main/assets/models/*_case.glb`, `*_box.glb`) | 3D boxes | **Original**: generated by `tools/generate_cases.py`, plain shapes, no logos | Yes | Yes | None. Good asset |
| `studio_ibl.ktx` | Lighting | Original (`tools/generate_studio.py` + cmgen) | Yes | Yes | None |
| `res/raw/media_click.wav` | UI sound | Original (`tools/generate_sound.py`) | Yes | Yes | None |
| `res/drawable/ic_cartridge.xml`, `res/anim`, `res/interpolator` | Icon, transitions | Original | Yes | Yes | None |
| Fonts | — | None bundled (system font) | — | — | None |
| RetroArch GLSL shader library (`libretro/glsl-shaders`, incl. crt-royale) | Optional shaders | Mixed per-shader licences, many GPL; crt-royale GPL-2.0-or-later (unverified). **Not bundled**: the user downloads it from GitHub on demand (`shaders/GitHubShaderSource.kt`) | Fine while you don't distribute it | Fine while you don't distribute it | Keep it user-initiated. If you ever bundle a default CRT shader, pick a permissively licensed one and ship its notice |
| **Untracked `/assets/models/`** (121 MB of owner-downloaded sources) **and git history** | Not shipped today | Sketchfab downloads with unrecorded licences; one archive is named as ripped from a commercial mobile game (`assets/models/segagenesis/source/…`); one is a scan of a commercial cartridge. `genesis_cartridge.glb`, `snes_us_cartridge.glb` and other branded GLBs are **still in git history** (commits `9057144`, `015959e`, …) | **No** | — | Never publish this repository's history. Before any source release, export the closed frontend to a fresh repo. Keep `/assets/models` out of any backup you share |
| ScreenScraper API and its images (runtime) | Box art and metadata | Service terms: per `docs/LICENSING.md`, ordinary API use is **limited to completely free apps unless an agreement is obtained**. Box art is the publishers' copyright | **Not without an agreement** | — | Contact ScreenScraper before charging, or make ScreenScraper strictly bring-your-own-account (§8.3) |
| RetroBase Collection API (runtime) | Alternative metadata | Data licence "pending", **no image licence** (LICENSING.md) | Unclear | — | Ask before charging |

### 1.3 Non-commercial components: summary

- **Snes9x** (SNES) and **Genesis Plus GX** (Genesis/Master System/Game Gear) are the only non-commercial
  components in the build. Both must go before **any** build reaches Patreon, paid or free.
- Old MAME, PicoDrive and Yabasanshiro are **not present**. Keep them out.
- If you add N64 later: Mupen64Plus-Next is GPL-2.0, but check each RDP plugin's licence separately. Some
  community RDP code has non-commercial terms (unverified). parallel-RDP also needs Vulkan, which this GL ES host
  doesn't have.
- Swapping cores invalidates save *states* for those systems. They're keyed by `Core.revision` in
  `domain/GameSystem.kt`. Battery saves (`.srm`) usually carry over between SNES cores, but test that.

---

## 2. Japan legal-risk audit of the code

Background, as you described it: an emulator is not illegal in itself in Japan; risk comes from **how** it is
used and supplied. The relevant laws:

- **不正競争防止法**: supplying devices, programs, codes or services that defeat 技術的制限手段. The
  2018 amendment extended this to services and codes.
- **著作権法**:
  - 技術的保護手段 and 技術的利用制限手段 circumvention;
  - private copying that circumvents protection isn't covered by Art. 30;
  - the 2020 リーチサイト・リーチアプリ rules.

Have the article numbers confirmed by a professional. They have moved between amendments.

### 2.1 Technical protection measures

| Finding | Where | Risk | Recommendation |
|---|---|---|---|
| **Dolphin compiles in Wii decryption keys** (retail common key, "new" common key, SD key, and the dev common key) as byte arrays | Pinned source `Source/Core/Core/IOS/IOSC.cpp` ≈ lines 579–623 (checkout under `.tools/upstream/feasibility/dolphin`); also used by `DiscIO/VolumeWii`, `IOS/ES/*`. Built into `libdolphin_libretro.so` | **Highest in this repo.** The app offers GameCube only, but you would be *distributing* a binary that contains console keys whose purpose is defeating Wii encryption. That is close to what the 2018 UCPA amendment on 指令符号 targets | Before distributing, either (a) build Dolphin with the Wii IOS/disc-decryption paths and key tables removed (this needs an engine patch, which conflicts with the AGENTS.md rule "Never edit an engine's source", so record an explicit exception), or (b) leave GameCube out of the first commercial release. Ask the lawyer (§5.5) |
| **Flycast compiles NAOMI/Atomiswave arcade decryption** (M1/M2/M4 cartridge and GD-ROM DIMM decryption) | Pinned source `core/hw/naomi/decrypt.cpp`, `m1cartridge.cpp`, `m4cartridge.cpp`, `gdcartridge.cpp`; always built (`core/hw/CMakeLists.txt:13 add_subdirectory(naomi)`) | Medium. The app never offers arcade content, but the code ships | Prefer a build without NAOMI/Atomiswave (engine patch or upstream build flag, if one exists; unverified). At minimum make sure the importer rejects arcade sets |
| **PS1 LibCrypt subchannel (`.sbi`) support** | `app/src/main/java/dev/cartridge/storage/SbiFile.kt`, `storage/DiscImporter.kt:96–99`; consumed by PCSX ReARMed | Low to medium. An SBI reproduces the protection data *of the user's own disc* so that a disc image behaves like the original. It emulates the protection rather than removing it, but it is protection-adjacent | Keep it optional and silent: no UI that explains LibCrypt, no links to SBI collections. Ask the lawyer |
| ARMSX2 emulates the PS2 drive's sector scrambling | Pinned source `pcsx2/CDVD/CDVD.cpp:1116 mechaDecryptBytes` (XOR/shift with values the emulated drive computes at run time). MagicGate isn't implemented: the code asks for "already decrypted" files (`:2449`) | Low. This is hardware behaviour emulation that needs the user's own BIOS, not a shipped key | Mention to the lawyer; no change proposed |
| Your own code | `app/src/main/java`, `libretrodroid/src/main`: no decryption, no `javax.crypto`, no keys (grep for decrypt/Cipher/AES found nothing) | None found | — |
| How users get their dumps | Not in the code | Dumping GameCube discs or a PS2 BIOS from original hardware usually involves running homebrew through an exploit. In Japan, *that step itself* may be circumvention, even when the game is owned | **Never publish or link dumping guides**, and don't name tools. Word the in-app text as "obtained lawfully" rather than "dump it yourself" (§2.6) |

Caveat: I checked the pinned Dolphin, Flycast and ARMSX2 checkouts for decryption and key code. I didn't audit
mGBA, Snes9x, FCEUmm, Genesis Plus GX, Beetle Saturn or PCSX ReARMed line by line. They aren't known to carry
key tables, but that is unverified.

### 2.2 System software, BIOS and firmware

- **Nothing of Sony's, Sega's or Nintendo's is bundled.**
  - `.gitignore` blocks `*.bin`, `*.bios`, `*.iso`, `*.chd`, `*.cue`, ROM extensions, memory cards and
    `*.zip`.
  - The APK's assets are case models, lighting, licence texts and Dolphin's free data.
  - The test programs in `tools/probe/` are original machine code (65816, 6502, 68000, Z80, SH-2, SH-4,
    R5900, PowerPC). The PS2 "no BIOS" test uses a zero-filled stand-in with a fake file table
    (`docs/LICENSING.md`).
- **High-level replacements inside the cores** (open-source reimplementations by their projects, not copies):
  PCSX ReARMed's HLE BIOS, Flycast's HLE BIOS, Dolphin's HLE IPL path (`dolphin_skip_gc_bios = enabled`,
  `GameSystem.kt:169`), and Dolphin's free DSP ROM and font replacements. I haven't verified their clean-room
  provenance beyond the upstream projects' own statements.
- **BIOS required per system** (`app/src/main/java/dev/cartridge/domain/GameSystem.kt`):

| System | BIOS | Code |
|---|---|---|
| PS2 | **Required**: one 4 MiB dump, recognised by its file table (`Firmware.proves`) | `GameSystem.kt:181–184` |
| Saturn | **Required**: `mpr-17933.bin` (US/EU) or `sega_101.bin` (JP), MD5-checked | `:147–151` |
| PS1 | Optional (the built-in HLE starts some games). Accepts listed retail dumps by MD5 | `:134–139` |
| Dreamcast | Optional (`dc_boot.bin`; HLE starts most games) | `:157–159` |
| GameCube | None (IPL skipped) | `:169` |
| GB/GBC/GBA, NES, SNES, Genesis/SMS/GG | None | — |

- **Never downloaded or linked.** Firmware is imported only through SAF (`storage/FirmwareStore.kt`), kept in
  private storage and excluded from backups. The setup page says "Cartridge never downloads console files.
  Choose the one you own; it stays on this device and is left out of backups." (`shell/ShellPages.kt:296`).
  That's good.
- **One wording risk.** The PS1 `firmwareAccepts` text (`GameSystem.kt:139`) lists model numbers and
  "PSXONPSP660", a BIOS extracted from PSP firmware. Model numbers are harmless, but the PSP name points at a
  specific extraction route. Keep accepting it by hash and remove the name from user-facing text.

### 2.3 "Reach app" risk (リーチアプリ)

- **URLs in shipped code** are only gnu.org (licence texts), the ScreenScraper and RetroBase APIs, and GitHub
  (shader library). There are **no ROM, ISO or BIOS sites** in `app/`, `libretrodroid/`, `README.md`,
  `AGENTS.md`, `docs/` or `tools/`. Docs only link upstream source, competitor pages (`depmots.com/socket`,
  `es-de.org`) and developer docs.
- **Search** only searches your own library (`Destination` search) and installed apps. There is no catalogue
  browser and no "find ROMs" feature.
- **Store text, Patreon text and Japanese strings** don't exist yet. All strings are in English, in code
  (`docs/ux/IMPLEMENTATION_STATUS.md`, "Localisation").
- **Gray area: ScreenScraper box art.** It isn't the pirated-work linking that the reach-app rules target, but
  the images are publishers' copyright, fetched by your app. Keep scraping user-initiated, never ship or show
  scraped art in marketing, and get ScreenScraper's commercial terms in writing.

### 2.4 Bundled games

- **No commercial ROMs, ISOs or test games** are in the repository or the APK.
- Test content is all original. Programs are generated by `tools/probe/gba_probe.S`, `gba_sram_probe.S` and
  `generate_{console,dreamcast,gamecube,gb,ps1,ps2,saturn}_probe.py`. Fixture games with fictional titles and
  artwork ("Orbit Field", "Aster Pilot", "Studio Meridian") come from `app/src/debug/.../FixtureTreeProvider.kt`
  and are used in tests and in `docs/ux/screenshots/`.
- `RealGameCheck` (androidTest) runs your own commercial games **on the device**. They aren't in the repo, and
  the docs deliberately never name them. Keep it that way, and keep its logs and screenshots out of Git.

### 2.5 Trademarks and copyrighted assets

| Item | Where | Assessment |
|---|---|---|
| App name "Cartridge", package `dev.cartridge` | `app/build.gradle.kts` | A generic word, not a console mark. **Not cleared.** Search J-PlatPat (classes 9 and 41), USPTO, EUIPO and the Play Store before you build a brand on it. Also note the app now shows *boxes*, not cartridges |
| Console names as UI text ("Game Boy Advance", "PlayStation 2", "Super Famicom"…) | `GameSystem.kt`, shelf headers, settings, case file names (`famicom_box.glb`, `gamecube_case.glb`, `saturn_case.glb`) | Descriptive use to say what a file is for is common and generally treated as acceptable, but **keep these names out of the app name, icon, store title and Patreon title**. File names are internal and fine |
| "RetroArch shaders" header and "Downloads RetroArch's GLSL shader library" | `shell/ShellPages.kt:558–559, 601` | RetroArch is libretro's mark. Reword to "libretro GLSL shader collection (GitHub)" |
| 3D case shapes | `tools/generate_cases.py` | Original plain shapes with no logos or printed marks. Low risk |
| Branded 3D models (Sketchfab, a commercial game rip, a cartridge scan with a moulded Nintendo name) | Git history and untracked `/assets/models/` | Not in the APK since ADR 0029. **Must never be distributed.** See §1.2 |
| Official box art | Runtime ScreenScraper cache only (private storage, never committed) | OK in the app. **Never in screenshots, store listings, Patreon posts or trailers** |
| Logos and console imagery | None in `res/` or assets | Good |
| Missing "not affiliated" disclaimer | No such text anywhere (grep found none) | Add it (§2.6) |

### 2.6 Changes to stay on the safe side, and suggested wording

**Code and build changes before any distribution**

1. Remove Snes9x and Genesis Plus GX (§1.3).
2. Remove Dolphin's Wii key tables and decryption paths from the shipped binary, or hold GameCube back (§2.1).
3. Remove Flycast's NAOMI/Atomiswave decryption from the shipped binary if possible (§2.1).
4. Remove "PSXONPSP660" from user text, and keep firmware MD5s out of the UI (§2.2).
5. Reword "RetroArch" in the shader settings (§2.5).
6. Add a first-run notice and an About › Legal page with the wording below. The setup page text in
   `ShellPages.kt:296` is already close.
7. Make sure no scraped image can ever be bundled, exported in a shareable "pack" by default, or uploaded.
   Today packs and backups are private and user-initiated. Keep it that way.
8. Commercial distribution means publishing GPL source. Publish it from a **fresh** repository, never this
   history (§1.2).

**Suggested in-app wording** (have it reviewed)

- *English, first run and About:* "Cartridge doesn't include any games or console system software (BIOS). Use
  only games and system files you have obtained lawfully from discs, cartridges and consoles you own."
- *Japanese:* 「Cartridge にはゲームソフトやゲーム機のシステムソフトウェア（BIOS）は含まれていません。ご自身が正当に所有するソフトおよび本体から、法令の範囲内で用意したデータのみをご利用ください。」
- *Trademark disclaimer (EN):* "All product names and trademarks belong to their respective owners. Cartridge
  isn't affiliated with or endorsed by any console maker."
- *Trademark disclaimer (JA):* 「記載の製品名・商標は各社の商標または登録商標です。Cartridge はいずれのゲーム機メーカーとも関係がなく、承認・推奨を受けたものではありません。」
- *BIOS page (EN):* "This console needs its system file. Cartridge never downloads, includes or links to
  system files."

The wording deliberately avoids "dump it yourself" and "back up your games", which in Japan can read as
instructions to perform a possibly unlawful step (§2.1, last row).

---

## 3. Employer and IP-ownership preparation

### 3.1 Git history

- **Author and committer of all 42 commits:** `マティス <faes.matisse@grev.co.jp>`. It comes from your
  **global** git config (`C:/Users/matisse/.gitconfig`). There are no other identities.
  - **What you've confirmed (2026-10-20):** this is your personal computer. The work address is a global git
    setting left over from remote work and never changed. All work on the project was done in your free time
    or on days off, never during working hours.
  - It's harmless in substance, but anyone reviewing ownership sees the work address first. So fix it going
    forward and explain it, rather than leaving someone else to discover it.
- **Remote:** none configured. Branches: `master`, `redesign/console-experience`. The repo hasn't been pushed
  anywhere, which is good.
- **AI assistance account:** the Claude account email is a personal address (gmail.com). Confirm that the
  subscription is paid personally, not from a company account or company card.

**When commits were made** (all in JST, +0900). Japan's Sports Day 2026 falls on Monday 12 October, so 9
October was a normal working Friday.

| Window | Commits | Detail |
|---|---|---|
| Weekday 09:00–18:00 | **7** | All on **Fri 2026-10-09, 15:27–17:52**. Three share the exact timestamp 15:27:58, which looks like an import of earlier work, so the work itself may be older |
| Weekday evenings (after 18:00) | 12 | Fri 10-09 19:33–22:57; Fri 10-16 19:52–23:48 |
| Late night / weekend | 23 | Sat 10-10 (00:46–22:45), Sun 10-11, Sat 10-17 (00:44–14:36), Sun 10-18 |

**Commit times don't show when the work was done.**

- The ADRs and docs record substantial work dated **Tue–Thu 13–15 October** (the PS2 engine change, ADR 0026)
  and **Tue 20 October** (the uncommitted multi-disc work). Those landed in large evening commits
  (`9057144` on Fri 10-16 19:52) or aren't committed yet.
- You've said this work was done on days off. That's all an employer can reasonably ask, but a weekday
  timestamp is the kind of detail that gets questioned. Match those dates to your leave records now (the
  9 Oct afternoon commits; 13–15 and 20 Oct in the docs) and keep the list.

**Recommendations**

- Change the global identity to a personal address (`git config --global user.email <personal>`). This is your
  personal machine, so there's no reason to keep a work address anywhere in its git config.
- **Don't rewrite history to hide the old address.** If it later came out, it would look worse than the
  address itself. Mention it in your disclosure: "my personal PC's git config still had the work email from
  when I worked remotely; no company equipment or time was used."
- Keep doing this project only outside working hours, on personal hardware and personal accounts, and keep a
  simple log (date, hours, machine).

### 3.2 Code or assets that might look like employer material

- I found **no** company names, "confidential" or 社外秘 markers, internal SDKs, game-engine code or
  unexplained proprietary libraries. `git grep` for confidentiality markers and the domain name found only
  licence boilerplate.
- Every dependency is public and pinned to a public upstream: AndroidX, Filament, LibretroDroid, the cores,
  Khronos tools and Blender.
- The 3D pipeline (Blender scripts in `tools/`, Filament, glTF) and the native build approach could look like
  "game-industry know-how". Be ready to say they come from public documentation, not your employer's
  pipelines, if that's true.
- The code was written with substantial AI assistance (Claude Code; `AGENTS.md` records your direction). That
  isn't an employer issue in itself, but:
  - (a) confirm no company AI seat was used, and
  - (b) note that under the Agency for Cultural Affairs' 2024 guidance, copyright in AI-assisted output depends
    on human creative contribution. Ask the lawyer whether that changes anything about enforcing rights in your
    closed code.

### 3.3 Checklist for your 雇用契約書 and 就業規則

Look for the exact article numbers, and copy the text.

1. **副業・兼業**: Is it banned, allowed with permission (許可制) or allowed with notification (届出制)? What
   form, who approves, how long it takes, and whether approval can be revoked.
2. **競業避止**: during employment and after leaving. Does "similar business" include game software,
   game-related tools or "anything competing with the company's business partners"?
3. **利益相反・会社の信用 (conflict of interest / reputation)**: clauses forbidding activities that "damage the
   company's reputation" or relationships with business partners. **An emulator is the most likely target
   here**, especially if your company works with Nintendo, Sony or Sega.
4. **職務著作 / 著作権の帰属**: Does the contract assign works "related to the company's business" to the
   company, even if made off-hours? Under Copyright Act Art. 15(2), a *program* made on the employer's
   initiative in the course of duties belongs to the employer, and programs don't need the "published under the
   company's name" condition. A clause that tries to reach *private* work is worth asking a lawyer about.
5. **職務発明** (Patent Act Art. 35) and any invention-disclosure duty.
6. **秘密保持**: confidential information, and whether "know-how" is defined broadly.
7. **会社資産の使用**: rules on company PCs, email, accounts, software licences and networks for personal use.
   This connects to §3.1.
8. **SNS・対外発信**: rules on public posting, open-source contributions, speaking or publishing under your
   name, or being identifiable as an employee.
9. **退職後の義務**: post-employment non-compete or IP duties.
10. **懲戒事由**: what breaking the 副業 rules leads to.

### 3.4 How to present the project to your employer (outline)

Keep it short, factual and in writing. A one-page 副業申請 attachment:

- **What it is:** a personal Android app that organises a user's own game collection and plays it with
  open-source emulation components. A game-library frontend with a built-in player.
- **What it is not:**
  - It contains no games, no console system software and no copy-protection circumvention. If you take the
    Dolphin/Flycast actions in §2.1, you can say this plainly.
  - It doesn't link to, search for or help obtain game files. Users must supply their own lawfully obtained
    copies.
  - It isn't related to the company's titles, platforms, clients or technology.
- **How it was made:**
  - In personal time, on personal equipment, with personal accounts.
  - It contains no company code, assets, tools, SDKs or confidential information.
  - It's built from public open-source components, listed in its licence notices.
  - Explain the git email: a setting left over from remote work on your personal PC (§3.1).
- **What you're asking for:** permission for a small personal side activity (Patreon supporter builds, maybe
  Google Play sales), estimated hours per week, no use of company time or resources, and an offer to keep the
  company's name out of all of it.
- **What you'll do if they're uncomfortable:** for example, keep it free and unmonetised, or stop. Deciding
  this in advance makes the conversation easier.

Expect the question "Will our partners mind?". Being able to say "no circumvention, no BIOS, no ROM links, no
console trademarks in branding" (§2.6) is the strongest answer you have.

---

## 4. Visa and tax preparation

> **No income of any kind (Patreon, Play Store, Ko-fi, "donations", sponsorship) should be accepted until a
> 行政書士 (or the immigration bureau) has confirmed your position, 資格外活動許可 has been granted if needed,
> and your employer has consented.** This section is preparation material for those conversations. It is not
> legal, immigration or tax advice.

### 4.1 Plain description to bring to the 行政書士

*English:*
"I am a software engineer employed full-time in Japan (status of residence: Engineer/Specialist in
Humanities/International Services, valid until 2028). In my own time I have developed an Android application
that organises a user's own video-game library and plays the user's own game files with built-in open-source
emulation software. The app contains no games or console system software, and users must supply files they own.
I would like to earn income from it by (1) offering supporter memberships on Patreon (a US platform), where
members receive early test versions, and later possibly (2) selling the app on Google Play. I would work on it
alone, outside my working hours, with no employees and no company. I expect income to start small (estimated
under ¥___ per month in the first year)."

*日本語:*
「私は日本で正社員のソフトウェアエンジニアとして勤務しています（在留資格：技術・人文知識・国際業務、2028年まで有効）。私的な時間に、ユーザーが所有するゲームのライブラリを整理し、内蔵のオープンソースのエミュレーションソフトでユーザー自身のゲームデータを再生する Android アプリを個人で開発しました。アプリにはゲームやゲーム機のシステムソフトは含まれず、ユーザーは自分で所有するデータを用意する必要があります。このアプリで (1) 米国の Patreon で支援者向けメンバーシップ（テスト版の先行提供）、将来的には (2) Google Play での有料販売、による収入を得たいと考えています。従業員や法人はなく、勤務時間外に一人で行います。当初の収入は小さい見込みです（初年度は月 ___ 円未満を想定）。」

Fill in the blank with a realistic number from §8.6.

### 4.2 Questions for the 行政書士

1. Is selling my own app (Patreon memberships, paid Google Play app) outside the activities of 技術・人文知識・国際業務, so that I need 資格外活動許可? Is it 包括 or 個別 permission for someone with my status?
2. Does it matter that the income comes from foreign platforms (Patreon in the US, Google in Ireland or the US) and from customers mostly outside Japan?
3. Do free tiers, tips or donations (no reward), or a free Patreon tier count as "receiving remuneration"? Can I run a free Patreon or Discord with no paid tier before permission?
4. Before permission arrives, may I **set up** accounts (Patreon page in draft, Play Console developer account, payment profile) if no money moves?
5. Does the application need my employer's written consent, or a copy of the 副業 approval?
6. What documents should I prepare (description, business plan, expected income, hours per week)?
7. How long does permission take, and what are common reasons for refusal?
8. How will this side income affect my **2028 renewal** (or a future change to permanent residence)? What records should I keep (確定申告 copies, 課税証明, 納税証明)?
9. What happens if income grows a lot (for example, more than my salary)? At what point would the immigration bureau see the side activity as my main activity, or expect a different status (e.g. 経営・管理)?
10. If I later set up a company (合同会社) to hold the app, what does that change for my status?
11. Is selling on Google Play or Patreon to Japanese customers subject to **特定商取引法** seller-information display (name, address, phone)? Can I use a business or virtual address?
12. Does the nature of the app (an emulator) affect how the application is assessed?
13. If my employment ends or I change jobs, what happens to the permission?

### 4.3 Questions for the 税理士

1. As a salaried employee, do I have to file 確定申告 if side income is **≤ ¥200,000** a year? (My understanding is that income tax may not require it but 住民税 still requires a declaration. Please confirm.)
2. Is the income 雑所得 (業務に係る) or 事業所得? What bookkeeping makes 事業所得 (and 青色申告) defensible? Should I file 開業届 and 青色申告承認申請書?
3. **Patreon (USD):** at what point is income recognised (when charged, or when paid out)? Which exchange rate (TTM on which day, or a monthly average)? Are platform and processing fees deductible? Is any US withholding involved (W-8BEN under the Japan–US treaty)?
4. **Google Play:** how should payouts be recorded? Who is the seller for consumption-tax purposes, and does the **platform taxation rule (from April 2025)** affect me as a domestic developer?
5. **Consumption tax:** confirm the ¥10 million taxable-sales threshold, how the base period and 特定期間 apply, and whether I need to think about the インボイス制度 for B2C sales through platforms.
6. **Deductible expenses:** test devices (handhelds, phones), the Google Play registration fee, domain and hosting, ScreenScraper or other API fees, AI coding subscriptions, a share of PC, internet and rent (家事按分), and depreciation (and 少額減価償却 rules if I'm 青色).
7. 電子帳簿保存法: what must I keep for electronic transactions (Patreon statements, Google payout reports)?
8. **住民税 collection:** what are the implications of 普通徴収 versus 特別徴収 for side income? (I intend to have my employer's consent, so this is about understanding, not hiding.)
9. Is 個人事業税 a consideration for software sales if income exceeds the threshold?
10. Is foreign tax credit relevant if any platform withholds tax?
11. What's the simplest record-keeping setup (spreadsheet or app) for a small foreign-currency side income?

---

## 5. Licensing architecture recommendation

### 5.1 The two options for this codebase

| | **Option 1: closed frontend + GPL "player" process, one APK** | **Option 2: closed app, cores downloaded at runtime, plus external emulators** |
|---|---|---|
| Fixes the LibretroDroid (GPL host) problem? | **Yes.** The host moves into the player, which is GPL | **No.** The host is LibretroDroid itself. You'd have to write your own closed libretro host (video and HW-render context, audio, input, environment, disc control, shader pipeline): a large rewrite of working code |
| Cores | Built and shipped as today, inside the GPL player. Source published | Downloaded by the user. **Google Play policy forbids apps from downloading executable code (including `.so`) from outside Google Play.** On Play you'd have to ship them through Play Feature Delivery, which is distributing them again. Patreon sideloads could download them, but loading GPL plugins into a closed host is exactly the case the FSF calls a combined work |
| Your shim inside each core | Stays; published as GPL | Must go, or be published anyway |
| "Unified console" experience | Kept, with careful transitions (§5.3) | Weakened: download step, and external emulators reintroduce app switching (the thing you differentiate against) |
| Japanese legal view | Neutral. You publish GPL emulator source (the player). The circumvention fixes in §2.1 apply either way | Neutral to slightly worse. "Downloads emulators" plus launching third-party emulators (some of which have their own legal issues) blurs your "clean" story |
| Employer view | You'd have a public open-source emulator repo, under a handle (§8.5). Explain it up front | You'd look less like an emulator author, but you'd still distribute or point to GPL emulators, and the app is still an emulator frontend |
| Effort | **L**, but mostly moving code that works (§5.2) | **XL** for a closed host. **M** for external-launch support |
| Recommendation | **Choose this** | Don't, except perhaps *adding* external-emulator launch later as an extra (for systems you don't embed, such as N64, PSP and DS), never as the licensing fix |

Whatever you choose, **any** distribution of the GPL cores requires you to offer their complete corresponding
source, including your shim and build scripts. That isn't avoidable.

### 5.2 What moves, what stays, and the boundary

**Into the GPL player** (`:player` process, its own Activity, a separate Gradle module published as source):

| Area | Files today |
|---|---|
| Host | `libretrodroid/` (whole module) |
| Cores and native glue | `app/src/main/cpp/*` (all core recipes, `cartridge_shim.c`, `save_bridge.c`, frontend wrappers) |
| Session engine | `emulation/LibretroEngine.kt`, `EmulationEngine.kt`, `SaveBridge.kt` |
| Saves | `storage/StateSlots.kt`, `storage/SessionStore.kt`, `storage/SaveStore.kt`, `storage/CoreData.kt` (unpacking core data), disc control parts of `storage/DiscCards.kt` |
| Core options | `environment/GameOptions.kt` (core variables) |
| Shaders | `shaders/*` (`GlslCompat`, `PresetReader`, `ShaderLibrary`, `ShaderChoices`). The *download* (`GitHubShaderSource`, `ShaderLibraryUpdater`) can stay in either half |
| Gameplay input | `input/ControllerRouter.kt` (gameplay half), `input/ButtonCombo.kt`, `domain/Pad.kt` |
| In-game overlays | `ui/GameBadge.kt` (freeze and fast-forward badge), the **Pause destination** of `shell/ConsoleShell.kt` and `ShellPages.kt` (Resume, Quick Save/Load, Save States, Game Options, Change Disc, Return to Library) |
| Shared UI | A **minimal** copy of the `ui/` tokens and components the pause menu needs, so it looks identical. These become GPL; keep them small |

**Stays closed** (frontend, main process): `rendering/*` (Filament stage, labels), `shell/*` except Pause,
`library/*` (Room), `storage/DirectoryScanner`, `DiscImporter`, `RomImporter`, `FirmwareStore`, `BackupStore`,
`LabelStore`, `scraping/*`, `apps/*`, `ui/*` (library chrome, Apps page, overlays), `session/CollectionCoordinator`
(minus the native session), `MainActivity` (minus `GLRetroView`).

**The IPC boundary.** Explicit Intents plus files, which counts as arm's-length communication. Avoid sharing
internal object graphs.

- **Launch** (frontend → player, an explicit Intent to `PlayerActivity` in the same APK):
  - `gameId`;
  - content path or playlist path (`files/playlists/<id>.m3u`) and initial disc;
  - `Core` id and revision;
  - system and saves directories;
  - start mode (fresh / quick save / chosen state / checkpoint);
  - merged core options (console + game);
  - shader preset path and parameters;
  - button map and combos;
  - fast-forward speed;
  - display prefs (fit, transition style);
  - accent colour and locale (so the pause menu matches);
  - a session token.
- **Result** (player → frontend, Activity result plus a small JSON journal in a shared private directory, so a
  killed frontend can still read it on restart):
  - play time (only Running time, as today);
  - saves written (state ids, thumbnails);
  - checkpoint written;
  - current disc;
  - notices (missing BIOS, shader refused);
  - exit reason.
- **Settings** travel *in the Intent* as a snapshot. **Don't share `SharedPreferences` or the Room database
  across processes.** Neither is multi-process safe, and the repo's ordering rules (AGENTS.md: "after any
  suspending operation, read current state…") rely on a single writer. The frontend stays the only Room writer
  and applies the player's journal.
- **Return to frontend:** "Return to Library" finishes the player activity. The frontend resumes, applies the
  journal and reopens the curtain on the focused box.
- **Lifecycle rules to carry over:**
  - `keptWhileAway` (PS2 and GameCube stay loaded and paused);
  - checkpoints on sleep;
  - releasing self-drawing engines on their own thread (`MainActivity.endSession` logic moves to
    `PlayerActivity`);
  - "exactly one native session" becomes "exactly one player process".

**Effort estimate:** **L.** Roughly 4–8 weekends of focused work plus a full device regression (all
`Test-Device.ps1` suites and `RealGameCheck` on DC, PS2 and GC). The riskiest parts are lifecycle and sleep
behaviour across two processes and the pause menu's look. The cores and host move without changes. This is an
estimate from reading the code, not from doing it.

### 5.3 Keeping it seamless across the boundary

- **No app-switch animation.** `PlayerActivity` runs in the same task (same `taskAffinity`, not a new task).
  Give it a theme with `windowAnimationStyle = @null`, `windowDisablePreview = true`, a black window
  background and `windowNoTitle`. Launch it with `ActivityOptions.makeCustomAnimation(ctx, 0, 0)`, and use
  `overrideActivityTransition` (API 34+) or `overridePendingTransition(0, 0)` when finishing. The user then sees
  your own curtain, not Android's transition.
- **Use the curtain you already have** (ADR 0020: Fade/Iris/Shutter, "open only on a game that shows a
  picture"):
  - The frontend closes the curtain and releases its Filament surface (the existing rule).
  - It then starts the player.
  - The player draws black until the first *real* core frame (`EngineStatus` readiness already waits for it),
    then opens the curtain itself.
  - On return, the player captures its PixelCopy pause frame to a file, closes to black and finishes. The
    frontend opens on the drawn library.
- **Cross-fade from box to first frame.** Before launching, the frontend renders the last frame of the box
  animation to a bitmap file. The player shows that bitmap as its first window content and cross-fades to the
  first core frame. Visually the box "becomes" the game, with no shared GPU context, which respects the
  AGENTS.md rule against sharing surfaces.
- **Pre-warm the player.** When focus settles on a game (after ~300 ms) or when the library opens, bind a
  lightweight `PlayerService` in `:player`. It loads `liblibretrodroid.so`, dlopens the likely core, unpacks
  core data and, for small cartridges, pre-reads the ROM. Unbind when the app goes to the background.
  - This hides process start (typically a few hundred ms on mid-range hardware; measure it) and most of the
    core-load time.
  - The trade-off is memory, so pre-warm only the focused system's core.
- **Shared settings.** The frontend owns them and sends a snapshot per launch. If the player changes a game
  option from the pause menu, it writes it into the result journal and the frontend persists it.
- **HOME mode and the Apps page** stay frontend-only. The player never appears in the launcher.

### 5.4 One APK or two?

One APK with two processes is the better experience, and the GPL FAQ treats separate programs that communicate
by command-line-style parameters and files as an *aggregate*. But "same APK, launched by Intent, styled
identically" is closer to the line than two apps. **Fallback if your lawyer is uncomfortable:** publish the
player as its own free, open-source app (GitHub, and Play or F-Droid), and have the frontend launch it by Intent
with the same transitions. ES-DE plus RetroArch work this way. It costs one extra install step.

### 5.5 Questions for an open-source-savvy lawyer

1. Is a closed frontend plus a GPL-3.0 player in **one APK**, communicating only by Intents, Activity results
   and files, "mere aggregation"? Or does it make the frontend a work based on the GPL program? Does styling the
   player's pause menu identically change the answer?
2. With GPLv3 "Installation Information" and Play App Signing, can a user replace the player inside a signed
   APK? Do I need to provide a way to build and sign a modified APK, or ship the player as a separate app?
3. The repo combines GPL-2.0-*only* parts (check Beetle Saturn and FCEUmm headers) with GPL-3.0 LibretroDroid
   in one process. Is that a licence incompatibility in the player? RetroArch's practice treats cores as
   separate. Is that defensible?
4. Distributing Dolphin with embedded Wii keys, or Flycast with NAOMI decryption, from Japan: does removing
   them (by patching the engine) satisfy 不正競争防止法 and 著作権法? Is removal *required*?
5. Is SBI (LibCrypt subchannel data) support a problem under Japanese law?
6. ScreenScraper and RetroBase terms for a paid app, and showing publishers' box art in a paid app. Does an
   app whose users download box-art scans from a community database fall under Japan's 2021 rule against
   downloading unlawfully uploaded copies (侵害コンテンツのダウンロード違法化), for the users or for me as the
   tool's supplier? Do its exceptions (minor portions, no unreasonable harm to the rights holder) apply?
7. Can I keep the frontend closed if I add RetroAchievements, given its rules for emulator integrations?
8. If I publish the player source under a handle, what are the copyright-notice requirements (real name or
   handle)?
9. AI-assisted code: does it affect my ability to enforce copyright in the closed frontend in Japan, the US or
   the EU?
10. Trademark use of console names in the app UI and in the store *description* (not the title). Do I need a
    "compatible with" formulation?
11. Should a Japan resident restrict distribution in Japan (Play country targeting) while the legal position
    is unsettled, or does that change nothing because the supply happens from Japan?

---

## 6. Selling points

### 6.1 Strongest points versus the competitors (all implemented)

1. **Built-in emulation for 13 consoles, including Dreamcast, GameCube and PS2, inside one app.**
   - Socket, DigiBitPlay and ES-DE launch *external* emulators. Lemuroid and RetroArch embed cores but have
     utilitarian UIs and no PS2 or GameCube in Lemuroid.
   - No app switching, no RetroArch menus, one pause menu everywhere.
   - Devices tried so far: only the RP5.
2. **A real console lifecycle**:
   - the device can sleep mid-game and the game resumes from a checkpoint (`CollectionCoordinator.keptWhileAway`);
   - process-death, reboot and cold-start recovery (M6);
   - battery saves restored before the first frame;
   - failed writes kept visible and old saves kept intact.

   External-emulator frontends can't own this.
3. **Save management**:
   - a quick save that keeps the previous one;
   - a list of saves with thumbnails, plus overwrite and delete (ADR 0028);
   - quick combos;
   - freeze and fast-forward with an on-screen badge (ADR 0031);
   - start a game from a chosen save;
   - per-game and per-console options;
   - remappable game buttons per console (ADR 0030).
4. **Polished 3D shelf**:
   - real-proportion retail boxes and cases per console *and region* (US/EU/JP shapes, ADR 0029);
   - automatic cover art;
   - horizontal, vertical and grid layouts;
   - right stick turns the box;
   - 60 fps measured with 3,000 games on the RP5 (ADR 0020);
   - curtain transitions with no loading text.
5. **Multi-disc games done properly**: one shelf entry, one shared memory card, Change Disc on PS1 (ADR 0032).
6. **libretro GLSL shader presets** (e.g. CRT), per console or per game, validated before saving and never able
   to stop a game (ADR 0027).
7. **Console-like device integration**: an optional HOME launcher mode and an Apps page on B (ADR 0021), using
   no broad permissions.
8. **Privacy and safety by design**:
   - SAF only (no "all files" permission);
   - no analytics;
   - credentials kept in private preferences;
   - firmware never downloaded or backed up;
   - backups with Undo, hidden games and exclusions that survive rescans.
9. **Quality engineering you can talk about**: pinned, hash-verified sources, device regression suites and a
   documented testing record. These support a "stable, not janky" message.

### 6.2 Pitch, store description and Patreon intro (trademark- and piracy-safe)

**One-sentence pitch:** "Cartridge turns your Android handheld into a single, quiet console: your collection on
a 3D shelf, and every game playing right there, with no app switching and no emulator menus."

**Short store description (≈80 words):**
"Cartridge is a game-library player for Android handhelds and controllers. Browse your own collection as 3D
boxes, press A, and play inside the same app: suspend, save and resume like a real console. Built-in players
cover thirteen classic systems, from 8-bit to 128-bit. Shaders, per-game options, multi-disc games, backups and
a home-screen mode. No ads, no tracking. Cartridge includes no games or system software; use only games and
system files you obtained lawfully."

**Patreon intro:**
"I'm building Cartridge, a retro game library that feels like a console instead of a toolkit. Your collection
sits on a 3D shelf. Pick a game and it plays right there, with one pause menu, real suspend and resume, save
states with pictures, and careful defaults. Supporters get the newest builds first, vote on what comes next and
get their name in the app. Cartridge never includes, finds or links to games or system files. Bring your own,
obtained lawfully."

Avoid in all marketing: console names in titles and headlines, "ROMs", "ISOs", "play any game", "free games",
"backup your games" tutorials, BIOS file names, and screenshots of commercial games or box art.

### 6.3 Where you're weaker (be honest about these in public)

- **Device coverage:** every result is from one Snapdragon 865 device. Mali, PowerVR, Dimensity and older
  Adreno drivers are untested, and PS2, GameCube, Dreamcast and Saturn are the risky ones.
- **Compatibility evidence:** six commercial games tried on DC, PS2 and GC together (`docs/CONSOLES.md`), and
  one on Saturn (yours, being played now; not yet recorded in the docs).
- **No touch controls, landscape only, English only.** Unusable on a phone without a controller. Socket and
  DigiBitPlay launch emulators that bring their own touch overlays.
- **Breadth:** no N64, PSP, DS, 3DS, Wii, Switch or arcade. Launch-anything frontends (ES-DE, Daijishō,
  Socket) cover far more systems.
- **No PS2 or GameCube save states**, no rewind, no RetroAchievements, no cloud sync, no themes.
- **Every game is copied into private storage** (ADR 0003/0017). A 4 GB PS2 image uses 4 GB twice, and an
  uninstall deletes the copies and, unless backed up, the saves.
- **Artwork setup:** ScreenScraper needs a user login *and* a developer ID and password typed on the device.
  That's fine for you, but a non-starter for customers (§8.3).
- **No cartridges or discs in 3D** (Socket's signature look). You chose boxes, which is a style difference, not
  a gap, but Socket owns the "physical media" image.
- **Bus factor and support load:** one developer with a full-time job, 13 systems and many devices.

---

## 7. Features to stand out

Effort: **S** = a few evenings, **M** = 1–3 weekends, **L** = a month or more of spare time. Estimates come from
reading the current code.

| Idea | Built-in advantage | What exists | Effort |
|---|---|---|---|
| **Seamless "box becomes game" boot** | External launchers can't control the first frame | Curtain transitions and first-real-frame readiness (ADR 0020), box leaves the screen | **M** (cross-fade from a rendered box frame; §5.3) |
| **Live preview behind the focused box**: last checkpoint or quick-save picture | You own the save thumbnails | `StateSlots` thumbnails, PixelCopy pause frames | **S–M** (image). Short recorded clips: **L** |
| **"Memory card" shelf**: a game's saves as cards with thumbnails, dates and play time | You own saves and play time | Save list with thumbnails (ADR 0028), `playMs` per game, Playtime sort | **M** |
| **Quick resume across several games** (Switch-style suspended games) | Needs control of the core and states | One session checkpoint (`SessionStore`) and per-game quick saves | **M** (keep N checkpoints, a "Suspended" row, memory limits; not for `Snapshots.Never` cores) |
| **Console-style Home overlay** mid-game | You own the pause menu | Resume, Quick Save/Load, Save States, Game Options, Change Disc, Return | **S** for a controls diagram. Manuals: **M** plus rights questions. Achievements: see the next row |
| **RetroAchievements** (rcheevos) in your UI | Frontends with external emulators can't | rcheevos source is already inside Flycast and ARMSX2, but not wired. The host lacks memory-map plumbing | **L**. It belongs in the GPL player. Hardcore mode conflicts with states; RA's integration rules and approval apply |
| **Run-ahead** (2D cores) | Needs per-frame state control | States are fast on small cores (`Snapshots.Always`) | **M–L** (host change in LibretroDroid) |
| **Rewind** | Same | Not offered (AGENTS.md: don't show what the adapter lacks) | **L** |
| **Device tiers and performance warnings** (PS2, GC, Saturn) | You know which engine runs | `Core` registry fields, frame-rate readout | **S–M** (GL renderer string and SoC → tier; per-console "may be slow" badge; default resolution) |
| **BIOS checker page**: one screen with each system's status, what's missing and why, no links | You verify firmware already | MD5 checks (PS1, Saturn, DC), structure proof (PS2), setup dialog | **S** (a status page from existing `Firmware` data) |
| **In-app supporter credits** | — | — | **S** (serve a static JSON you generate from Patreon; never ship a Patreon token in the APK) |
| **Finish half-done work** | — | See the next list | varies |

**Half-finished work worth completing before a paid launch** (from `docs/ux/IMPLEMENTATION_STATUS.md`,
`docs/ROADMAP.md`, `docs/CONSOLES.md`):

- **Play in place instead of copying games**: big storage win, especially for PS2 and GC. **L**
- **Touch controls** (or an explicit controller-required listing). **M–L**. Note that LibretroDroid's
  companion touch library (RadialGamePad) is GPL, unverified. It would have to live in the player.
- **PS1 analog / DualShock** (many later PS1 games need it), analog trigger travel (DC/GC). **M**
- **Change Disc on Saturn, DC, PS2 and GC** (code exists; needs device checks). **S–M**
- **PS2 save states**, and **compressed GameCube states**. **M** each
- **Memory cards in backups** (PS2, GC, DC). **S–M**
- **Japanese and English localisation** (strings are hard-coded). **M**
- Non-blocking quiet scan, sheet exit animations, aspect and integer scaling. **S–M** each

**Top 5 by impact on attracting paying supporters**

1. **Seamless boot and instant resume**: the box becomes the game, and putting the device down never loses
   your place. This is *the* clip that sells "unified console". **M**
2. **Live save-picture previews and the "memory card" shelf** with play time. Very visual and uniquely
   possible with built-in emulation. **S–M**
3. **Quick resume across several games.** A headline feature handheld owners talk about. **M**
4. **RetroAchievements.** The most requested feature in handheld communities, and a reason to stay subscribed.
   **L**, and only in the GPL player.
5. **Device tiers plus the BIOS/setup checker.** Not glamorous, but it cuts refunds, angry reviews and support
   load across many devices. **S–M**

---

## 8. Monetization and launch plan

### 8.1 Patreon tiers

| Tier | Price | What's in it |
|---|---|---|
| Free (follower) | $0 | Devlog posts and clips; access to the **older stable build** (the on-ramp, like Socket's) **once permission exists**. Free builds still have to meet every licence condition (no Snes9x or Genesis Plus GX) |
| **Player** | **$5–6/month** (¥800–900) | Current builds as they ship, supporter Discord role, name in the in-app credits (opt-in) |
| **Insider** | **$10/month** | Player, plus beta and nightly builds, roadmap votes, early feature previews |
| (optional) Patron | $20+/month | Insider, plus a highlighted credit. Keep perks cheap to deliver: no custom work, no per-person support |

Price near ES-DE ($5.50) and Socket ($6.50). Offer annual billing (a discount for 12 months), which helps
retention. Don't gate *emulation correctness* fixes behind higher tiers; gate *timing* (early access).

**Free older build:** release each stable build to the free tier 2–3 months after paid supporters get it.
Always sign with **the same key and application ID** (`dev.cartridge`). A key change forces an uninstall, and
because games and saves live in private storage, an uninstall wipes them.

### 8.2 90-day roadmap

**Phase 0: legal prep, NO income (weeks 1–4, longer if needed).** Gates, in order:

1. contract and 就業規則 review;
2. 行政書士 consultation;
3. 資格外活動許可 if required;
4. written employer consent;
5. optionally, a 税理士 and an OSS lawyer consultation.

Nothing paid goes live until gates 1–4 are clear.

*Safe during Phase 0 (no money moves):* keep building; open a **free** Discord (no paid roles); post progress
clips of the UI with the fictional fixture games and properly licensed homebrew; draft the Patreon page
privately; trademark search for the name; write the privacy policy. Engineering work:

- replace Snes9x and Genesis Plus GX (§1.3);
- the Dolphin/Flycast decryption decisions (§2.1);
- the player-process split (§5.2);
- the git identity fix (§3.1);
- a release build type and signing config (none exists in `app/build.gradle.kts`);
- device tiers and the BIOS status page.

**Phase 1: Patreon alpha (≈ days 30–60, only once Phase 0 clears).**

- Small cohort; state openly that it targets **Android handhelds with built-in controllers** (Retroid, AYN,
  Anbernic and similar).
- Builds every 1–2 weeks with a visible changelog (Socket's growth lever).
- A structured bug-report template.
- Collect device and SoC compatibility reports into a public compatibility table *of devices*, never of games
  by name.

**Phase 2: stable build (≈ days 60–90).**

- Ship a 1.0 candidate.
- Push the older build to the free tier.
- Press pitch with the seamless-boot clip.
- Start the Google Play closed test (see below).

**Phase 3: paid Play Store 1.0 (after day 90, only if the checklist in §8.3 is green).**

- Patreon continues for betas.
- A Play purchase at $9.99–14.99 (¥1,500–2,200) is reasonable. Decide whether Patreon supporters get a promo
  code.

### 8.3 Google Play readiness checklist

**Listing and content**

- [ ] Title and short description without console names or marks (e.g. "Cartridge: Retro Game Library").
- [ ] Screenshots and video **only** with fictional fixture games and original UI (the existing
  `docs/ux/screenshots` style is ideal). Avoid console-name shelf headers in hero shots; use "All games". No
  commercial box art or gameplay.
- [ ] No ROM, BIOS or ISO links or names anywhere; the disclaimers from §2.6 in the description and app.
- [ ] Rename the "RetroArch shaders" text (§2.5).
- [ ] Privacy policy URL. A **Data safety** form that discloses: file names and checksums sent to ScreenScraper
  or RetroBase when the user scrapes; ScreenScraper credentials stored on the device and sent only to
  ScreenScraper; the shader download from GitHub; no analytics.
- [ ] IARC content rating questionnaire.
- [ ] **New personal developer accounts must run a closed test with at least 12 testers for 14 days before
  production access** (policy as I last knew it; verify). Your Patreon/Discord cohort is a natural fit.
- [ ] Developer identity verification. If you sell, Google (and the EU DSA "trader" rules) may **display your
  name and address** publicly. Settle this with the 行政書士 (特商法) and decide on a business address first.
- [ ] Android's 2026–27 developer-verification programme for **sideloaded** apps also affects Patreon APK
  distribution on certified devices. Check its current rollout dates.

**Technical**

- [ ] **Target SDK:** currently 35 (`app/build.gradle.kts`). Google requires new apps to target a recent API
  level; check today's requirement, which is likely API 36 after August 2026.
- [ ] **16 KB page size:** the build already passes `-z max-page-size=16384` and flexible page sizes. Verify
  every `.so` with an alignment check, especially ARMSX2's prebuilt dependency libraries and Dolphin's
  ExternalProject output.
- [ ] Release build type, R8 or keep rules for JNI classes, Play App Signing, AAB instead of APK. The AAB is
  ~45 MB of native code, which is fine.
- [ ] Device targeting: arm64-v8a only and OpenGL ES 3.0 required (already declared). Add
  `<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>`, and decide whether
  `android.hardware.gamepad` should be **required** at launch (it filters phones out, which reduces 1-star
  reviews) or whether to ship touch controls. Consider `android:appCategory="game"`. Landscape-only is allowed.
- [ ] Permissions are already Play-friendly: only `INTERNET`; SAF instead of `MANAGE_EXTERNAL_STORAGE` (which
  is often rejected); `<queries>` MAIN/LAUNCHER instead of `QUERY_ALL_PACKAGES`. The HOME alias is allowed.
- [ ] **No runtime download of executable code.** Cores ship in the AAB. Shader *text* downloads are data, not
  code, and shouldn't be a problem.
- [ ] ScreenScraper: a non-free app needs ScreenScraper's agreement, and customers can't be asked for a
  developer ID. Get an agreement with your own developer credentials (which then have to be in the app,
  revisiting the AGENTS.md "no developer ID built in" rule), or make RetroBase or a bring-your-own option the
  public default.
- [ ] Remove Snes9x and Genesis Plus GX, apply the decisions on Wii keys and NAOMI, ship complete GPL source for
  the player and cores, and collect licence notices (ARMSX2, Flycast and Dolphin notices are "not collected"
  per `docs/LICENSING.md`).

### 8.4 Marketing

- **Clips that sell it:**
  - press A and the box becomes the game;
  - sleep mid-game and wake right back in;
  - quick save with thumbnails;
  - multi-disc change;
  - freeze and fast-forward badge;
  - CRT shader toggle;
  - the three layouts;
  - Apps page and HOME mode.

  Use the fictional fixtures or homebrew whose authors gave written permission (credit them). Let *reviewers*
  test with their own games; you supply only UI footage.
- **Outlets:** Retro Dodo, Retro Handhelds, The Bryant Review, Steam Deck HQ (less Android-focused), and retro
  handheld YouTubers (Retro Game Corps and similar). Send a press kit: clips, original screenshots, a feature
  list and the legal stance ("no games, no BIOS, no links").
- **Communities:** r/SBCGaming, r/retroid, r/EmulationOnAndroid. Read each sub's self-promotion rules, post as
  the developer with flair, answer questions, and never answer "where do I get games".
- **Discord setup:**
  - Channels: #announcements, #changelog, #support (template: device, SoC, Android version, system, file
    format, steps), #bug-reports, #feature-requests, #showcase (UI only), #off-topic. Patreon role sync for
    #insider.
  - **Rules:** no sharing, requesting or linking ROMs, ISOs, BIOS or firmware; no naming sources; no "where to
    get" questions; first offence is deletion plus a warning, and repeats or obvious piracy mean a ban.
  - Tooling: AutoMod with a private keyword blocklist of well-known download-site names, a link allowlist,
    slow-mode in #support, and one or two trusted moderators. Pin a short FAQ: "We can't help you find games or
    BIOS."
- **Handle vs real name:** publish as a **project or brand handle** (Patreon page, Discord, GitHub for the GPL
  player, store developer name), keep your employer's name out of everything, and use personal email and
  accounts only.
  - Platforms will still know your legal name, and Play or 特商法 may require a public name or address for paid
    sales (§8.3).
  - Use a handle for *privacy*, not to hide the project from your employer: with consent in hand, there's
    nothing to hide.

### 8.5 Positioning against each competitor

- **Socket / DigiBitPlay:** "same love for physical media, but the game plays *inside*. No hand-off, one pause
  menu, real suspend."
- **ES-DE / Daijishō:** "fewer systems, but nothing to configure."
- **Lemuroid / RetroArch:** "the polish and the console feel they don't aim for."
- **Cannoli:** "the same console-like goal, with a richer media shelf."

### 8.6 Realistic revenue expectations

These are scenarios using your competitor figures (unverified by me). Gross means before Patreon fees (roughly
8–12% platform plus payment processing and currency conversion) and before Japanese income tax and resident
tax.

| Scenario (6 months after the Patreon launch) | Paid members | ≈ Gross/month |
|---|---|---|
| Low: little press, handheld-only niche, launch issues on non-RP5 devices | 30–100 | $150–600 |
| Base: steady changelogs, one or two outlet features | 150–400 | $800–2,500 |
| High: a Retro Dodo or YouTuber spotlight lands with a stable build (Socket-like) | 800–1,500 | $5,000–9,000 |

DigiBitPlay (the closest look-alike, external emulators) reached ~95 paid in ~2 months. Socket reached ~1,200
in ~3 months on the back of press and frequent changelogs. Your built-in emulation is a stronger hook than
either's, but your device coverage is the risk. One bad week of crashes on popular Mali or Dimensity handhelds
would push you toward the low row.

A **paid Play release** at ~$10 might sell a few hundred to a few thousand copies in year one. Treat this as
highly uncertain, because Play's paid-app audience for emulators is small and price-sensitive, and free
Lemuroid sits next to you. Google takes 15% on the first $1M a year.

Plan your life on the low row and treat anything above it as upside. Note too that higher income raises the
immigration questions in §4.2 (questions 9 and 10).

---

## 9. Technical health check (launch risks)

Ordered by how much they'd hurt a public launch.

1. **Single-device validation.** Everything was proven on one RP5 (Android 13, SD865/Adreno 650; firmware in
   `docs/CONSOLES.md`). The self-drawing engines (Flycast, Dolphin, ARMSX2) use GL ES 3 only (no Vulkan) and
   depend on driver behaviour the shim compensates for (`cartridge_shim.c`). **Expect black screens or
   corruption on some Mali and PowerVR devices** until tested. Get 3–5 representative devices before the
   alpha.
2. **PS2 (ARMSX2)**:
   - only two games tried;
   - no save states (`Snapshots.Never`);
   - two memory cards shared by all games;
   - the engine's per-game patches are left out;
   - `allowNativeHeapPointerTagging="false"` app-wide (manifest);
   - fast-forward unavailable;
   - CPU-heavy, so mid-range SoCs (SD7xx, Dimensity 8xxx and below) will often be below full speed.

   The in-tree build of its 11 dependency libraries has **never run** (a prebuilt set in `.tools/` was used,
   per `docs/ROADMAP.md`), which is a release-reproducibility risk.
3. **GameCube (Dolphin)**:
   - one game tried;
   - forced single-thread mode to avoid a deadlock (`dolphin_main_cpu_thread`), which costs performance on
     mid-range devices;
   - no states;
   - Wii keys in the binary (§2.1);
   - an unload-thread lifecycle that crashed before and is now guarded only by `MainActivity.endSession`
     ordering. Re-verify it after the process split.
4. **Saturn (Beetle Saturn)**: one of your games is running on it now. That's more than `docs/CONSOLES.md`
   records, which still says no game has started, so update that doc with the result. One game, though, is
   still thin evidence. It's an accuracy-first, interpreted core, likely too slow for demanding 3D games on
   mid-range devices, and there's no Change Disc yet. Label it "experimental" in 1.0 unless more games and
   devices say otherwise.
5. **Dreamcast (Flycast)**: three discs tried; 27 MB states written at pause, not during play; digital-only
   triggers (racing games suffer); Change Disc off.
6. **Storage model**: games are copied into private storage. That means double disk use for DVD and GD images,
   long imports, and **data loss on uninstall** (`allowBackup="false"` in the manifest; only the manual backup
   protects saves). This is a support nightmare for sideloaded Patreon builds. Prioritise play in place, or at
   least loud warnings and an easy backup.
7. **No touch controls and English only** (§6.3).
8. **Licensing blockers** in the build itself (§1.3, §2.1).
9. **Process and release hygiene**:
   - 48 uncommitted files (the multi-disc work), so commit them in reviewable pieces;
   - `versionName "0.7.0-ps1"` is stale;
   - no release build type or signing config;
   - `tools/Test-Device.ps1` fails under Windows PowerShell 5.1 at `adb push`
     (`docs/ux/IMPLEMENTATION_STATUS.md`);
   - one intermittent library-test failure is recorded in `docs/TESTING.md`.
10. **Memory and thermals**: one ~174 MiB PSS snapshot for the library; no long thermal or battery profiling
    of PS2 or GC sessions. The two-process split adds a second process's overhead, so measure it.
11. **Quiet scan on open locks library input** while it runs (IMPLEMENTATION_STATUS). On large SD-card
    libraries that will feel like a hang.
12. **Save-format changes coming:** replacing the SNES and Genesis cores invalidates their states (keyed by
    revision). Communicate it, and keep battery saves compatible.

---

*End of report. Files referenced are relative to the repository root. Upstream engine paths refer to the pinned
checkouts under `.tools/upstream/feasibility/` (ignored by Git).*
