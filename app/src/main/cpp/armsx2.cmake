# ARMSX2 0076b5c3 (GPL-3.0), the PlayStation 2 core: PCSX2 with ARM64 recompilers for its three processors, built
# unchanged as the Libretro core its own tree carries (pcsx2-libretro). It runs the console's own BIOS, which only
# the user can supply, and draws with OpenGL ES 3.2 on a thread of its own in a context that shares the host's.
# A commit pins the tree; it has no submodules.
FetchContent_Declare(armsx2
    GIT_REPOSITORY https://github.com/ARMSX2/ARMSX2.git
    GIT_TAG 0076b5c389ea7296331ad2d96193c6e8d5d314fa)
FetchContent_GetProperties(armsx2)
if(NOT armsx2_POPULATED)
    FetchContent_Populate(armsx2)
endif()

# The libraries it links, static, in one prefix. This is the engine's own Android recipe
# (.github/workflows/scripts/android/build-dependencies.sh) with every tag it names replaced by the commit that
# tag was, and without the shader compiler, which only its Vulkan renderer needs. -Pps2Libraries=<prefix> names a
# prefix that recipe has already built instead.
if(CARTRIDGE_PS2_LIBRARIES)
    set(ps2_prefix "${CARTRIDGE_PS2_LIBRARIES}")
    add_custom_target(ps2_libraries)
else()
    set(ps2_prefix ${CMAKE_BINARY_DIR}/ps2-libraries)
    function(cartridge_ps2_library name repository commit)
        cmake_parse_arguments(library "" "SUBDIRECTORY" "ARGUMENTS;AFTER" ${ARGN})
        ExternalProject_Add(ps2_${name}
            GIT_REPOSITORY ${repository} GIT_TAG ${commit} GIT_SUBMODULES ""
            SOURCE_SUBDIR "${library_SUBDIRECTORY}"
            CMAKE_ARGS -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE} -DANDROID_ABI=${ANDROID_ABI}
                -DANDROID_PLATFORM=${ANDROID_PLATFORM} -DANDROID_STL=${ANDROID_STL} -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
                -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM} -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON
                -DBUILD_SHARED_LIBS=OFF -DCMAKE_INSTALL_PREFIX=${ps2_prefix} -DCMAKE_PREFIX_PATH=${ps2_prefix}
                -DCMAKE_FIND_ROOT_PATH=${ps2_prefix} ${library_ARGUMENTS}
            DEPENDS ${library_AFTER})
    endfunction()
    cartridge_ps2_library(png https://github.com/pnggroup/libpng 49363adcfaf098748d7a4c8c624ad8c45a8c3a86   # v1.6.51
        ARGUMENTS -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_TESTS=OFF -DPNG_TOOLS=OFF -DPNG_FRAMEWORK=OFF)
    cartridge_ps2_library(jpeg https://github.com/libjpeg-turbo/libjpeg-turbo 9217719d3a58633923b096af4c1d50d304768a64   # 3.1.4.1
        ARGUMENTS -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TURBOJPEG=OFF)
    cartridge_ps2_library(zstd https://github.com/facebook/zstd f8745da6ff1ad1e7bab384bd1f9d742439278e99   # v1.5.7
        SUBDIRECTORY build/cmake
        ARGUMENTS -DZSTD_BUILD_SHARED=OFF -DZSTD_BUILD_STATIC=ON -DZSTD_BUILD_PROGRAMS=OFF -DZSTD_BUILD_TESTS=OFF)
    cartridge_ps2_library(lz4 https://github.com/lz4/lz4 ebb370ca83af193212df4dcbadcc5d87bc0de2f0   # v1.10.0
        SUBDIRECTORY build/cmake ARGUMENTS -DLZ4_BUILD_CLI=OFF -DLZ4_BUILD_LEGACY_LZ4C=OFF)
    cartridge_ps2_library(webp https://github.com/webmproject/libwebp 4fa21912338357f89e4fd51cf2368325b59e9bd9   # v1.6.0
        ARGUMENTS -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF -DWEBP_BUILD_GIF2WEBP=OFF
            -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF
            -DWEBP_BUILD_EXTRAS=OFF)
    # For the input and sound sources the engine compiles against. A Libretro core opens no window of SDL's.
    cartridge_ps2_library(sdl https://github.com/libsdl-org/SDL badbf8da4ee72b3ef599c721ffc9899e8d7c8d90   # release-3.2.26
        ARGUMENTS -DSDL_SHARED=OFF -DSDL_STATIC=ON -DSDL_TESTS=OFF -DSDL_EXAMPLES=OFF)
    cartridge_ps2_library(freetype https://github.com/freetype/freetype 526ec5c47b9ebccc4754c85ac0c0cdf7c85a5e9b   # VER-2-14-1
        ARGUMENTS -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON -DFT_DISABLE_PNG=ON -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON)
    cartridge_ps2_library(plutovg https://github.com/sammycage/plutovg 5695a711dd1cff1f01fa6542f3fe6a15de082c63   # v1.3.2
        ARGUMENTS -DPLUTOVG_BUILD_EXAMPLES=OFF)
    cartridge_ps2_library(plutosvg https://github.com/sammycage/plutosvg 31f7d2675416cd777c8e86220b035364873b2a8b   # v0.0.7
        ARGUMENTS -DPLUTOSVG_ENABLE_FREETYPE=ON -DPLUTOSVG_BUILD_EXAMPLES=OFF AFTER ps2_plutovg ps2_freetype)
    # No TLS: the core talks to no server.
    cartridge_ps2_library(curl https://github.com/curl/curl 75a2079d5c28debb2eaa848ca9430f1fe0d7844c   # curl-8_11_1
        ARGUMENTS -DCURL_ENABLE_SSL=OFF -DBUILD_CURL_EXE=OFF -DCURL_DISABLE_LDAP=ON -DCURL_DISABLE_LDAPS=ON
            -DCURL_USE_LIBPSL=OFF -DBUILD_TESTING=OFF -DBUILD_EXAMPLES=OFF)
    # libpcap is asked for by the engine's configuration and never linked on Android, where the network adapter
    # has a stand-in: its headers, which the adapter's own header includes, and an archive with nothing in it.
    # (Building it would need flex and bison, for a library no Android device can use.)
    ExternalProject_Add(ps2_pcap
        GIT_REPOSITORY https://github.com/the-tcpdump-group/libpcap GIT_TAG bbcbc9174df3298a854daee2b3e666a4b6e5383a   # libpcap-1.10.5
        GIT_SUBMODULES "" CONFIGURE_COMMAND "" BUILD_COMMAND ""
        INSTALL_COMMAND ${CMAKE_COMMAND} -E make_directory ${ps2_prefix}/include/pcap ${ps2_prefix}/lib
        COMMAND ${CMAKE_COMMAND} -E copy <SOURCE_DIR>/pcap.h <SOURCE_DIR>/pcap-bpf.h <SOURCE_DIR>/pcap-namedb.h ${ps2_prefix}/include
        COMMAND ${CMAKE_COMMAND} -E copy_directory <SOURCE_DIR>/pcap ${ps2_prefix}/include/pcap
        COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:cartridge_no_pcap> ${ps2_prefix}/lib/libpcap.a)
    file(WRITE ${CMAKE_BINARY_DIR}/cartridge_no_pcap.c "int cartridge_pcap_is_not_linked_on_android;\n")
    add_library(cartridge_no_pcap STATIC ${CMAKE_BINARY_DIR}/cartridge_no_pcap.c)
    add_dependencies(ps2_pcap cartridge_no_pcap)
    add_custom_target(ps2_libraries DEPENDS ps2_png ps2_jpeg ps2_zstd ps2_lz4 ps2_webp ps2_sdl ps2_freetype ps2_plutovg
        ps2_plutosvg ps2_curl ps2_pcap)
