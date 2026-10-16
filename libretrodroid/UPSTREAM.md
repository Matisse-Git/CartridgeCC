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
