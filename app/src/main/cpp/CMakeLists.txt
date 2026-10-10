cmake_minimum_required(VERSION 3.22.1)
project(cartridge_cores LANGUAGES C CXX)
include(FetchContent)

# All source and build artifacts remain in Gradle's ignored .cxx directory.
# Hash pins the complete, unmodified upstream source archive.
FetchContent_Declare(mgba
    URL https://codeload.github.com/mgba-emu/mgba/zip/refs/tags/0.10.5
    URL_HASH SHA256=c8cecb3d2e2f8f5d2e67c8c986863a53bf5efeaca75cebbd69ba9100bd732eca
)
set(BUILD_LIBRETRO ON CACHE BOOL "" FORCE)
foreach(feature BUILD_QT BUILD_SDL BUILD_SHARED BUILD_STATIC BUILD_GL BUILD_GLES2
        BUILD_GLES3 BUILD_TEST BUILD_SUITE BUILD_PERF BUILD_CINEMA BUILD_ROM_TEST
        BUILD_EXAMPLE BUILD_PYTHON BUILD_LTO USE_FFMPEG USE_PNG USE_ZLIB USE_LZMA
        USE_SQLITE3 USE_LIBZIP USE_ELF USE_LUA USE_DEBUGGERS USE_GDB_STUB USE_EDITLINE
        USE_MINIZIP USE_DISCORD_RPC USE_EPOXY ENABLE_SCRIPTING)
    set(${feature} OFF CACHE BOOL "" FORCE)
endforeach()
set(DISABLE_DEPS ON CACHE BOOL "" FORCE)
set(SKIP_LIBRARY ON CACHE BOOL "" FORCE)
set(M_CORE_GB ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(mgba)
# Compile the unchanged upstream frontend through an original RTC synchronization extension.
get_target_property(cartridge_core_sources mgba_libretro SOURCES)
list(REMOVE_ITEM cartridge_core_sources "${mgba_SOURCE_DIR}/src/platform/libretro/libretro.c")
set_property(TARGET mgba_libretro PROPERTY SOURCES "${cartridge_core_sources}")
configure_file(mgba_frontend.c.in "${CMAKE_CURRENT_BINARY_DIR}/cartridge_mgba_frontend.c" @ONLY)
target_sources(mgba_libretro PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/cartridge_mgba_frontend.c")
# Android packages native libraries whose filename begins with lib.
set_target_properties(mgba_libretro PROPERTIES PREFIX "lib")
target_link_options(mgba_libretro PRIVATE "-Wl,-z,max-page-size=16384")
include(pcsx.cmake)

add_library(cartridge_saves SHARED save_bridge.c)
target_compile_features(cartridge_saves PRIVATE c_std_11)
target_compile_options(cartridge_saves PRIVATE -Wall -Wextra -Werror)
target_link_libraries(cartridge_saves PRIVATE dl)
target_link_options(cartridge_saves PRIVATE "-Wl,-z,max-page-size=16384")
