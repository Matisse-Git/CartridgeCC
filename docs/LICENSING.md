# License audit — feasibility integration

Audit date: 2026-10-09. This records selected sources and known obligations. It is not a completed
public-release source/notice package. No commercial ROM, BIOS, copyrighted game art or branded media
model is bundled. Runtime user-selected artwork is privately cached with provenance. Cannoli/Lemuroid,
Socket/Slot were references only; no frontend code was copied from them.

| Dependency | Version / source | License and consequences |
| --- | --- | --- |
| LibretroDroid | 0.14.0, commit 8835c3098514390a271e36983957f7bb5f40abf1 | GPL-3.0-or-later in source headers; published metadata labels GPL-3.0. A combined frontend distribution needs GPL-compatible licensing and complete corresponding source/build instructions |
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
| Oboe in LibretroDroid | b15f5e39c01a7ada306d959e5129620b145fb8b4 | Apache-2.0; corresponding selected source/notice included in a frontend distribution |
| libretro-common in LibretroDroid | b0c348ea5543c4d7fb0bc479258aa6988b20c0c9 | File-level permissive licenses for the five compiled C files; preserve headers, not a guessed repository-wide license |
| Cartridge/receiver GLB, PNG, studio IBL, contact WAV, icon/probe | original project generators/source | Original development assets, no adopted third-party art/audio/HDR photographs; project-wide licensing choice remains with the owner |
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
JitPack's AAR is version-pinned; retain its source and build provenance for any actual release.

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
