# Flycast v2.7 (5aa091fd, GPL-2.0), the Dreamcast core. It is built by its own CMake libretro target, unchanged,
# with the OpenGL ES 3 renderer the host can give it: no Vulkan, OpenMP, Lua or crash reporter. A commit pins the
# tree and, through it, each submodule; only the five submodules that target needs are fetched.
foreach(feature LIBRETRO USE_GLES)
    set(${feature} ON CACHE BOOL "" FORCE)
endforeach()
foreach(feature USE_VULKAN USE_OPENMP USE_BREAKPAD USE_LUA USE_DX9 USE_DX11 USE_ALSA USE_LIBAO USE_PULSEAUDIO
        USE_DISCORD ENABLE_CTEST)
    set(${feature} OFF CACHE BOOL "" FORCE)
endforeach()
FetchContent_Declare(flycast
    GIT_REPOSITORY https://github.com/flyinghead/flycast.git
    GIT_TAG 5aa091fde632fb332c8d8c34e280d62dc951954c
    GIT_SUBMODULES core/deps/libchdr core/deps/rcheevos core/deps/asio core/deps/libjuice core/deps/tinygettext
    GIT_SUBMODULES_RECURSE ON)
FetchContent_GetProperties(flycast)
if(NOT flycast_POPULATED)
    FetchContent_Populate(flycast)
endif()

# Gradle's debug variant must not build an emulator without optimisation. The flags are set in a function so
# they reach Flycast and its bundled libraries and nothing else.
function(cartridge_add_flycast)
    set(CMAKE_C_FLAGS_DEBUG "-O2 -g0 -DNDEBUG")
    set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g0 -DNDEBUG")
    # A two-letter build directory: Flycast's bundled zstd nests deeply enough that, under a long project path,
    # the default one pushes an object file past Windows' 260-character limit.
    add_subdirectory(${flycast_SOURCE_DIR} ${CMAKE_BINARY_DIR}/fc EXCLUDE_FROM_ALL)
endfunction()
cartridge_add_flycast()

# The readiness counters and host fixes of cartridge_shim.c, around the unchanged frontend.
cartridge_hardware_core(flycast_libretro)
