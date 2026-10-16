/*
 *     Cartridge: desktop GLSL turned into GLSL ES through SPIR-V (glslang, SPIRV-Cross), for shader-preset
 *     passes that GLSL ES will not compile as written (ADR 0027).
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#ifndef LIBRETRODROID_GLSLTRANSLATE_H
#define LIBRETRODROID_GLSLTRANSLATE_H

#include <string>
#include <utility>

namespace libretrodroid {

/**
 * One program's two stages, each a complete desktop GLSL source (its own #version first), compiled together
 * by glslang for OpenGL and written out by SPIRV-Cross as GLSL ES 3.00 with high precision throughout. Names
 * of uniforms, samplers and vertex inputs are kept, so the host addresses them as before. Throws
 * std::runtime_error with glslang's or SPIRV-Cross's message. Any thread; needs no GL context.
 */
std::pair<std::string, std::string> translateToEs(const std::string& vertex, const std::string& fragment);

}

#endif //LIBRETRODROID_GLSLTRANSLATE_H
