cmake_minimum_required(VERSION 3.13.2)

# now build app's shared lib
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17 -Wall")

add_definitions("-DVFS_FRONTEND -DHAVE_STRL")

# Cartridge: the two submodules of the 0.14.0 tag, fetched at the commits it pins instead of checked out
# beside the source. Hashes pin the complete, unmodified archives; nothing is downloaded into the source tree.
include(FetchContent)
FetchContent_Declare(oboe
    URL https://codeload.github.com/google/oboe/zip/b15f5e39c01a7ada306d959e5129620b145fb8b4
    URL_HASH SHA256=eb2a817017a554f664c5b6262ceef55756097a1e0b9536045de0f33b9868ef6e
)
FetchContent_Declare(libretro_common
    URL https://codeload.github.com/libretro/libretro-common/zip/b0c348ea5543c4d7fb0bc479258aa6988b20c0c9
    URL_HASH SHA256=c8deb75ad240745551611843d7c52ae10f553871a798fcd949bfda6e70acfde0
    # The sources include libretro.h as ../../libretro-common/include/ from this include directory.
    SOURCE_DIR ${CMAKE_BINARY_DIR}/_deps/libretro/libretro-common
)
FetchContent_GetProperties(oboe)
if(NOT oboe_POPULATED)
    FetchContent_Populate(oboe)
endif()
FetchContent_GetProperties(libretro_common)
if(NOT libretro_common_POPULATED)
    FetchContent_Populate(libretro_common)
endif()

# Let's include oboe
set (OBOE_DIR ${oboe_SOURCE_DIR})
add_subdirectory (${OBOE_DIR} oboe)
include_directories (${OBOE_DIR}/include)
include_directories (${OBOE_DIR}/src)

include_directories("${libretro_common_SOURCE_DIR}/include")

set (LIBRETRO_COMMON
        ${libretro_common_SOURCE_DIR}/vfs/vfs_implementation.c
        ${libretro_common_SOURCE_DIR}/string/stdstring.c
        ${libretro_common_SOURCE_DIR}/encodings/encoding_utf.c
        ${libretro_common_SOURCE_DIR}/file/file_path.c
        ${libretro_common_SOURCE_DIR}/time/rtime.c
)

add_library(libretrodroid SHARED
        libretrodroidjni.h
        libretrodroidjni.cpp
        libretrodroid.h
        libretrodroid.cpp
        log.h
        core.h
        core.cpp
        video.h
        video.cpp
        immersivemode.h
        immersivemode.cpp
        videolayout.h
        videolayout.cpp
        renderers/renderer.h
        renderers/renderer.cpp
        renderers/es3/es3utils.h
        renderers/es3/es3utils.cpp
        renderers/es3/framebufferrenderer.h
        renderers/es3/framebufferrenderer.cpp
        renderers/es2/imagerendereres2.h
        renderers/es2/imagerendereres2.cpp
        renderers/es3/imagerendereres3.h
        renderers/es3/imagerendereres3.cpp
        audio.h
        audio.cpp
        resamplers/resampler.h
        resamplers/linearresampler.h
        resamplers/linearresampler.cpp
        resamplers/sincresampler.h
        resamplers/sincresampler.cpp
        fpssync.h
        fpssync.cpp
        environment.h
        environment.cpp
        input.h
        input.cpp
        shadermanager.h
        shadermanager.cpp
        rumble.h
        rumble.cpp
        rumblestate.h
        rumblestate.cpp
        utils/javautils.h
        utils/javautils.cpp
        utils/utils.cpp
        utils/utils.h
        utils/jnistring.h
        utils/jnistring.cpp
        utils/libretrodroidexception.h
        utils/libretrodroidexception.cpp
        utils/rect.h
        utils/rect.cpp
        errorcodes.h
        errorcodes.cpp
        vfs/vfs.h
        vfs/vfs.cpp
        vfs/vfsfile.h
        vfs/vfsfile.cpp
        vfs/fdwrapper.h
        vfs/fdwrapper.cpp
        microphone/microphone.h
        microphone/microphone.cpp
        microphone/microphoneinterface.h
        microphone/microphoneinterface.cpp
        ${LIBRETRO_COMMON}
)

# Support 16k pages for Android 15
target_link_options(libretrodroid PRIVATE "-Wl,-z,max-page-size=16384")
# Cartridge: on Windows a project path with a space makes CMake call the linker driver by its 8.3 name, which
# hides the "++" clang reads its mode from; the library would then link without the C++ runtime.
target_link_options(libretrodroid PRIVATE --driver-mode=g++)

# add lib dependencies
target_link_libraries(libretrodroid
                      android
                      log 
                      EGL
                      oboe
                      GLESv3
)
