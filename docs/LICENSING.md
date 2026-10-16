# License audit — feasibility integration

PlayStation 2 engine replaced (2026-10-13, ADR 0026). Play! and everything of it (its BSD-2-Clause
text, the link-time replacement `cartridge_play.cpp`, its packaged `GameConfig.xml`) left the build.
In its place:

| Engine | Source and pin | Licence | Consequence |
| --- | --- | --- | --- |
| ARMSX2 | ARMSX2/ARMSX2 `0076b5c389ea7296331ad2d96193c6e8d5d314fa`, a continuation of PCSX2, built unmodified as its own `pcsx2-libretro` target with `cartridge_shim.c` linked in. No submodules | GPL-3.0 (`ARMSX2-GPL-3.0.txt`, the tree's own `COPYING.GPLv3`). Configured by its build from its `3rdparty` folder (which of them the Libretro target finally links was not traced): fmt (MIT), fast_float (Apache-2.0, BSL-1.0 or MIT), rapidyaml (MIT), rapidjson (MIT), LZMA SDK (public domain), libchdr (BSD-3-Clause), libzip (BSD-3-Clause), SoundTouch (LGPL-2.1), simpleini (MIT), Dear ImGui (MIT), cpuinfo (BSD-2-Clause), rcheevos (MIT), cubeb (ISC), discord-rpc (MIT), FreeSurround (GPL, its `GPL.txt`), glad (generated loader), VIXL (BSD-3-Clause), a demangler taken from GCC's libiberty (GPL and LGPL files, both texts beside it) and a symbol-table reader (ccc) | Source and notice duties as for the other GPL engines, and the first GPL-3.0-only-or-later *core* in the build (the host library already is). The per-library notices have **not** been collected |
| Libraries ARMSX2 links, built first by its own Android recipe | libpng `49363adc` (libpng licence), libjpeg-turbo `9217719d` (IJG, BSD-3-Clause, zlib), Zstandard `f8745da6` (BSD-3-Clause option), LZ4 `ebb370ca` (BSD-2-Clause library), libwebp `4fa21912` (BSD-3-Clause), SDL `badbf8da` (zlib), FreeType `526ec5c4` (FTL option), PlutoVG `5695a711` and PlutoSVG `31f7d267` (MIT), curl `75a2079d` (curl licence), and the headers only of libpcap `bbcbc917` (BSD-3-Clause) | as named | Statically linked into the core. Notices not collected. libpcap contributes no code: Android has a stand-in for the engine's network adapter and the archive linked is empty |

The engine's resources (its game database among them) are embedded in the core by its own build.
Its per-game patch archive is **not** included: the build would download it from an unpinned
address, and that step is switched off.

The console's BIOS is Sony's and is not included, downloaded or described by any checksum here.
The owner adds a dump of their own through SAF; it is recognised by the structure of the file, kept
in the app's private system folder, and excluded from backups, the repository and APK assets. The
device tests that need one read it from a private folder on the device and are skipped without it.
The stand-in the BIOS-less test imports is an original 4 MiB file of zeroes with a file table and a
version string written into it; it is not a BIOS and cannot start anything.

Three engines that draw for themselves (2026-10-11, ADR 0023). Each is the unmodified upstream
source at a pinned commit; the original code linked into them is `cartridge_shim.c` and, for Play!
(removed 2026-10-13, see above), `cartridge_play.cpp`. None was edited.

| Engine | Source and pin | Licence | Consequence |
| --- | --- | --- | --- |
| Flycast 2.7 | flyinghead/flycast `5aa091fde632fb332c8d8c34e280d62dc951954c`, with submodules libchdr, rcheevos, asio, libjuice, tinygettext | GPL-2.0 (`Flycast-GPL-2.0.txt`). Compiled in from its tree: libchdr (BSD-3-Clause), zlib, Zstandard (BSD), LZMA SDK (public domain), xxHash (BSD-2-Clause), glm (MIT), libretro-common units under their file notices, rcheevos (MIT), asio (BSL-1.0), libjuice (MPL-2.0), tinygettext (zlib licence), Vixl (BSD-3-Clause), and others its `core/deps` carries | Source and notice duties as for PCSX ReARMed, over a much larger tree. The per-library notices have **not** been collected |
| Play! *(removed 2026-10-13)* | jpd002/Play- `83700b2c31e593bc94e845b4b31b797be84dda59`, with the author's Framework, CodeGen and Nuanceur, libchdr, and from its dependency bundle zlib, Zstandard, xxHash and ghc::filesystem | BSD-2-Clause; Framework, CodeGen and Nuanceur by the same author under the same terms; ghc::filesystem MIT | No longer built or packaged |
| Dolphin (Libretro fork) | libretro/dolphin `114977f115757b1d5506da93c825188233813ec1`, with 23 of its bundled libraries fetched, mGBA among them | GPL-2.0-or-later for most of its own code; "in aggregate … compatible with the GPLv3" by its own statement (`Dolphin-GPL-2.0-or-later.txt` holds that statement and the licence text). Bundled libraries under BSD, MIT, zlib, Apache-2.0, MPL-2.0 and others, each with its own file in that tree | Source and notice duties over the largest tree in the build. The per-library notices have **not** been collected. Its `Data/Sys` subset (replacement fonts with their own licence file, DSP tables, per-game settings, shaders) is packaged as an asset |

Dolphin's fork is built as the Libretro project builds it for Android, which includes a second copy
of mGBA (for the Game Boy Advance link cable) inside the GameCube core. It is not used by Cartridge
and is not the mGBA that plays Game Boy games.

Nothing of Sega's, Sony's or Nintendo's is included: no BIOS, no IPL, no font or DSP ROM dump (the
ones in Dolphin's data are its project's free replacements), no game. The three test programs in
`tools/probe` (SH-4, MIPS R5900 and PowerPC machine code) are original. A Dreamcast BIOS is the
owner's to add and is accepted by content; GameCube takes no file from the owner, and PlayStation 2
did not while it ran on Play!.