endif()

# The arguments are the ones the engine's own Libretro job for Android gives (.gitlab-ci.yml), and three more:
# no Vulkan renderer, because this host has no Vulkan surface to give it and the core asks for OpenGL ES first
# when the host prefers it; no fetching of a patches archive from a "latest" address while configuring; and
# Android 9 as the floor, where the engine's own recipe puts it (it starts processes with posix_spawn).
# Pages are 4 KiB, as on the Retroid Pocket 5: the engine fixes the size its translators assume when it is built.
cartridge_external_core(ps2
    SOURCE ${armsx2_SOURCE_DIR} TARGET pcsx2-libretro OUTPUT bin/armsx2_libretro.so
    ARGUMENTS -DANDROID_PLATFORM=android-28 -DCMAKE_PREFIX_PATH=${ps2_prefix} -DCMAKE_FIND_ROOT_PATH=${ps2_prefix}
        -DENABLE_LIBRETRO=ON -DENABLE_QT_UI=OFF -DENABLE_SDL_FRONTEND=OFF -DENABLE_GSRUNNER=OFF -DENABLE_TESTS=OFF
        -DENABLE_RECOMPILER_TEST_HOOKS=OFF -DENABLE_SETCAP=OFF -DUSE_BACKTRACE=OFF -DX11_API=OFF -DWAYLAND_API=OFF
        -DDISABLE_ADVANCE_SIMD=TRUE -DARMSX2_ANDROID_HOST_PAGE_SIZE=0x1000 -DCMAKE_DISABLE_PRECOMPILE_HEADERS=ON
        -DUSE_VULKAN=OFF -DARMSX2_EMBED_PATCHES=OFF
    # It draws on a thread of its own: see cartridge_unload in cartridge_shim.c.
    SHIM CARTRIDGE_UNLOAD_FIRST)
add_dependencies(ps2_core ps2_libraries)
