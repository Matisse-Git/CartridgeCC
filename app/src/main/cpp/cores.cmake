# Cores that need nothing beyond the Libretro ABI. Each is the unchanged, hash-pinned upstream source built
# from its own Android recipe, with Cartridge's shared readiness counters (cartridge_shim.c) around the
# video and audio callbacks. Android packages native libraries whose filename begins with lib.
add_library(cartridge_shim OBJECT cartridge_shim.c)
target_compile_features(cartridge_shim PRIVATE c_std_11)
target_compile_options(cartridge_shim PRIVATE -Wall -Wextra -Werror -O2)
set_target_properties(cartridge_shim PROPERTIES POSITION_INDEPENDENT_CODE ON)

# The same file for a core that draws with OpenGL ES itself: it also keeps the host's copy to the screen
# clear of the core's GL bindings and the host's framebuffer as large as the picture the core draws.
add_library(cartridge_shim_gl OBJECT cartridge_shim.c)
target_compile_features(cartridge_shim_gl PRIVATE c_std_11)
target_compile_definitions(cartridge_shim_gl PRIVATE CARTRIDGE_HW_FRAMES)
target_compile_options(cartridge_shim_gl PRIVATE -Wall -Wextra -Werror -O2)
set_target_properties(cartridge_shim_gl PROPERTIES POSITION_INDEPENDENT_CODE ON)

# Gives a core built in this tree by its own CMake target (Flycast) that second shim.
function(cartridge_hardware_core target)
    target_sources(${target} PRIVATE $<TARGET_OBJECTS:cartridge_shim_gl>)
    target_compile_definitions(${target} PRIVATE
        retro_set_environment=cartridge_core_set_environment
        retro_run=cartridge_core_run
        retro_unload_game=cartridge_core_unload_game
        retro_set_video_refresh=cartridge_core_set_video_refresh
        retro_set_audio_sample_batch=cartridge_core_set_audio_sample_batch)
    target_link_libraries(${target} PRIVATE GLESv3)
    # Android packages native libraries whose filename begins with lib. The driver mode: see cartridge_core.
    set_target_properties(${target} PROPERTIES PREFIX "lib" SUFFIX ".so")
    target_link_options(${target} PRIVATE --driver-mode=g++ -Wl,-z,max-page-size=16384)
endfunction()

