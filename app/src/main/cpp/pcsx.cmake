# Pinned upstream Android arm64 recipe (jni/Android.mk), synchronous software NEON GPU.
# Async CD/GPU/SPU and dynarec worker threads deliberately disabled for the first integration.
enable_language(ASM)
FetchContent_Declare(pcsx
    URL https://codeload.github.com/libretro/pcsx_rearmed/zip/c8816799b50388e61cfe237fe2cdbb7d8175f20a
    URL_HASH SHA256=5e1b15f17d0e97225d8b2eb29a8412fb0d3b03ff242c5456d055ec2967667a83)
FetchContent_GetProperties(pcsx)
if(NOT pcsx_POPULATED)
    FetchContent_Populate(pcsx)
endif()
set(p ${pcsx_SOURCE_DIR})
set(pcsx_sources)
foreach(name cdriso cdrom cdrom-async cheat database decode_xa mdec misc pad plugins ppf
        psxbios psxcommon psxcounters psxdma psxevents psxhw psxinterpreter psxmem r3000a sio spu gpu gte gte_nf gte_divider)
    list(APPEND pcsx_sources ${p}/libpcsxcore/${name}.c)
endforeach()
foreach(name dma freeze registers spu out nullsnd)
    list(APPEND pcsx_sources ${p}/plugins/dfsound/${name}.c)
endforeach()
foreach(name gpu prim vout_pl)
    list(APPEND pcsx_sources ${p}/plugins/gpulib/${name}.c)
endforeach()
foreach(name bitstream cdrom chd codec_cdfl codec_cdlz codec_cdzl codec_cdzs codec_flac
        codec_huff codec_lzma codec_zlib codec_zstd flac huffman)
    list(APPEND pcsx_sources ${p}/deps/libchdr/src/libchdr_${name}.c)
endforeach()
list(APPEND pcsx_sources
    ${p}/plugins/cdrcimg/cdrcimg.c
    ${p}/frontend/main.c ${p}/frontend/plugin.c ${p}/frontend/cspace.c
    ${p}/libpcsxcore/new_dynarec/new_dynarec.c
    ${p}/libpcsxcore/new_dynarec/pcsxmem.c ${p}/libpcsxcore/new_dynarec/emu_if.c
    ${p}/libpcsxcore/new_dynarec/linkage_arm64.S
    ${p}/libpcsxcore/gte_arm64.S ${p}/libpcsxcore/gte_nf_arm64.S
    ${p}/plugins/gpu_neon/psx_gpu/psx_gpu_simd.c ${p}/plugins/gpu_neon/psx_gpu_if.c
    ${p}/deps/libchdr/deps/lzma-26.02/src/LzmaDec.c
    ${p}/deps/libchdr/deps/lzma-26.02/Asm/arm64/LzmaDecOpt.S
    ${p}/deps/libchdr/deps/zstd-1.5.7/zstddeclib.c)
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/revision.h "#define REV \"c8816799\"\n")
configure_file(pcsx_frontend.c.in ${CMAKE_CURRENT_BINARY_DIR}/cartridge_pcsx_frontend.c @ONLY)
add_library(pcsx_rearmed_libretro SHARED ${pcsx_sources} ${CMAKE_CURRENT_BINARY_DIR}/cartridge_pcsx_frontend.c)
target_include_directories(pcsx_rearmed_libretro PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR} ${p}/include ${p}/deps/crypto ${p}/deps/libretro-common/include
    ${p}/deps/libchdr/include ${p}/deps/libchdr/deps/lzma-26.02/include ${p}/deps/libchdr/deps/zstd-1.5.7)
target_compile_definitions(pcsx_rearmed_libretro PRIVATE HAVE_CHD CHDR_SYSTEM_ZLIB Z7_LZMA_DEC_OPT
    HAVE_LIBRETRO NO_FRONTEND ANDROID REARMED P_HAVE_MMAP=1 P_HAVE_PTHREAD=1
    P_HAVE_POSIX_MEMALIGN=1 NEON_BUILD TEXTURE_CACHE_4BPP TEXTURE_CACHE_8BPP GPU_NEON SIMD_BUILD
    LIGHTREC_CUSTOM_MAP=0 LIGHTREC_ENABLE_THREADED_COMPILER=0 LIGHTREC_ENABLE_DISASSEMBLER=0 LIGHTREC_NO_DEBUG=1)
target_compile_options(pcsx_rearmed_libretro PRIVATE -O2 -ffast-math -fno-strict-aliasing)
target_link_libraries(pcsx_rearmed_libretro PRIVATE z log m)
target_link_options(pcsx_rearmed_libretro PRIVATE -Wl,-z,max-page-size=16384
    "-Wl,--script=${p}/frontend/libretro-extern.T")
