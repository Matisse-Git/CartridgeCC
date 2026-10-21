# LibretroDroid, built from source

This module is [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) at tag `0.14.0`, commit
`8835c3098514390a271e36983957f7bb5f40abf1`, the version the app used as JitPack's prebuilt AAR until
2026-10-16. It is built here so that the host's final drawing pass can take downloaded shader presets
(ADR 0027). Its licence is GPL-3.0-or-later (`LICENSE`, also `docs/licenses/LibretroDroid-GPL-3.0.txt`).

`src/` and `LICENSE` were copied from the tag's archive
(`https://codeload.github.com/Swordfish90/LibretroDroid/tar.gz/8835c3098514390a271e36983957f7bb5f40abf1`,
SHA-256 `74a8c471697bbdf2e675164f311c83b89ef1546fd135016d955d26fdc217b137`), whose contents also match
`.tools/upstream/LibretroDroid-0.14.0`. The tag's two submodules are fetched by CMake at the commits the
tag pins, each by a hash of its complete archive, and are never copied into this tree:

| Submodule | Commit | Archive SHA-256 (codeload zip) |
| --- | --- | --- |
| google/oboe | `b15f5e39c01a7ada306d959e5129620b145fb8b4` | `eb2a817017a554f664c5b6262ceef55756097a1e0b9536045de0f33b9868ef6e` |
| libretro/libretro-common | `b0c348ea5543c4d7fb0bc479258aa6988b20c0c9` | `c8deb75ad240745551611843d7c52ae10f553871a798fcd949bfda6e70acfde0` |

Added for shader presets (ADR 0027), fetched the same way and built static with only what translation needs:

| Project | Tag (commit) | Archive SHA-256 (codeload zip) |
| --- | --- | --- |
| KhronosGroup/glslang | `vulkan-sdk-1.4.363.0` (`e1b562a8bed273a02f30b59b66a5d499793cede5`) | `1041701e37fbe62ec6e3e80515fdfcb696ee69e5a5731cd8aa0db32bf458fde8` |
| KhronosGroup/SPIRV-Cross | `vulkan-sdk-1.4.363.0` (`f11ba9f0b21ba8fc15153d50a2a1ae31ab1cf8f7`) | `637ee99192da3cea88bb81c97bcb7177d7b0010f75d2afd6c97f8e7b7d2a0c90` |

## Changes from the tag

Only what building it with this project's toolchain (NDK 27.2, CMake 3.22.1 from Windows) needs:

- `src/main/cpp/CMakeLists.txt`: the two submodules come from `FetchContent` instead of checked-out
  folders. libretro-common is unpacked into a folder named `libretro/libretro-common`, because the
  sources include `../../libretro-common/include/libretro.h` relative to its include directory.
  The library links with `--driver-mode=g++`, the same as this project's cores: a project path with a
  space makes CMake call the linker driver by its 8.3 name, and the C++ runtime would be left out.
- `src/main/cpp/rumble.h`, `src/main/cpp/utils/javautils.h`: `#include <functional>`. The tag
  relied on NDK 26's libc++ including it from other headers, which NDK 27's no longer does.
- `build.gradle.kts` replaces the tag's `build.gradle`: arm64 only, compile SDK 35, the project's
  NDK, no publishing. Native code is built as `RelWithDebInfo` (`-O2 -DNDEBUG`) for every variant,
  as the published release AAR was, so a debug build of the app does not get an unoptimised host.

The prebuilt AAR (SHA-256 `cd987ed8d0e2af1410b1ad81f53df2163339e3ae4105b8906a7fdc314998b414`) and this
build were compared on 2026-10-16: the same 39 classes with identical public signatures (`javap
-public`), the same 34 exported JNI functions, and the same system libraries linked. The AAR was built
with NDK r26b's clang 17 at API 21; this one uses NDK 27.2's clang 18 at API 21.

Every later change to this module is the project's own and is described in its commit and ADR.

## Games of several discs (ADR 0032)

- `Environment` answers `RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION` with 1 and takes
  `RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE`. The extended interface's first seven functions are copied into
  an original `retro_disk_control_callback`, so `availableDisks`, `currentDisk` and `changeDisk` are unchanged and
  work with either interface. Cores that ask for the version (PCSX ReARMed, Beetle Saturn, Flycast) now register the
  extended interface; ARMSX2 and Dolphin, which only register it, are now heard.
- `LibretroDroid.setInitialDisk(index, path)` (JNI, `LibretroDroid::setInitialDisk`) calls the core's
  `set_initial_image`. `GLRetroViewData.initialDisk`/`initialDiskPath` make `GLRetroView` call it just before it loads a
  game from a path, so a playlist starts on the disc asked for. A core without the extended interface ignores it and
  starts on its first disc.
- The host's API is otherwise unchanged.

## Shader presets (ADR 0027, step 2)

- `ShaderConfig.Preset` and `ShaderPreset` (Kotlin), `ShaderManager::Type::SHADER_PRESET` and a
  `PresetSpec` pointer in `ShaderManager::Config`: a RetroArch GLSL preset already read by the app.
  `GLRetroShader` carries it to the native side (`JavaUtils::presetFromJava`).
- `presetchain.{h,cpp}`: `PresetChain` builds and draws a preset following RetroArch 1.22.2's GL driver
  (pass sizes, FBO formats, filters and wraps, mipmaps, aliases, LUTs, `Orig`/`PassN`/`PassPrevN`/`Prev`
  frames, feedback, frame counters, parameters). The core's frame is first copied upright into a texture of
  the chain's own, so one orientation runs through every pass. When RetroArch's way of compiling a pass
  fails, `link` tries the others listed above `VARIANTS` before refusing the preset with RetroArch's own error.
  `PresetChain::check` builds a preset and draws two frames in a pbuffer context of its own.
- `Video` builds the chain on the GL thread when a preset is requested, draws it instead of the built-in
  passes, and falls back to the default built-in shader when it cannot be built or fails while drawing;
  `takeShaderFailure` reports that once. The requested shader is guarded by a mutex, since it is set from
  the UI thread and read on the GL thread.
- JNI: `LibretroDroid.takeShaderFailure()` (polled after each `step`, emitted on
  `GLRetroView.getShaderFailures()`) and `LibretroDroid.checkShaderPreset()` (`ShaderPreset.check()`).
- `consumer-rules.pro` keeps the fields the native side reads by name.
- Every precision qualifier in a preset's source is read as `highp`, as desktop GL reads them, and float
  framebuffers are 32-bit where the GPU can draw to and filter them (16-bit otherwise), as RetroArch's GL
  driver makes them. A pass may carry a second, GLSL ES form of its source (`compatSource`, made by the app's
  `GlslCompat`), compiled only when its own does not compile.
- `glsltranslate.{h,cpp}`: a pass's two stages as desktop GLSL 3.30 (RetroArch's defines, line continuations
  joined), compiled together by glslang for OpenGL and written out by SPIRV-Cross as GLSL ES 3.00 with high
  precision and no bindings or uniform locations, so names stay as the host looks them up. `link` tries it
  after the source as written and before the app's rewritten source. The stripped library grows from 1.5 to
  5.8 MB.
- A check logs each pass's mean colour after drawing (`PresetChain::describe`), and every refused way of
  compiling a pass is logged with the compiler's whole message.
- The last pass is drawn on a rectangle of whole pixels, the viewport's size, at the layout's corner rounded
  to a pixel, as RetroArch's viewport is; the layout's own fractional rectangle made a viewport-sized pass
  skip or repeat a column. The rectangle is logged when the chain's sizes change.