For this private build the above is a record. For any distribution it adds two GPL trees and their
bundled libraries to the source-and-notice work the release gate already names.

Console increment (2026-10-11, ADR 0022). Four cores were added, each the unmodified upstream
source fetched by archive hash and built by `app/src/main/cpp/cores.cmake`; the only original code
linked into them is `cartridge_shim.c` (callback counters) and the export list `cartridge_core.T`.

| Core | Source and pin | Licence | Consequence |
| --- | --- | --- | --- |
| Snes9x | snes9xgit/snes9x tag 1.63, archive SHA-256 `bec7792b1dec6bbcbf080064322bd196eb5938ab4599047c4725af7343492162` | Snes9x licence: free for personal use, **no commercial use** without the copyright holders' permission; source changes are to be offered back | Fine for this private build. Not GPL-compatible in distribution terms: a public release would need permission or another SNES core |
| FCEUmm | libretro/libretro-fceumm `7a542dab1e87679921962a9f056186eca425c0c2`, SHA-256 `a2e8793d24343a3d0cdebdad38184ca8defd61224803d12f5f53b963554d8a8a` | GPL-2.0-or-later | Same source and notice duties as PCSX ReARMed |
| Genesis Plus GX | libretro/Genesis-Plus-GX `393f70cd64c7334564c19a424c383f53986c13f4`, SHA-256 `7de279d6f23998438ea82019b501ee822f1873c02ee4e63024d98736eeeb9f2c` | Genesis Plus GX licence: redistribution **not for sale or commercial use**, source of modified builds required. Bundled: Nuked OPN2/OPLL (LGPL-2.1-or-later), Tremor (BSD), libchdr (BSD-3-Clause), LZMA SDK (public domain), zlib, Zstandard (BSD) | Fine for this private build; a further gate on public release |
| Beetle Saturn | libretro/beetle-saturn-libretro `ec20ea05023f6c863f89d7e5052bd63f406852ff`, SHA-256 `d7e8869dd7e21ca4b4ff9684cbfdba5ff89b5abd0f2b0235488201a5ccfad036` | GPL-2.0 (Mednafen); bundled Tremor (BSD) and libretro-common units under their file notices | Same duties as PCSX ReARMed |

The licence texts are packaged with the app from `docs/licenses` (`Snes9x-non-commercial.txt`,
`FCEUmm-GPL-2.0.txt`, `Genesis-Plus-GX-non-commercial.txt`, which carries its bundled components'
notices, and `Beetle-Saturn-GPL-2.0.txt`) and are shown under Settings › System › About & licences.
The per-file notices of the libretro-common units these cores compile still need collecting before
any release.