# A core that cannot share this tree: its build files assume they are the top level, or it bundles libraries under
# target names another core's also use. It is configured and built as a project of its own with this build's
# toolchain, always optimised, given the same shim through core_wrap.cmake, and its stripped library is placed
# where Gradle collects every other one. cartridge_saves depends on it, so every build of the app builds it.
include(ExternalProject)
function(cartridge_external_core name)
    cmake_parse_arguments(core "" "SOURCE;TARGET;OUTPUT" "ARGUMENTS;SOURCES;DEFINITIONS;LINK;SHIM" ${ARGN})
    set(build ${CMAKE_BINARY_DIR}/${name})
    file(WRITE ${build}-wrap.cmake "set(CARTRIDGE_TARGET ${core_TARGET})
set(CARTRIDGE_CPP \"${CMAKE_CURRENT_FUNCTION_LIST_DIR}\")
set(CARTRIDGE_SOURCES \"${core_SOURCES}\")
set(CARTRIDGE_DEFINITIONS \"${core_DEFINITIONS}\")
set(CARTRIDGE_LINK \"${core_LINK}\")
set(CARTRIDGE_SHIM \"${core_SHIM}\")
include(\"${CMAKE_CURRENT_FUNCTION_LIST_DIR}/core_wrap.cmake\")
")
    ExternalProject_Add(${name}_core
        SOURCE_DIR ${core_SOURCE}
        BINARY_DIR ${build}
        CMAKE_ARGS -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE} -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM} -DANDROID_STL=${ANDROID_STL} -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM} -DCMAKE_BUILD_TYPE=Release
            -DCMAKE_PROJECT_INCLUDE=${build}-wrap.cmake ${core_ARGUMENTS}
        BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target ${core_TARGET}
        # Stripping is repeatable, so an unchanged core leaves the packaged library and its date alone.
        INSTALL_COMMAND ${CMAKE_STRIP} --strip-unneeded -o <BINARY_DIR>/stripped.so <BINARY_DIR>/${core_OUTPUT}
        COMMAND ${CMAKE_COMMAND} -E copy_if_different <BINARY_DIR>/stripped.so
            ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/lib${core_TARGET}.so
        BUILD_ALWAYS ON USES_TERMINAL_CONFIGURE ON USES_TERMINAL_BUILD ON
        BUILD_BYPRODUCTS ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/lib${core_TARGET}.so)
endfunction()

function(cartridge_core name)
    cmake_parse_arguments(core "" "" "SOURCES;INCLUDES;DEFINITIONS;OPTIONS;LIBRARIES" ${ARGN})
    add_library(${name} SHARED ${core_SOURCES} $<TARGET_OBJECTS:cartridge_shim>)
    target_include_directories(${name} PRIVATE ${core_INCLUDES})
    # The renames reach every unit of the core, never the shim, which defines the Libretro names.
    target_compile_definitions(${name} PRIVATE ${core_DEFINITIONS} ANDROID __LIBRETRO__
        retro_set_video_refresh=cartridge_core_set_video_refresh
        retro_set_audio_sample_batch=cartridge_core_set_audio_sample_batch)
    # Gradle's debug variant would otherwise build an emulator without optimisation. Packaged libraries are
    # stripped, and debug information multiplies the compile time of the largest generated interpreters.
    target_compile_options(${name} PRIVATE -O2 -g0 -w ${core_OPTIONS})
    target_link_libraries(${name} PRIVATE log m ${core_LIBRARIES})
    target_link_options(${name} PRIVATE -Wl,-z,max-page-size=16384
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/cartridge_core.T")
    # On Windows a project path with a space makes CMake call the linker driver by its 8.3 name, which hides
    # the "++" clang reads its mode from; a C++ core would then link without the C++ runtime.
    if("${core_SOURCES}" MATCHES "\\.cpp")
        target_link_options(${name} PRIVATE --driver-mode=g++)
    endif()
endfunction()

# ---- Snes9x 1.63 (libretro/jni/Android.mk) ------------------------------------------------------------
FetchContent_Declare(snes9x
    URL https://codeload.github.com/snes9xgit/snes9x/zip/refs/tags/1.63
    URL_HASH SHA256=bec7792b1dec6bbcbf080064322bd196eb5938ab4599047c4725af7343492162)
FetchContent_GetProperties(snes9x)
if(NOT snes9x_POPULATED)
    FetchContent_Populate(snes9x)
endif()
set(s ${snes9x_SOURCE_DIR})
set(snes9x_sources ${s}/filter/snes_ntsc.c ${s}/apu/apu.cpp ${s}/apu/bapu/dsp/sdsp.cpp
    ${s}/apu/bapu/smp/smp.cpp ${s}/apu/bapu/smp/smp_state.cpp ${s}/libretro/libretro.cpp)
foreach(unit bsx c4 c4emu cheats cheats2 clip conffile controls cpu cpuexec cpuops crosshairs dma dsp dsp1
        dsp2 dsp3 dsp4 fxinst fxemu gfx globals memmap obc1 msu1 ppu stream sa1 sa1cpu screenshot sdd1 sdd1emu
        seta seta010 seta011 seta018 snapshot snes9x spc7110 srtc tile tileimpl-n1x1 tileimpl-n2x1
        tileimpl-h2x1 sha256 bml movie fscompat)
    list(APPEND snes9x_sources ${s}/${unit}.cpp)
endforeach()
cartridge_core(snes9x_libretro SOURCES ${snes9x_sources}
    INCLUDES ${s}/libretro ${s}/libretro/libretro-common/include ${s} ${s}/apu ${s}/apu/bapu
    DEFINITIONS HAVE_STRINGS_H RIGHTSHIFT_IS_SAR)

# ---- FCEUmm 7a542dab (jni/Android.mk, NTSC filter on, HD packs off) ------------------------------------
FetchContent_Declare(fceumm
    URL https://codeload.github.com/libretro/libretro-fceumm/zip/7a542dab1e87679921962a9f056186eca425c0c2
    URL_HASH SHA256=a2e8793d24343a3d0cdebdad38184ca8defd61224803d12f5f53b963554d8a8a)
FetchContent_GetProperties(fceumm)
if(NOT fceumm_POPULATED)
    FetchContent_Populate(fceumm)
endif()
set(f ${fceumm_SOURCE_DIR}/src)
set(fc ${f}/drivers/libretro/libretro-common)
file(GLOB fceumm_sources ${f}/boards/*.c ${f}/input/*.c)
foreach(unit cart cheat crc32 fceu-endian fceu-memory fceu fds fds_apu file filter general input md5 nsf
        palette ppu sound state video vsuni ines unif x6502 ntsc/nes_ntsc
        drivers/libretro/libretro drivers/libretro/libretro_dipswitch)
    list(APPEND fceumm_sources ${f}/${unit}.c)
endforeach()
foreach(unit streams/memory_stream compat/compat_posix_string compat/compat_snprintf compat/compat_strcasestr
        compat/compat_strl compat/fopen_utf8 encodings/encoding_utf file/file_path file/file_path_io
        streams/file_stream streams/file_stream_transforms string/stdstring time/rtime vfs/vfs_implementation)
    list(APPEND fceumm_sources ${fc}/${unit}.c)
endforeach()
cartridge_core(fceumm_libretro SOURCES ${fceumm_sources}
    INCLUDES ${f}/drivers/libretro ${fc}/include ${f} ${f}/input ${f}/boards ${f}/ntsc
    DEFINITIONS PATH_MAX=1024 FCEU_VERSION_NUMERIC=9900 FRONTEND_SUPPORTS_RGB565 HAVE_NTSC_FILTER PSS_STYLE=1)

# ---- Genesis Plus GX 393f70cd (libretro/jni/Android.mk) ------------------------------------------------
FetchContent_Declare(gpgx
    URL https://codeload.github.com/libretro/Genesis-Plus-GX/zip/393f70cd64c7334564c19a424c383f53986c13f4
    URL_HASH SHA256=7de279d6f23998438ea82019b501ee822f1873c02ee4e63024d98736eeeb9f2c)
FetchContent_GetProperties(gpgx)
if(NOT gpgx_POPULATED)
    FetchContent_Populate(gpgx)
endif()
set(g ${gpgx_SOURCE_DIR})
set(gd ${g}/libretro/deps)
set(gc ${g}/libretro/libretro-common)
set(gpgx_dirs ${g}/core ${g}/core/z80 ${g}/core/m68k ${g}/core/ntsc ${g}/core/sound ${g}/core/sound/minimp3
    ${g}/core/input_hw ${g}/core/cd_hw ${g}/core/cart_hw ${g}/core/cart_hw/svp)
set(gpgx_sources ${g}/libretro/libretro.c)
foreach(dir ${gpgx_dirs} ${g}/core/sound/tremor)
    file(GLOB units ${dir}/*.c)
    list(APPEND gpgx_sources ${units})
endforeach()
foreach(unit Alloc Bra86 BraIA64 CpuArch Delta LzFind Lzma86Dec LzmaDec LzmaEnc Sort)
    list(APPEND gpgx_sources ${gd}/lzma-19.00/src/${unit}.c)
endforeach()
foreach(unit bitstream cdrom chd flac huffman)
    list(APPEND gpgx_sources ${gd}/libchdr/src/libchdr_${unit}.c)
endforeach()
foreach(unit common/entropy_common common/error_private common/fse_decompress common/zstd_common common/xxhash
        decompress/huf_decompress decompress/zstd_ddict decompress/zstd_decompress decompress/zstd_decompress_block)
    list(APPEND gpgx_sources ${gd}/zstd/lib/${unit}.c)
endforeach()
foreach(unit adler32 crc32 inffast inflate inftrees zutil)
    list(APPEND gpgx_sources ${gd}/zlib-1.2.11/${unit}.c)
endforeach()
foreach(unit streams/file_stream streams/file_stream_transforms compat/fopen_utf8 compat/compat_snprintf
        compat/compat_strl compat/compat_strcasestr compat/compat_posix_string encodings/encoding_utf
        file/file_path file/retro_dirent lists/string_list lists/dir_list memmap/memalign string/stdstring
        vfs/vfs_implementation)
    list(APPEND gpgx_sources ${gc}/${unit}.c)
endforeach()
cartridge_core(genesis_plus_gx_libretro SOURCES ${gpgx_sources}
    INCLUDES ${gpgx_dirs} ${g}/libretro ${gc}/include ${gd}/libchdr/include ${gd}/lzma-19.00/include
        ${gd}/zlib-1.2.11 ${gd}/zstd/lib
    DEFINITIONS "INLINE=static inline" USE_LIBTREMOR USE_16BPP_RENDERING LSB_FIRST BYTE_ORDER=LITTLE_ENDIAN
        FRONTEND_SUPPORTS_RGB565 ALIGN_LONG ALIGN_WORD M68K_OVERCLOCK_SHIFT=20 Z80_OVERCLOCK_SHIFT=20
        HAVE_YM3438_CORE USE_LIBCHDR _7ZIP_ST ZSTD_DISABLE_ASM USE_LIBRETRO_VFS HAVE_OPLL_CORE
        USE_PER_SOUND_CHANNELS_CONFIG MAXROMSIZE=16777216
    OPTIONS -ffast-math -funroll-loops)

# ---- Beetle Saturn ec20ea05 (jni/Android.mk: CD, CHD, Tremor, threaded CD reads, 32-bit colour) ------------
FetchContent_Declare(saturn
    URL https://codeload.github.com/libretro/beetle-saturn-libretro/zip/ec20ea05023f6c863f89d7e5052bd63f406852ff
    URL_HASH SHA256=d7e8869dd7e21ca4b4ff9684cbfdba5ff89b5abd0f2b0235488201a5ccfad036)
FetchContent_GetProperties(saturn)
if(NOT saturn_POPULATED)
    FetchContent_Populate(saturn)
endif()
set(b ${saturn_SOURCE_DIR})
set(bm ${b}/mednafen)
set(bc ${b}/libretro-common)
file(GLOB saturn_sources ${bm}/tremor/*.c)
foreach(unit ss scsp_dsp_jit jitdump db smpc cdb mpeg vdp2 vdp2_render scu_dsp_gen scu_dsp_mvi scu_dsp_jmp scu_dsp_misc
        scu_dsp_jit a64emit x86emit scsp_dsp_jit_x86 scu_dsp_jit_x86 sh7095_jit_x86 sound vdp1 vdp1_line vdp1_sprite
        vdp1_poly ak93c45 stvio cart cart/backup cart/cs1ram cart/bootrom cart/extram cart/rom cart/stv cart/stv_5881
        cart/ar4mp smpc_iodevice)
    list(APPEND saturn_sources ${bm}/ss/${unit}.c)
endforeach()
foreach(unit edc_crc32 galois recover-raw l-ec lec CDUtility audioreader CDAccess CDAccess_CCD CDAccess_Image cdromif
        CDAccess_CHD)
    list(APPEND saturn_sources ${bm}/cdrom/${unit}.c)
endforeach()
# The 68000 interpreter is one 64K-case switch and by far the slowest unit to compile (minutes, and far longer
# with debug information). Upstream's split form does not link outside its Emscripten recipe.
foreach(unit hash/crc hw_cpu/m68k/m68k state cdstream general mempatcher settings video/Deinterlacer hash/md5
        hash/sha256 thread_queue thread_eventcount)
    list(APPEND saturn_sources ${bm}/${unit}.c)
endforeach()
foreach(unit disc input libretro libretro_settings zip_reader)
    list(APPEND saturn_sources ${b}/${unit}.c)
endforeach()
foreach(unit encodings/encoding_deflate encodings/encoding_crc32 formats/chd/rchd formats/7z/r7z_lzma formats/flac/rflac
        encodings/encoding_huffman encodings/encoding_rzstd formats/mpeg1/rmpeg1_ps formats/mpeg1/rmpeg1_video
        formats/mp3/rmp3 streams/file_stream streams/file_stream_transforms file/file_path file/retro_dirent
        lists/dir_list lists/string_list vfs/vfs_implementation compat/compat_posix_string compat/compat_strl
        compat/compat_strcasestr compat/fopen_utf8 encodings/encoding_utf memmap/memalign string/stdstring
        string/rstrtod time/rtime features/features_cpu rthreads/rthreads queues/retro_spsc vfs/vfs_hybrid
        file/file_path_io)
    list(APPEND saturn_sources ${bc}/${unit}.c)
endforeach()
cartridge_core(mednafen_saturn_libretro SOURCES ${saturn_sources}
    INCLUDES ${b} ${bm} ${bm}/include ${bm}/intl ${bm}/hw_sound ${bm}/hw_cpu ${bm}/hw_misc ${bc}/include
    DEFINITIONS _LOW_ACCURACY_ INLINE=inline WANT_THREADING HAVE_THREADS NEED_DEINTERLACER WANT_32BPP NEED_CD HAVE_CHD
        HAVE_RCHD_DEFLATE HAVE_RCHD_LZMA HAVE_RCHD_FLAC HAVE_RCHD_ZSTD NEED_TREMOR NDEBUG STDC_HEADERS
        __STDC_LIMIT_MACROS __STDC_CONSTANT_MACROS
    OPTIONS -funroll-loops -fno-strict-aliasing)
