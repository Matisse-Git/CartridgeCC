# Runs inside a core that is configured as a project of its own (cartridge_external_core in cores.cmake), as the
# last step of each of its project() calls. Once, from the top level, it defers to the end of the core's own
# CMakeLists what cartridge_hardware_core does for a core built in Cartridge's tree. The core's source and its
# build files stay unchanged.
if(CARTRIDGE_WRAPPED OR NOT CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    return()
endif()
set(CARTRIDGE_WRAPPED ON)

function(cartridge_wrap)
    # An object library of its own: the renames below must reach the core's units and never the shim, which
    # defines the Libretro names. The same file is compiled with warnings as errors in Cartridge's own tree.
    add_library(cartridge_shim_gl OBJECT "${CARTRIDGE_CPP}/cartridge_shim.c")
    set_target_properties(cartridge_shim_gl PROPERTIES POSITION_INDEPENDENT_CODE ON C_STANDARD 11)
    target_compile_definitions(cartridge_shim_gl PRIVATE CARTRIDGE_HW_FRAMES ${CARTRIDGE_SHIM})
    target_compile_options(cartridge_shim_gl PRIVATE -O2 -w)
    target_sources(${CARTRIDGE_TARGET} PRIVATE $<TARGET_OBJECTS:cartridge_shim_gl> ${CARTRIDGE_SOURCES})
    target_compile_definitions(${CARTRIDGE_TARGET} PRIVATE
        retro_set_environment=cartridge_core_set_environment
        retro_run=cartridge_core_run
        retro_unload_game=cartridge_core_unload_game
        retro_set_video_refresh=cartridge_core_set_video_refresh
        retro_set_audio_sample_batch=cartridge_core_set_audio_sample_batch
        ${CARTRIDGE_DEFINITIONS})
    # See cartridge_core in cores.cmake for the driver mode. The shim's OpenGL ES library is named as an option
    # because a core may have used either form of target_link_libraries, and the two cannot be mixed.
    target_link_options(${CARTRIDGE_TARGET} PRIVATE --driver-mode=g++ -Wl,-z,max-page-size=16384 -lGLESv3 ${CARTRIDGE_LINK})
endfunction()
cmake_language(DEFER DIRECTORY "${CMAKE_SOURCE_DIR}" CALL cartridge_wrap)
