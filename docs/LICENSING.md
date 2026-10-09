# License audit — feasibility integration

Audit date: 2026-10-09. This records selected sources and known obligations. It is not a completed
public-release source/notice package. No commercial ROM, BIOS, copyrighted game art or branded media
model was adopted. Cannoli/Lemuroid were references only; no frontend code was copied from them.

| Dependency | Version / source | License and consequences |
| --- | --- | --- |
| LibretroDroid | 0.14.0, commit 8835c3098514390a271e36983957f7bb5f40abf1 | GPL-3.0-or-later in source headers; published metadata labels GPL-3.0. A combined frontend distribution needs GPL-compatible licensing and complete corresponding source/build instructions |
| mGBA | 0.10.5, hash-pinned source | MPL-2.0 main code, file-level source obligations and notices; source/build changes must be available as required |
| blip_buf in mGBA | 1.1.0 in selected source | LGPL-2.1-or-later; compiled into the core, so source and suitable relinking/rebuild materials must be provided, not just the top-level MPL license |
| inih in mGBA | bundled source | BSD-3-Clause; retain copyright/license/disclaimer |
| mGBA Libretro headers | selected source | File-level permissive notices, including MIT retro_inline.h; preserve corresponding source/header notices |
| Filament + gltfio | 1.72.1 | Apache-2.0; preserve notices/licenses and audit bundled third-party components before release |
| SceneView | v4.53.0 inspected, not linked | Apache-2.0; no SceneView binary in the APK |
| AndroidX/Compose/AGP | pins in build scripts | Apache-2.0; retain required notices for distributed artifacts |
| Kotlin/coroutines | 2.1.20 / 1.10.2 | Apache-2.0; retain required notices |
| Gradle wrapper | 8.13 | Apache-2.0; generated wrapper retained, distribution checksum pinned |
| Oboe in LibretroDroid | b15f5e39c01a7ada306d959e5129620b145fb8b4 | Apache-2.0; corresponding selected source/notice included in a frontend distribution |
| libretro-common in LibretroDroid | b0c348ea5543c4d7fb0bc479258aa6988b20c0c9 | File-level permissive licenses for the five compiled C files; preserve headers, not a guessed repository-wide license |
| Cartridge GLB/PNG/icon/probe | original project generators/source | Original development assets, no adopted third-party art; project-wide licensing choice remains with the owner |

Inspected sources:

- [LibretroDroid license](https://github.com/Swordfish90/LibretroDroid/blob/0.14.0/LICENSE),
  [build](https://github.com/Swordfish90/LibretroDroid/blob/0.14.0/libretrodroid/build.gradle),
  [submodules](https://github.com/Swordfish90/LibretroDroid/blob/0.14.0/.gitmodules).
- [mGBA license](https://github.com/mgba-emu/mgba/blob/0.10.5/LICENSE),
  [blip_buf header notice](https://github.com/mgba-emu/mgba/blob/0.10.5/src/third-party/blip_buf/blip_buf.c),
  [inih notice](https://github.com/mgba-emu/mgba/blob/0.10.5/src/third-party/inih/LICENSE.txt).
- [Filament license](https://github.com/google/filament/blob/v1.72.1/LICENSE).
- [libretro-common compiled VFS file](https://github.com/libretro/libretro-common/blob/b0c348ea5543c4d7fb0bc479258aa6988b20c0c9/vfs/vfs_implementation.c).

Preserved texts are in `docs/licenses/`. LibretroDroid's full source/submodule commits and mGBA's
complete hash-pinned archive can be retrieved from those references. The source-built core disables
Qt/SDL/FFmpeg/libpng/zlib/libzip/SQLite/Lua/debugger dependencies, and retains GBA emulation and audio.
JitPack's AAR is version-pinned; retain its source and build provenance for any actual release.

## Release gate

Choose a GPL-compatible project/distribution license, or replace LibretroDroid with a separately audited
implementation before a proprietary-release plan. Prepare complete corresponding frontend/core source,
the exact submodule trees, build scripts, source modifications, appropriate LGPL relinking materials,
all required dependency notices and an in-app notices entry. Audit the resolved transitive Android and
Filament/Oboe dependencies rather than treating this table as a complete binary BOM. Do not claim GPL
obligations disappear because the core is dynamically loaded. Private feasibility builds are not a
public commercial release, and this work does not assign a blanket license to the owner's source.
