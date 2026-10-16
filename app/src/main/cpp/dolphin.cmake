# Dolphin 114977f1, the Libretro project's fork (GPL-2.0-or-later), the GameCube core, unchanged and configured
# as that project builds it for Android, with its OpenGL ES 3 renderer: no Vulkan. A commit pins the tree and,
# through it, each submodule. Its build files need CMake 3.25, which is why Gradle names 3.31.6.
FetchContent_Declare(dolphin
    GIT_REPOSITORY https://github.com/libretro/dolphin.git
    GIT_TAG 114977f115757b1d5506da93c825188233813ec1
    GIT_SUBMODULES "")
FetchContent_GetProperties(dolphin)
if(NOT dolphin_POPULATED)
    FetchContent_Populate(dolphin)
endif()
# The libraries its libretro target compiles, out of the forty-odd it carries. One of them keeps reference
# files whose names pass Windows' path limit, hence the long-path setting for this one command.
if(NOT EXISTS ${dolphin_SOURCE_DIR}/Externals/fmt/fmt/CMakeLists.txt)
    find_package(Git REQUIRED)
    set(dolphin_externals)
    foreach(external spirv_cross/SPIRV-Cross zlib-ng/zlib-ng libspng/libspng VulkanMemoryAllocator implot/implot
            libadrenotools curl/curl fmt/fmt lz4/lz4 xxhash/xxHash enet/enet tinygltf/tinygltf minizip-ng/minizip-ng
            Vulkan-Headers watcher/watcher SFML/SFML zstd/zstd glslang/glslang pugixml/pugixml
            cpp-optparse/cpp-optparse bzip2/bzip2 imgui/imgui mGBA/mgba)
        list(APPEND dolphin_externals Externals/${external})
    endforeach()
    execute_process(COMMAND ${GIT_EXECUTABLE} -c core.longpaths=true submodule update --init --depth 1 ${dolphin_externals}
        WORKING_DIRECTORY ${dolphin_SOURCE_DIR} RESULT_VARIABLE dolphin_nested)
    if(NOT dolphin_nested EQUAL 0)
        message(FATAL_ERROR "Dolphin's bundled libraries could not be fetched")
    endif()
endif()

cartridge_external_core(gc
    SOURCE ${dolphin_SOURCE_DIR} TARGET dolphin_libretro OUTPUT dolphin_libretro_android.so
    ARGUMENTS -DLIBRETRO=ON -DENABLE_VULKAN=OFF)

# The data files Dolphin reads at run time: its replacement fonts and sound-processor tables, its per-game
# compatibility settings and the shader its presentation needs. They are packed into one asset beside the
# core, which Cartridge unpacks into the private system folder (CoreData).
if(CARTRIDGE_ASSETS)
    set(dolphin_data ${CARTRIDGE_ASSETS}/cores/dolphin-sys.zip)
    add_custom_command(OUTPUT ${dolphin_data}
        COMMAND ${CMAKE_COMMAND} -E make_directory ${CARTRIDGE_ASSETS}/cores
        COMMAND ${CMAKE_COMMAND} -E tar cf ${dolphin_data} --format=zip GC GameSettings Shaders codehandler.bin
        WORKING_DIRECTORY ${dolphin_SOURCE_DIR}/Data/Sys VERBATIM)
    add_custom_target(gc_data DEPENDS ${dolphin_data})
    add_dependencies(gc_core gc_data)
endif()