Each core is a separate shared library loaded at run time. Whether that separates the two
non-commercial licences from the GPL-3.0-or-later host library in a distributed APK has not been
assessed; it does not need to be for a build that is not distributed. **The public-release gate now
has three parts:** the existing GPL source plan, permission or replacement for Snes9x and Genesis
Plus GX, and provider artwork terms.

No BIOS, ROM or logo was added. The Saturn BIOS is supplied by the owner through SAF, accepted
only by the two MD5 digests the pinned core's README names, and excluded from backups, the
repository and APK assets. The test programs in `tools/probe` (65816, 6502, 68000, Z80 and SH-2
machine code written for this project, and a disc image holding only the console's 16-byte name)
are original; the SH-2 image is test equipment and not a BIOS replacement.

PS1 audit addition (2026-10-10): PCSX ReARMed commit `c8816799b50388e61cfe237fe2cdbb7d8175f20a`,
archive SHA-256 `5e1b15f17d0e97225d8b2eb29a8412fb0d3b03ff242c5456d055ec2967667a83`.
The compiled frontend, PSX core, NEON and arm64 dynarec source headers specify GPL-2.0-or-later;
preserve the full COPYING text and corresponding source, including the original callback wrapper
and CMake build recipe. The existing GPL-3.0-or-later combined-application release gate remains.
No PCSX standalone frontend/libpicofe, Lightrec or bundled external core binary is used.

Compiled CHD dependencies: libchdr BSD-3-Clause (also retain its MAME/Aaron Giles file notices),
LZMA SDK 26.02 public domain, Zstandard 1.5.7 under its offered BSD-3-Clause option, and dr_flac
0.13.3 under its offered MIT-0 option. Android system zlib is linked; public Libretro header notices
remain in the pinned corresponding source. Added licence texts in docs/licenses are packaged by
the existing notice sync task. Full transitive source/notice packaging still precedes public release.

Primary sources: [pinned Android recipe](https://github.com/libretro/pcsx_rearmed/blob/c8816799b50388e61cfe237fe2cdbb7d8175f20a/jni/Android.mk),
[frontend header](https://github.com/libretro/pcsx_rearmed/blob/c8816799b50388e61cfe237fe2cdbb7d8175f20a/frontend/libretro.c),
[core documentation](https://docs.libretro.com/library/pcsx_rearmed/).
The disc mesh, fictional print, MIPS/ISO/CHD diagnostic generator and ADPCM test tone are original.
No commercial ROM, BIOS, logos or media are added. The owner supplies firmware through SAF;
it is excluded from backups, repository and APK assets. Provider artwork rights remain independent.

Audit date: 2026-10-09. This records selected sources and known obligations. It is not a completed
public-release source/notice package. No commercial ROM, BIOS, copyrighted game art or branded media
model is bundled. Runtime user-selected artwork is privately cached with provenance. Cannoli/Lemuroid,
Socket/Slot were references only; no frontend code was copied from them.

| Dependency | Version / source | License and consequences |
| --- | --- | --- |
| LibretroDroid | 0.14.0, commit 8835c3098514390a271e36983957f7bb5f40abf1, built from source in `libretrodroid/` since 2026-10-16 (changes listed in its `UPSTREAM.md`) | GPL-3.0-or-later in source headers; published metadata labels GPL-3.0. A combined frontend distribution needs GPL-compatible licensing and complete corresponding source/build instructions |
| mGBA | 0.10.5, hash-pinned source | MPL-2.0 main code, file-level source obligations and notices; source/build changes must be available as required |
| blip_buf in mGBA | 1.1.0 in selected source | LGPL-2.1-or-later; compiled into the core, so source and suitable relinking/rebuild materials must be provided, not just the top-level MPL license |
| inih in mGBA | bundled source | BSD-3-Clause; retain copyright/license/disclaimer |
| mGBA Libretro headers | selected source | File-level permissive notices, including MIT retro_inline.h; preserve corresponding source/header notices |
| Filament + gltfio + filament-utils | 1.72.1 | Apache-2.0; preserve notices/licenses and audit bundled third-party components before release. M2 uses the official KTX loader |
| cmgen | official Filament 1.72.1 asset tool, not packaged | Apache-2.0/project notices; processes only the original analytic HDR environment. No bundled sample environment adopted |
| Khronos glTF Validator | npm 2.0.0-dev.3.10, tools only | Apache-2.0 as inspected in its package LICENSE; ignored tool installation, not production code |
| SceneView | v4.53.0 inspected, not linked | Apache-2.0; no SceneView binary in the APK |
| AndroidX/Compose/AGP | pins in build scripts | Apache-2.0; retain required notices for distributed artifacts |
| Kotlin/coroutines | 2.1.20 / 1.10.2 | Apache-2.0; retain required notices |
| Room runtime/compiler/Gradle plugin | 2.7.2 | Apache-2.0 in resolved Maven POMs; runtime notices, build-tool provenance |
| KSP build plugin | 2.1.20-1.0.32 | Apache-2.0 in resolved Maven POM; build-only code generation |
| Compose UI test / Espresso Intents | BOM 2025.04.01 / 3.6.1 | AndroidX Apache-2.0; instrumentation-only, not production runtime |
| Gradle wrapper | 8.13 | Apache-2.0; generated wrapper retained, distribution checksum pinned |
| Oboe in LibretroDroid | b15f5e39c01a7ada306d959e5129620b145fb8b4, fetched by archive hash | Apache-2.0; corresponding selected source/notice included in a frontend distribution |
| glslang in LibretroDroid | Vulkan SDK 1.4.363.0 tag (commit e1b562a8bed273a02f30b59b66a5d499793cede5), fetched by archive hash; GLSL front end and SPIR-V generation only | BSD-3-Clause, with MIT, Apache-2.0 and other files as its LICENSE.txt lists; text in `docs/licenses/glslang-BSD-3-Clause-and-others.txt` |
| SPIRV-Cross in LibretroDroid | Vulkan SDK 1.4.363.0 tag (commit f11ba9f0b21ba8fc15153d50a2a1ae31ab1cf8f7), fetched by archive hash; core and GLSL back end only | Apache-2.0; `docs/licenses/SPIRV-Cross-Apache-2.0.txt` |
| RetroArch GLSL shader library | `libretro/glsl-shaders`, downloaded by the owner at run time (ADR 0027), never in the repository or APK | Mixed per-shader licences, many GPL; this build is the owner's own |
| libretro-common in LibretroDroid | b0c348ea5543c4d7fb0bc479258aa6988b20c0c9, fetched by archive hash | File-level permissive licenses for the five compiled C files; preserve headers, not a guessed repository-wide license |
| Studio IBL, contact WAV, icon/probe | original project generators/source | Original development assets, no adopted third-party art/audio/HDR photographs; project-wide licensing choice remains with the owner |
| Cartridge, tall cartridge and disc GLB (since 2026-10-10) | three models the owner downloaded from Sketchfab, converted by `tools/import_models.py` | Third-party models. The owner states they are free to use and that this build is for personal use only; the individual licence and author of each download are not recorded here. See "Imported media models" below |
| NES, Super Famicom, Super Nintendo and Genesis cartridge GLB (since 2026-10-12) | four more models the owner supplied, converted by the same tool | Third-party models, one of them taken from a commercial mobile game. Same statement by the owner; same gap in what is recorded. See "Imported media models" below |
| SaveBridge / SRAM diagnostic | original C/JNI and ARM assembly | No frontend implementation or third-party assets copied; uses public Libretro ABI and Android/NDK platform APIs. Combined-app GPL/source obligations remain |

Inspected sources:

- [LibretroDroid license](https://github.com/Swordfish90/LibretroDroid/blob/0.14.0/LICENSE),
  [build](https://github.com/Swordfish90/LibretroDroid/blob/0.14.0/libretrodroid/build.gradle),
  [submodules](https://github.com/Swordfish90/LibretroDroid/blob/0.14.0/.gitmodules).
- [mGBA license](https://github.com/mgba-emu/mgba/blob/0.10.5/LICENSE),
  [blip_buf header notice](https://github.com/mgba-emu/mgba/blob/0.10.5/src/third-party/blip_buf/blip_buf.c),
  [inih notice](https://github.com/mgba-emu/mgba/blob/0.10.5/src/third-party/inih/LICENSE.txt).
- [Filament license](https://github.com/google/filament/blob/v1.72.1/LICENSE).
- [Room dependencies/schema configuration](https://developer.android.com/jetpack/androidx/releases/room),
  [KSP pinned release](https://github.com/google/ksp/releases/tag/2.1.20-1.0.32),
  [Espresso Intents](https://developer.android.com/training/testing/espresso/intents).
- [libretro-common compiled VFS file](https://github.com/libretro/libretro-common/blob/b0c348ea5543c4d7fb0bc479258aa6988b20c0c9/vfs/vfs_implementation.c).

Preserved texts are in `docs/licenses/`. LibretroDroid's full source/submodule commits and mGBA's
complete hash-pinned archive can be retrieved from those references. The source-built core disables
Qt/SDL/FFmpeg/libpng/zlib/libzip/SQLite/Lua/debugger dependencies, and retains GBA emulation and audio.
LibretroDroid is no longer taken from JitPack: its tag's source is in `libretrodroid/` with its
licence, and its two submodules are fetched by archive hash, so the source of the built library is
in the repository.

## Imported media models

The cartridge, tall cartridge and disc shown in the library are converted from three Sketchfab
downloads kept under `assets/models` (ADR 0019). The owner supplied them, describes their licence
as free to use, and uses this build privately. This audit did not see the licence text or author
credit of any of the three, so nothing here should be read as cleared for redistribution.

What the converted files contain: the makers' geometry and shell textures, including embossed
console names and logos, a circuit-board texture with its manufacturer's copyright line, and a
disc maker's print in the disc texture (not shown: the game's label covers that side). What they do not contain: the commercial game labels
that were baked into both cartridge textures. The conversion fills those regions with plain
plastic and checks that nothing printed remains, and the embedded placeholder label is original.

Four more were added on 2026-10-12 (ADR 0025), on the same terms and with the same gap: the owner
supplied them and said not to hold the work up over their licences, because the build is only for
them. What is known from the files themselves:

| Runtime file | What the download is | What it still carries |
|---|---|---|
| `nes_cartridge.glb` | a modelled Game Pak with a diffuse and a normal map | the maker's geometry and relief; no print |
| `snes_cartridge.glb` | a CAD conversion of the Super Famicom / PAL shell with a PBR texture set | the maker's geometry; gold contacts; no print (the label area held the model site's name and logo, which is filled) |
| `snes_us_cartridge.glb` | a photographic scan of one real cartridge | the scan's geometry and its photograph of the plastic, including the moulded Nintendo name on the back and the printed warning sticker there |
| `genesis_cartridge.glb` | one of four cartridge meshes **taken from a commercial mobile game**, by the archive's own name | that game's low-polygon mesh and the grey part of its atlas, including the moulded console name plate |

None of them carries a game's label any more: the conversion fills it and asserts that it is gone
(ASSET_PIPELINE). The scan's back sticker and the moulded names are the console maker's, not a
game's, and are left as on the real objects. The Genesis mesh is the one whose origin most plainly
rules out distribution: it is another product's asset, not a model someone published for reuse.
`assets/models/n64` holds a fifth download that is not converted or packaged.

Earlier statements in this file that no branded media model is bundled describe the generated
templates these replaced. Before any distribution beyond the owner's own devices, record each
model's licence and required attribution, replace the one taken from a game, and decide whether the
trademarks on the shells may ship.

## Release gate

Choose a GPL-compatible project/distribution license, or replace LibretroDroid with a separately audited
implementation before a proprietary-release plan. Prepare complete corresponding frontend/core source,
the exact submodule trees, build scripts, source modifications, appropriate LGPL relinking materials,
all required dependency notices and an in-app notices entry. Audit the resolved transitive Android and
Filament/Oboe dependencies rather than treating this table as a complete binary BOM. Do not claim GPL
obligations disappear because the core is dynamically loaded. Private feasibility builds are not a
public commercial release, and this work does not assign a blanket license to the owner's source.

## Implemented provider and imported labels

RetroBase Collection's public v1 API is used for runtime metadata/media retrieval, not linked library
code. Its [developer documentation](https://retrobase-collection.com/developers) permits anonymous
access. Its [legal notice](https://retrobase-collection.com/legal) states that the final data license is
pending and grants no image license. Store the API's license status and provider/source/record/time
with each accepted selection; show Data: RetroBase Collection in the scraper. These facts are not a
blanket redistribution grant. Resolve data/service terms before a public release or redistributable
metadata export. Commercial images remain outside Git, fixtures and APK assets. Tests use original
artwork, with a separate live API test that retrieves only metadata and media URLs.

The folder/removal/resumable-queue increment adds no dependency. DocumentsContract, DocumentsProvider
and AtomicFile are Android platform APIs. Folder and migration/device fixtures use the project's own
diagnostic ROM and original solid-color labels. Runtime ROMs, scraped artwork and private database
inspection copies stay outside Git/APKs. Entry removal retains these private originals and saves.

See PROVIDER_RESEARCH for the precise Socket/Slot evidence. Neither Socket's unidentified backend nor
a community fork's Skyscraper recipe establishes permission to copy that application's assets/keys.

## Unadopted scraping providers

ScreenScraper is a researched candidate, not a dependency or adopted artwork source. Its official
API documentation requires developer credentials and limits ordinary API use to completely free apps
unless a prior agreement is obtained. Check current terms, approval, quotas and individual asset rights
before implementation/distribution. A provider subscription or an API response is not assumed to grant
redistribution rights over every image. Preserve imported-asset provenance and never commit scraped
commercial artwork. See [LABEL_SCRAPING.md](LABEL_SCRAPING.md) for the requested workflow.

M3 introduces original Kotlin library UI/query/index/history code and no third-party dependency,
artwork, ROM or BIOS. Existing scraper provenance and GPL/source-distribution gates still apply.

M3 review/region-language ranking/undo adds original Kotlin code and original isolated test imagery only.
No new third-party dependency or artwork is bundled. The public RetroBase OpenAPI/developer reference
and live response keys were rechecked on 2026-10-09 for source tags; no additional usage/redistribution
rights are inferred. Preference ranking and private undo retain provider/rightsholder provenance.
Existing GPL-compatible complete-source and provider/image distribution gates remain unresolved.

Offline scrape packs add original ZIP/JSON/import/helper code only, using Android/Java/Python standard
APIs. No commercial pack or artwork is committed/bundled. Private imported candidates keep the source
provider/licence/URL; internal local URLs do not grant redistribution rights. The PC helper never includes
ROM bytes. Provider/GPL release obligations remain, including for a user-exported redistributable pack.

M4 adds original layout/input/rendering code and isolated original artwork/homebrew tests. It adopts
no new dependency or media asset. Existing complete-source and provider/image release gates apply.

M5 enables GB/GBC source in the same hash-pinned MPL-2.0 mGBA 0.10.5 archive; dependencies/licenses remain unchanged. The original mgba_frontend.c.in wrapper includes the unchanged upstream frontend and adds save-mask/RTC synchronization. Distribute this wrapper, pinned source and build instructions with complete corresponding source under the existing GPL-compatible distribution plan. Tall GLB, label, grain and SM83/GBX diagnostics are original generated works; probes remain ignored/private, outside APK assets. No ROM, BIOS, boot logo or commercial artwork is included.

M6 setup/controller/core/HOME code adds no dependency. The APK now includes the matching repository licence files via a reproducible Sync task and an in-app reader. Source/relinking and full transitive notice audit remain release gates; this does not assign a project licence or imply that a private debug build is ready for public distribution.

M6 recovery extends the original native wrapper with a narrowly scoped video callback counter over the unchanged hash-pinned mGBA translation unit. Corresponding source includes this wrapper/bridge/build change. SessionStore, lifecycle and pause-frame code are original; Android PixelCopy/Bitmap/PowerManager and Java atomic-file APIs add no third-party dependency. Actual diagnostic frame captures are private and generated from original homebrew, not bundled commercial images.

Backup adds no third-party dependency. Accepted user/provider artwork stays private in an explicitly exported archive; exporting does not grant redistribution rights. Notice/licensing and complete-source release gates remain in force.

Console reliability diagnostics add original test code only and no new dependency. Process markers and runtime logs stay in ignored private fixture storage and `.tools`. No commercial game was used for automated soak/recovery.

M6's final live probe (2026-10-10, 3.794 seconds) verified the real GBA regional and GB/GBC platform/flat-label URL routes. It requested metadata/URLs only; the existing provider/image rights and complete-source release gates remain unchanged.

The Android apps library (2026-10-11, ADR 0021) adds no dependency: it uses the platform's `LauncherApps` and `PackageManager` APIs and original Compose code. Other apps' icons and names are read from the device at run time, shown, and kept only in memory; none is bundled, cached to storage, exported in a backup or committed. Its tests and screenshots draw invented apps. The transition resources under `res/anim` and `res/interpolator` are original.
