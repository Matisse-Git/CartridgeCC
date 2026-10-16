/*
 *     Cartridge: draws a RetroArch GLSL preset (.glslp) as the host's final pass. Added to LibretroDroid
 *     0.14.0 for Project Cartridge (ADR 0027). See presetchain.h.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#include "presetchain.h"

#include <GLES3/gl32.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <stdexcept>

#include "log.h"
#include "glsltranslate.h"

namespace libretrodroid {

namespace {

// The core's frame copied upright into the chain's own texture, alpha made opaque: the first row at the top
// whichever way the core delivered it, so every later pass sees one orientation. RetroArch has no such pass;
// its frame arrives in one orientation already.
const char* const NORMALIZE_SOURCE = R"(#version 130
#if defined(VERTEX)
in vec2 position;
out vec2 uv;
uniform float flip;
void main() {
    uv = vec2((position.x + 1.0) * 0.5, abs(flip - (position.y + 1.0) * 0.5));
    gl_Position = vec4(position, 0.0, 1.0);
}
#elif defined(FRAGMENT)
precision highp float;
uniform sampler2D Texture;
in vec2 uv;
out vec4 color;
void main() { color = vec4(texture(Texture, uv).rgb, 1.0); }
#endif
)";

// RetroArch's own last step when a preset's last pass has a size of its own: the picture to the screen.
const char* const STOCK_SOURCE = R"(#version 130
#if defined(VERTEX)
in vec4 VertexCoord;
in vec2 TexCoord;
out vec2 uv;
uniform mat4 MVPMatrix;
void main() { uv = TexCoord; gl_Position = MVPMatrix * VertexCoord; }
#elif defined(FRAGMENT)
precision highp float;
uniform sampler2D Texture;
in vec2 uv;
out vec4 color;
void main() { color = texture(Texture, uv); }
#endif
)";

// The unit quad every pass draws, as a triangle strip: VertexCoord, then the coordinates every *TexCoord gets.
// Every texture here is exactly the size of its picture, so RetroArch's InputSize / TextureSize ratio is 1.
const GLfloat QUAD_VERTICES[16] = { 0, 0, 0, 1,  1, 0, 0, 1,  0, 1, 0, 1,  1, 1, 0, 1 };
const GLfloat QUAD_COORDINATES[8] = { 0, 0,  1, 0,  0, 1,  1, 1 };
const GLfloat QUAD_COLORS[16] = { 1, 1, 1, 1,  1, 1, 1, 1,  1, 1, 1, 1,  1, 1, 1, 1 };
const GLfloat NORMALIZE_POSITIONS[8] = { -1, -1,  1, -1,  -1, 1,  1, 1 };
const std::array<float, 16> FRAMEBUFFER_MVP = { 2, 0, 0, 0,  0, 2, 0, 0,  0, 0, 1, 0,  -1, -1, 0, 1 };

using Parameters = std::vector<std::pair<std::string, float>>;

/**
 * How a pass is compiled. The first way is RetroArch's own. The library's shaders were written for desktop GL,
 * where more is allowed than in OpenGL ES, so when that fails the others are tried, each a way the shader's own
 * source provides for or a stricter GLSL that keeps its meaning:
 *  - parameters as the shader's constants, at the preset's values (its `#else` branch for frontends without
 *    PARAMETER_UNIFORM): GLSL ES allows no uniform in a global initializer, which crt-royale and many others
 *    have. The preset's parameters stay what they are; Cartridge offers no tuning of them (ADR 0027).
 *  - a source written for GLSL 1.10 or 1.20 compiled as GLSL ES 3.00 rather than 1.00, which lacks line
 *    continuation, `%`, array constructors and textureLod; the shaders' COMPAT macros follow __VERSION__.
 *  - a default float precision in the fragment stage, which desktop GL does not need.
 * Every way also enables the derivative functions GLSL ES 1.00 keeps behind an extension.
 */
struct Variant {
    bool parameterUniforms;
    bool newerGlsl;
    bool defaultPrecision;
    bool levelAsFloat;
};
const Variant VARIANTS[] = {
    { true, false, false, false }, { false, false, true, false }, { true, true, true, false }, { false, true, true, false } };
// With the rewritten source: desktop GL's conversion of an int texture level, which GLSL ES does not make.
const Variant COMPAT_VARIANTS[] = {
    { true, false, true, true }, { false, false, true, true }, { true, true, true, true }, { false, true, true, true } };

/** The shader's own `#define NAME value` for each parameter, set to the preset's value. */
std::string patchDefines(const std::string& body, const Parameters& parameters) {
    std::string result;
    result.reserve(body.size());
    size_t start = 0;
    while (start < body.size()) {
        size_t end = body.find('\n', start);
        if (end == std::string::npos) end = body.size();
        std::string line = body.substr(start, end - start);
        size_t first = line.find_first_not_of(" \t");
        if (first != std::string::npos && line.compare(first, 7, "#define") == 0) {
            size_t nameStart = line.find_first_not_of(" \t", first + 7);
            size_t nameEnd = nameStart == std::string::npos ? std::string::npos : line.find_first_of(" \t(", nameStart);
            if (nameEnd != std::string::npos && line[nameEnd] != '(') {
                std::string name = line.substr(nameStart, nameEnd - nameStart);
                for (const auto& parameter : parameters) {
                    if (parameter.first != name) continue;
                    char value[32];
                    snprintf(value, sizeof(value), "%.9g", parameter.second);
                    line = line.substr(0, nameEnd) + " " + value;
                    break;
                }
            }
        }
        result += line;
        if (end < body.size()) result += '\n';
        start = end + 1;
    }
    return result;
}

/**
 * Every `mediump` and `lowp` read as `highp`. The library's shaders were written for desktop GL, which ignores
 * precision qualifiers, and some depend on it: crt-guest-dr-venom keeps colours raised to the tenth power,
 * which a 16-bit mediump value (what Adreno gives) flushes to black.
 */
std::string fullPrecision(const std::string& body) {
    std::string result;
    result.reserve(body.size());
    auto word = [](char c) { return std::isalnum((unsigned char) c) || c == '_'; };
    size_t i = 0;
    while (i < body.size()) {
        bool boundary = i == 0 || !word(body[i - 1]);
        if (boundary && body.compare(i, 7, "mediump") == 0 && (i + 7 >= body.size() || !word(body[i + 7]))) {
            result += "highp";
            i += 7;
        } else if (boundary && body.compare(i, 4, "lowp") == 0 && (i + 4 >= body.size() || !word(body[i + 4]))) {
            result += "highp";
            i += 4;
        } else {
            result += body[i++];
        }
    }
    return result;
}

/** shader_glsl.c, gl_glsl_compile_shader: what a source's own #version becomes on OpenGL ES 3, then the defines. */
std::string prepare(const std::string& program, const char* stage, const std::string& aliases, const Variant& variant,
                    const Parameters& parameters) {
    std::string version;
    std::string body = program;
    size_t at = program.find("#version");
    if (at != std::string::npos) {
        const char* start = program.c_str() + at + 8;
        char* end = nullptr;
        unsigned long number = strtoul(start, &end, 10);
        body = std::string(end);
        if (number >= 130 && number < 330) version = "#version 300 es\n";
        else if (number == 330) version = "#version 310 es\n";
        else if (number > 330) version = "#version 320 es\n";
        else version = variant.newerGlsl ? "#version 300 es\n" : "#version 100\n";
    } else if (variant.newerGlsl) {
        version = "#version 300 es\n";
    }
    bool legacy = version.empty() || version == "#version 100\n";
    std::string prologue = version;
    if (legacy && (body.find("fwidth") != std::string::npos || body.find("dFdx") != std::string::npos || body.find("dFdy") != std::string::npos))
        prologue += "#extension GL_OES_standard_derivatives : enable\n";
    prologue += std::string("#define ") + stage + "\n";
    if (variant.parameterUniforms) prologue += "#define PARAMETER_UNIFORM\n";
    prologue += "#define _HAS_ORIGINALASPECT_UNIFORMS\n#define _HAS_FRAMETIME_UNIFORMS\n";
    if (variant.levelAsFloat && body.find("#define textureLod") == std::string::npos)
        prologue += "#define textureLod(s, c, l) textureLod(s, c, float(l))\n";
    if (variant.defaultPrecision && strcmp(stage, "FRAGMENT") == 0) prologue += "precision highp float;\n";
    return prologue + aliases + fullPrecision(variant.parameterUniforms ? body : patchDefines(body, parameters));
}

std::string firstLines(const std::string& log) {
    std::string result;
    size_t start = 0;
    for (int lines = 0; lines < 3 && start < log.size(); ++lines) {
        size_t end = log.find('\n', start);
        if (end == std::string::npos) end = log.size();
        std::string line = log.substr(start, end - start);
        if (!line.empty()) result += (result.empty() ? "" : " / ") + line;
        start = end + 1;
    }
    return result.size() > 400 ? result.substr(0, 400) : result;
}

GLuint compile(GLenum type, const std::string& source, const std::string& name) {
    GLuint shader = glCreateShader(type);
    const char* text = source.c_str();
    glShaderSource(shader, 1, &text, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled == GL_TRUE) return shader;
    GLint length = 0;
    glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
    std::string log(std::max(length, 1), '\0');
    glGetShaderInfoLog(shader, length, nullptr, log.data());
    glDeleteShader(shader);
    throw std::runtime_error(name + (type == GL_VERTEX_SHADER ? ": vertex stage: " : ": fragment stage: ") + log);
}

GLuint linkTexts(const std::string& vertexText, const std::string& fragmentText, const std::string& name) {
    GLuint vertex = compile(GL_VERTEX_SHADER, vertexText, name);
    GLuint fragment;
    try {
        fragment = compile(GL_FRAGMENT_SHADER, fragmentText, name);
    } catch (...) {
        glDeleteShader(vertex);
        throw;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked == GL_TRUE) return program;
    GLint length = 0;
    glGetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
    std::string log(std::max(length, 1), '\0');
    glGetProgramInfoLog(program, length, nullptr, log.data());
    glDeleteProgram(program);
    throw std::runtime_error(name + ": link: " + log);
}

GLuint linkVariant(const std::string& source, const std::string& aliases, const std::string& name, const Variant& variant,
                   const Parameters& parameters) {
    return linkTexts(prepare(source, "VERTEX", aliases, variant, parameters), prepare(source, "FRAGMENT", aliases, variant, parameters), name);
}

/**
 * The pass as desktop GLSL 3.30, the oldest glslang compiles for OpenGL: RetroArch's defines, the source after
 * its own #version. The shaders choose their syntax by __VERSION__, so 1.30 sources read the same.
 */
std::string desktop(const std::string& program, const char* stage, const std::string& aliases) {
    std::string body = program;
    size_t at = program.find("#version");
    if (at != std::string::npos) {
        char* end = nullptr;
        strtoul(program.c_str() + at + 8, &end, 10);
        body = std::string(end);
    }
    // GLSL 3.30 has no line continuation (4.20 does): continued lines are joined, as a preprocessor would.
    std::string joined;
    joined.reserve(body.size());
    for (size_t i = 0; i < body.size(); ++i) {
        if (body[i] == '\\' && i + 1 < body.size() && body[i + 1] == '\n') { ++i; continue; }
        if (body[i] == '\\' && i + 2 < body.size() && body[i + 1] == '\r' && body[i + 2] == '\n') { i += 2; continue; }
        joined += body[i];
    }
    return std::string("#version 330\n#define ") + stage + "\n#define PARAMETER_UNIFORM\n"
        "#define _HAS_ORIGINALASPECT_UNIFORMS\n#define _HAS_FRAMETIME_UNIFORMS\n" + aliases + fullPrecision(joined);
}

/** Every way's whole log, for whoever finds out why a preset is refused; logcat cuts long lines. */
void logRefusal(const std::string& name, const char* way, const std::string& log) {
    for (size_t at = 0; at < log.size(); at += 900)
        LOGI("Shader preset %s not built %s [%zu]: %s", name.c_str(), way, at / 900, log.substr(at, 900).c_str());
}

const char* wayOf(const Variant& variant) {
    if (variant.parameterUniforms) return variant.newerGlsl ? "with parameters as uniforms, as GLSL ES 3.00" : "with parameters as uniforms";
    return variant.newerGlsl ? "with parameters as constants, as GLSL ES 3.00" : "with parameters as constants";
}

/**
 * The source as written, RetroArch's way first and then the variants above; then what desktop GL would have
 * compiled, translated to GLSL ES by glslang and SPIRV-Cross, which makes implicit conversions, non-constant
 * global initialisers and the rest of what desktop GLSL allows explicit without changing what it computes;
 * last the source rewritten by GlslCompat, for what the translator cannot read. When no way works, RetroArch's
 * own error is the reason given.
 */
GLuint link(const std::string& source, const std::string& aliases, const std::string& name, const Parameters& parameters,
            const std::string& compatSource = std::string()) {
    std::string firstFailure;
    for (const auto& variant : VARIANTS) {
        try {
            GLuint program = linkVariant(source, aliases, name, variant, parameters);
            if (&variant != &VARIANTS[0]) LOGI("Shader preset %s built %s", name.c_str(), wayOf(variant));
            return program;
        } catch (const std::runtime_error& failure) {
            if (firstFailure.empty()) firstFailure = failure.what();
            logRefusal(name, wayOf(variant), failure.what());
        }
    }
    try {
        auto texts = translateToEs(desktop(source, "VERTEX", aliases), desktop(source, "FRAGMENT", aliases));
        GLuint program = linkTexts(texts.first, texts.second, name);
        LOGI("Shader preset %s built translated", name.c_str());
        return program;
    } catch (const std::runtime_error& failure) {
        logRefusal(name, "translated", failure.what());
    }
    if (!compatSource.empty()) {
        for (const auto& variant : COMPAT_VARIANTS) {
            try {
                GLuint program = linkVariant(compatSource, aliases, name, variant, parameters);
                LOGI("Shader preset %s built rewritten, %s", name.c_str(), wayOf(variant));
                return program;
            } catch (const std::runtime_error& failure) {
                logRefusal(name, "rewritten", failure.what());
            }
        }
    }
    throw std::runtime_error(firstLines(firstFailure));
}

bool hasExtension(const char* name) {
    const char* extensions = (const char*) glGetString(GL_EXTENSIONS);
    if (extensions == nullptr) return false;
    size_t length = strlen(name);
    for (const char* at = strstr(extensions, name); at != nullptr; at = strstr(at + length, name)) {
        if ((at == extensions || at[-1] == ' ') && (at[length] == ' ' || at[length] == '\0')) return true;
    }
    return false;
}

/** Whether the program's active uniform [name] is a float, as a source rewritten for GLSL ES declares the counters. */
bool isFloatUniform(GLuint program, const char* name) {
    GLint count = 0;
    glGetProgramiv(program, GL_ACTIVE_UNIFORMS, &count);
    char found[64];
    for (GLint i = 0; i < count; ++i) {
        GLsizei length = 0;
        GLint size = 0;
        GLenum type = 0;
        glGetActiveUniform(program, (GLuint) i, sizeof(found), &length, &size, &type, found);
        if (strcmp(found, name) == 0) return type == GL_FLOAT;
    }
    return false;
}

std::string hex(unsigned value) {
    char buffer[16];
    snprintf(buffer, sizeof(buffer), "0x%X", value);
    return buffer;
}

unsigned levelsFor(unsigned width, unsigned height) {
    return (unsigned) std::floor(std::log2((double) std::max(width, height))) + 1;
}

}

PresetChain::PresetChain(std::shared_ptr<const PresetSpec> presetSpec) : spec(std::move(presetSpec)) {
    if (spec == nullptr || spec->passes.empty()) throw std::runtime_error("The preset has no passes");
    GLint major = 0, minor = 0;
    glGetIntegerv(GL_MAJOR_VERSION, &major);
    glGetIntegerv(GL_MINOR_VERSION, &minor);
    if (major < 3) throw std::runtime_error("Shader presets need OpenGL ES 3");
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);
    glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &maxUnits);
    borderClamp = major > 3 || minor >= 2 || hasExtension("GL_EXT_texture_border_clamp") || hasExtension("GL_OES_texture_border_clamp");
    bool floatColor = hasExtension("GL_EXT_color_buffer_float");
    floatFormat = floatColor && hasExtension("GL_OES_texture_float_linear") ? GL_RGBA32F
        : floatColor || hasExtension("GL_EXT_color_buffer_half_float") ? GL_RGBA16F : GL_RGBA8;
    LOGI("Shader preset: OpenGL ES %d.%d, %d texture units, float framebuffers %s, border clamp %s", major, minor, maxUnits,
         floatFormat == GL_RGBA32F ? "32-bit" : floatFormat == GL_RGBA16F ? "16-bit" : "none", borderClamp ? "yes" : "no");

    try {
        uploadTextures();
        stock.id = link(STOCK_SOURCE, "", "stock pass", {});
        normalize.id = link(NORMALIZE_SOURCE, "", "copy pass", {});
        for (Program* program : { &stock, &normalize }) {
            program->mvp = glGetUniformLocation(program->id, "MVPMatrix");
            program->vertexCoord = glGetAttribLocation(program->id, "VertexCoord");
            program->texCoord = glGetAttribLocation(program->id, "TexCoord");
            program->texture = glGetUniformLocation(program->id, "Texture");
            glUseProgram(program->id);
            glUniform1i(program->texture, 0);
        }
        normalizeFlip = glGetUniformLocation(normalize.id, "flip");
        normalizePosition = glGetAttribLocation(normalize.id, "position");
        for (size_t i = 0; i < spec->passes.size(); ++i) buildProgram(i);
        glUseProgram(0);
        const auto& last = spec->passes.back();
        lastPassScaled = last.scaleTypeX != PresetSpec::NONE || last.scaleTypeY != PresetSpec::NONE;
    } catch (...) {
        glUseProgram(0);
        for (auto& program : programs) glDeleteProgram(program.id);
        if (stock.id) glDeleteProgram(stock.id);
        if (normalize.id) glDeleteProgram(normalize.id);
        for (auto& lut : luts) glDeleteTextures(1, &lut.texture);
        throw;
    }
}

PresetChain::~PresetChain() {
    releaseTargets();
    for (auto& program : programs) glDeleteProgram(program.id);
    glDeleteProgram(stock.id);
    glDeleteProgram(normalize.id);
    for (auto& lut : luts) glDeleteTextures(1, &lut.texture);
}

void PresetChain::uploadTextures() {
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
    glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
    for (const auto& texture : spec->textures) {
        if (texture.width == 0 || texture.height == 0 || texture.rgba.size() != (size_t) texture.width * texture.height * 4)
            throw std::runtime_error("Texture " + texture.name + " is not a usable image");
        if ((GLint) std::max(texture.width, texture.height) > maxTextureSize)
            throw std::runtime_error("Texture " + texture.name + " is larger than this GPU allows");
        Target lut;
        lut.width = texture.width;
        lut.height = texture.height;
        glGenTextures(1, &lut.texture);
        luts.push_back(lut);
        glBindTexture(GL_TEXTURE_2D, lut.texture);
        glTexStorage2D(GL_TEXTURE_2D, texture.mipmap ? levelsFor(texture.width, texture.height) : 1, GL_RGBA8, texture.width, texture.height);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, texture.width, texture.height, GL_RGBA, GL_UNSIGNED_BYTE, texture.rgba.data());
        if (texture.mipmap) glGenerateMipmap(GL_TEXTURE_2D);
        setSampling(lut.texture, texture.linear ? 1 : 0, texture.wrap, texture.mipmap);
    }
    glBindTexture(GL_TEXTURE_2D, 0);
}

void PresetChain::buildProgram(size_t index) {
    const auto& pass = spec->passes[index];
    std::string aliases;
    for (const auto& other : spec->passes) {
        if (!other.alias.empty()) aliases += "#define " + other.alias + "_ALIAS\n";
    }
    Program program;
    program.id = link(pass.source, aliases, "Pass " + std::to_string(index) + " (" + pass.name + ")", spec->parameters, pass.compatSource);
    programs.push_back(program);
    Program& p = programs.back();
    GLuint id = p.id;
    p.frameCountMod = pass.frameCountMod;
    p.mvp = glGetUniformLocation(id, "MVPMatrix");
    p.vertexCoord = glGetAttribLocation(id, "VertexCoord");
    p.texCoord = glGetAttribLocation(id, "TexCoord");
    p.color = glGetAttribLocation(id, "Color");
    p.lutTexCoord = glGetAttribLocation(id, "LUTTexCoord");
    p.texture = glGetUniformLocation(id, "Texture");
    p.inputSize = glGetUniformLocation(id, "InputSize");
    p.outputSize = glGetUniformLocation(id, "OutputSize");
    p.textureSize = glGetUniformLocation(id, "TextureSize");
    p.finalViewportSize = glGetUniformLocation(id, "FinalViewportSize");
    p.frameCount = glGetUniformLocation(id, "FrameCount");
    p.frameDirection = glGetUniformLocation(id, "FrameDirection");
    p.floatCounters = isFloatUniform(id, "FrameCount") || isFloatUniform(id, "FrameDirection");
    p.frameTimeDelta = glGetUniformLocation(id, "FrameTimeDelta");
    p.originalFps = glGetUniformLocation(id, "OriginalFPS");
    p.rotation = glGetUniformLocation(id, "Rotation");
    p.originalAspect = glGetUniformLocation(id, "OriginalAspect");
    p.originalAspectRotated = glGetUniformLocation(id, "OriginalAspectRotated");

    auto addFrame = [&](Ref ref, unsigned frameIndex, const std::string& base) {
        Frame frame {
            ref, frameIndex,
            glGetUniformLocation(id, (base + "Texture").c_str()),
            glGetUniformLocation(id, (base + "TextureSize").c_str()),
            glGetUniformLocation(id, (base + "InputSize").c_str()),
            glGetAttribLocation(id, (base + "TexCoord").c_str()),
        };
        if (frame.texture >= 0 || frame.textureSize >= 0 || frame.inputSize >= 0 || frame.texCoord >= 0) p.frames.push_back(frame);
    };
    // gl_glsl_find_uniforms, in its order; RetroArch's pass numbers start at 1 with pass 0 its stock pass.
    for (unsigned i = 0; i < spec->textures.size(); ++i) {
        GLint location = glGetUniformLocation(id, spec->textures[i].name.c_str());
        if (location >= 0) p.frames.push_back(Frame { Ref::Lut, i, location, -1, -1, -1 });
    }
    unsigned number = (unsigned) index + 1;
    addFrame(Ref::Original, 0, "Orig");
    if (number > 1) addFrame(Ref::Original, 0, "PassPrev" + std::to_string(number));
    addFrame(Ref::Feedback, 0, "Feedback");
    for (unsigned i = 0; i + 1 < number; ++i) {
        addFrame(Ref::Pass, i, "Pass" + std::to_string(i + 1));
        addFrame(Ref::Pass, i, "PassPrev" + std::to_string(number - (i + 1)));
        if (!spec->passes[i].alias.empty()) addFrame(Ref::Pass, i, spec->passes[i].alias);
    }
    addFrame(Ref::History, 1, "Prev");
    for (unsigned i = 1; i < 7; ++i) addFrame(Ref::History, i + 1, "Prev" + std::to_string(i));

    glUseProgram(id);
    if (p.texture >= 0) glUniform1i(p.texture, 0);
    GLint unit = 1;
    for (auto& frame : p.frames) {
        if (frame.ref == Ref::History) historyDepth = std::max(historyDepth, frame.index);
        if (frame.ref == Ref::Feedback && frame.texture >= 0) usesFeedback = true;
        if (frame.texture < 0) continue;
        if (unit >= maxUnits) throw std::runtime_error("Pass " + std::to_string(index) + " needs more textures than this GPU can read at once");
        frame.unit = unit++;
        glUniform1i(frame.texture, frame.unit);
    }
    unsigned found = 0;
    for (const auto& parameter : spec->parameters) {
        GLint location = glGetUniformLocation(id, parameter.first.c_str());
        if (location >= 0) { glUniform1f(location, parameter.second); found++; }
    }
    LOGI("Shader preset pass %zu: %u of %zu parameters are uniforms here, %zu frames", index, found, spec->parameters.size(), p.frames.size());
}

GLenum PresetChain::wrapMode(int wrap) const {
    switch (wrap) {
        case PresetSpec::EDGE: return GL_CLAMP_TO_EDGE;
        case PresetSpec::REPEAT: return GL_REPEAT;
        case PresetSpec::MIRRORED_REPEAT: return GL_MIRRORED_REPEAT;
        default: return borderClamp ? GL_CLAMP_TO_BORDER : GL_CLAMP_TO_EDGE;
    }
}

/** A filter the preset leaves open is nearest, as RetroArch's default video_smooth = false makes it. */
void PresetChain::setSampling(GLuint texture, int filter, int wrap, bool mipmapped) {
    bool linear = filter == 1;
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
        mipmapped ? (linear ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_NEAREST) : (linear ? GL_LINEAR : GL_NEAREST));
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, (GLint) wrapMode(wrap));
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, (GLint) wrapMode(wrap));
}

PresetChain::Target PresetChain::createTarget(unsigned width, unsigned height, GLenum format, bool mipmapped, int filter, int wrap) {
    Target target;
    target.width = width;
    target.height = height;
    glGenTextures(1, &target.texture);
    glBindTexture(GL_TEXTURE_2D, target.texture);
    glTexStorage2D(GL_TEXTURE_2D, mipmapped ? levelsFor(width, height) : 1, format, width, height);
    setSampling(target.texture, filter, wrap, mipmapped);
    glGenFramebuffers(1, &target.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target.texture, 0);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        glDeleteFramebuffers(1, &target.framebuffer);
        glDeleteTextures(1, &target.texture);
        throw std::runtime_error("A " + std::to_string(width) + "x" + std::to_string(height) + " framebuffer of format "
            + hex(format) + " cannot be drawn to here (status " + hex(status) + ")");
    }
    glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
    glClear(GL_COLOR_BUFFER_BIT);
    return target;
}

void PresetChain::releaseTargets() {
    auto release = [](Target& target) {
        if (target.framebuffer) glDeleteFramebuffers(1, &target.framebuffer);
        if (target.texture) glDeleteTextures(1, &target.texture);
        target = Target();
    };
    for (auto& target : history) release(target);
    for (auto& target : passes) release(target);
    release(feedback);
    history.clear();
    passes.clear();
    allocatedWidth = allocatedHeight = allocatedViewportWidth = allocatedViewportHeight = 0;
}

/** gl2_renderchain_recompute_pass_sizes and gl2_create_fbo_texture: each pass's picture and how the next one reads it. */
void PresetChain::allocate(unsigned width, unsigned height, unsigned viewportWidth, unsigned viewportHeight) {
    releaseTargets();
    const auto& first = spec->passes.front();
    for (unsigned i = 0; i <= historyDepth; ++i)
        history.push_back(createTarget(width, height, GL_RGBA8, first.mipmapInput, first.filter, first.wrap));
    current = 0;

    size_t count = spec->passes.size();
    size_t framebuffers = lastPassScaled ? count : count - 1;
    unsigned sourceWidth = width, sourceHeight = height;
    auto clampSize = [this](double size) { return (unsigned) std::min<double>(std::max<double>(size, 1.0), maxTextureSize); };
    for (size_t i = 0; i < framebuffers; ++i) {
        const auto& pass = spec->passes[i];
        auto axis = [&](int type, float scale, unsigned source, unsigned viewport) -> unsigned {
            switch (type) {
                case PresetSpec::VIEWPORT: return clampSize((unsigned) (scale * viewport));
                case PresetSpec::ABSOLUTE: return clampSize((unsigned) scale);
                case PresetSpec::SOURCE: return clampSize((unsigned) (scale * source));
                default: return clampSize(source);
            }
        };
        unsigned passWidth = axis(pass.scaleTypeX, pass.scaleX, sourceWidth, viewportWidth);
        unsigned passHeight = axis(pass.scaleTypeY, pass.scaleY, sourceHeight, viewportHeight);
        GLenum format = pass.floatFramebuffer ? floatFormat : (pass.srgbFramebuffer ? GL_SRGB8_ALPHA8 : GL_RGBA8);
        bool hasNext = i + 1 < count;
        const PresetSpec::Pass* next = hasNext ? &spec->passes[i + 1] : nullptr;
        passes.push_back(createTarget(passWidth, passHeight, format, next != nullptr && next->mipmapInput,
            next != nullptr ? next->filter : 0, next != nullptr ? next->wrap : PresetSpec::EDGE));
        if (usesFeedback && (int) i == spec->feedbackPass) {
            feedback = createTarget(passWidth, passHeight, format, next != nullptr && next->mipmapInput,
                next != nullptr ? next->filter : 0, next != nullptr ? next->wrap : PresetSpec::EDGE);
        }
        sourceWidth = passWidth;
        sourceHeight = passHeight;
    }
    glBindTexture(GL_TEXTURE_2D, 0);
    allocatedWidth = width;
    allocatedHeight = height;
    allocatedViewportWidth = viewportWidth;
    allocatedViewportHeight = viewportHeight;
}

const PresetChain::Target& PresetChain::frameTarget(const Frame& frame) const {
    switch (frame.ref) {
        case Ref::History: return history[(current + history.size() - frame.index % history.size()) % history.size()];
        case Ref::Pass: return passes[frame.index];
        // A feedback pass that draws to the screen has nothing kept; RetroArch then shows the input instead.
        case Ref::Feedback: return feedback.texture ? feedback : history[current];
        case Ref::Lut: return luts[frame.index];
        default: return history[current];
    }
}

void PresetChain::drawPass(const Program& p, const Target& input, unsigned outputWidth, unsigned outputHeight,
                           const std::array<float, 16>& mvp, unsigned viewportWidth, unsigned viewportHeight) {
    glUseProgram(p.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, input.texture);
    for (const auto& frame : p.frames) {
        const Target& target = frameTarget(frame);
        if (frame.unit >= 0) {
            glActiveTexture(GL_TEXTURE0 + frame.unit);
            glBindTexture(GL_TEXTURE_2D, target.texture);
        }
        if (frame.textureSize >= 0) glUniform2f(frame.textureSize, (float) target.width, (float) target.height);
        if (frame.inputSize >= 0) glUniform2f(frame.inputSize, (float) target.width, (float) target.height);
    }
    glActiveTexture(GL_TEXTURE0);

    if (p.mvp >= 0) glUniformMatrix4fv(p.mvp, 1, GL_FALSE, mvp.data());
    if (p.inputSize >= 0) glUniform2f(p.inputSize, (float) input.width, (float) input.height);
    if (p.textureSize >= 0) glUniform2f(p.textureSize, (float) input.width, (float) input.height);
    if (p.outputSize >= 0) glUniform2f(p.outputSize, (float) outputWidth, (float) outputHeight);
    if (p.finalViewportSize >= 0) glUniform2f(p.finalViewportSize, (float) viewportWidth, (float) viewportHeight);
    unsigned counter = p.frameCountMod ? frameCount % p.frameCountMod : frameCount;
    if (p.frameCount >= 0) {
        if (p.floatCounters) glUniform1f(p.frameCount, (float) counter); else glUniform1i(p.frameCount, (GLint) counter);
    }
    if (p.frameDirection >= 0) {
        if (p.floatCounters) glUniform1f(p.frameDirection, 1.0F); else glUniform1i(p.frameDirection, 1);
    }
    if (p.frameTimeDelta >= 0) glUniform1i(p.frameTimeDelta, 16667);
    if (p.originalFps >= 0) glUniform1f(p.originalFps, 60.0F);
    if (p.rotation >= 0) glUniform1i(p.rotation, 0);
    float aspect = viewportHeight ? (float) viewportWidth / (float) viewportHeight : 1.0F;
    if (p.originalAspect >= 0) glUniform1f(p.originalAspect, aspect);
    if (p.originalAspectRotated >= 0) glUniform1f(p.originalAspectRotated, aspect);

    std::vector<GLint> enabled;
    auto attribute = [&enabled](GLint location, GLint size, const GLfloat* data) {
        if (location < 0) return;
        glEnableVertexAttribArray(location);
        glVertexAttribDivisor(location, 0);
        glVertexAttribPointer(location, size, GL_FLOAT, GL_FALSE, 0, data);
        enabled.push_back(location);
    };
    attribute(p.vertexCoord, 4, QUAD_VERTICES);
    attribute(p.texCoord, 2, QUAD_COORDINATES);
    attribute(p.lutTexCoord, 2, QUAD_COORDINATES);
    attribute(p.color, 4, QUAD_COLORS);
    for (const auto& frame : p.frames) attribute(frame.texCoord, 2, QUAD_COORDINATES);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    for (GLint location : enabled) glDisableVertexAttribArray(location);
}

void PresetChain::render(const PresetSource& source, const PresetTarget& target) {
    if (source.texture == 0 || source.width == 0 || source.height == 0 || target.width == 0 || target.height == 0) return;

    // The picture's size on the screen in its own orientation, what RetroArch calls the viewport.
    float rightX = target.right[0] * target.width * 0.5F, rightY = target.right[1] * target.height * 0.5F;
    float downX = target.down[0] * target.width * 0.5F, downY = target.down[1] * target.height * 0.5F;
    auto viewportWidth = (unsigned) std::max(1L, std::lround(std::hypot(rightX, rightY)));
    auto viewportHeight = (unsigned) std::max(1L, std::lround(std::hypot(downX, downY)));
    if (source.width != allocatedWidth || source.height != allocatedHeight
        || viewportWidth != allocatedViewportWidth || viewportHeight != allocatedViewportHeight) {
        allocate(source.width, source.height, viewportWidth, viewportHeight);
        LOGI("Shader preset viewport %ux%u for a frame of %ux%u, at %.2f,%.2f by %.2f,%.2f and %.2f,%.2f on %ux%u",
             viewportWidth, viewportHeight, source.width, source.height, (target.origin[0] + 1.0F) * target.width * 0.5F,
             (target.origin[1] + 1.0F) * target.height * 0.5F, rightX, rightY, downX, downY, target.width, target.height);
    }

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glDisable(GL_BLEND);

    // The frame, upright, into the newest history slot.
    const Target& original = history[current];
    glBindFramebuffer(GL_FRAMEBUFFER, original.framebuffer);
    glViewport(0, 0, (GLsizei) original.width, (GLsizei) original.height);
    glUseProgram(normalize.id);
    glUniform1f(normalizeFlip, source.bottomLeftOrigin ? 1.0F : 0.0F);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, source.texture);
    glEnableVertexAttribArray(normalizePosition);
    glVertexAttribDivisor(normalizePosition, 0);
    glVertexAttribPointer(normalizePosition, 2, GL_FLOAT, GL_FALSE, 0, NORMALIZE_POSITIONS);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(normalizePosition);
    if (spec->passes.front().mipmapInput) {
        glBindTexture(GL_TEXTURE_2D, original.texture);
        glGenerateMipmap(GL_TEXTURE_2D);
    }

    // RetroArch draws the last pass into a viewport of whole pixels. The layout's rectangle starts and ends
    // between pixels, so a viewport-sized pass would be drawn a fraction of a pixel narrower or wider than it
    // is, and a shader's mask or scanlines would skip or repeat one column where the error crosses a half.
    // The rectangle keeps its place to the nearest pixel and is exactly the viewport's size; a direction
    // within a hair of an axis is taken as that axis, so a rotated picture snaps too.
    auto snap = [](float x, float y, unsigned length, float pixelWidth, float pixelHeight) -> std::array<float, 2> {
        float size = std::hypot(x, y);
        float dx = size > 0.0F ? x / size : 1.0F, dy = size > 0.0F ? y / size : 0.0F;
        if (std::fabs(dx) < 1e-3F) dx = 0.0F, dy = dy < 0.0F ? -1.0F : 1.0F;
        if (std::fabs(dy) < 1e-3F) dy = 0.0F, dx = dx < 0.0F ? -1.0F : 1.0F;
        return { dx * (float) length / pixelWidth, dy * (float) length / pixelHeight };
    };
    float halfWidth = (float) target.width * 0.5F, halfHeight = (float) target.height * 0.5F;
    auto right = snap(rightX, rightY, viewportWidth, halfWidth, halfHeight);
    auto down = snap(downX, downY, viewportHeight, halfWidth, halfHeight);
    float originX = std::round((target.origin[0] + 1.0F) * halfWidth) / halfWidth - 1.0F;
    float originY = std::round((target.origin[1] + 1.0F) * halfHeight) / halfHeight - 1.0F;
    std::array<float, 16> screen = {
        right[0], right[1], 0, 0,
        down[0], down[1], 0, 0,
        0, 0, 1, 0,
        originX, originY, 0, 1,
    };
    size_t count = programs.size();
    for (size_t i = 0; i < count; ++i) {
        const Target& input = i == 0 ? original : passes[i - 1];
        if (i > 0 && spec->passes[i].mipmapInput) {
            glBindTexture(GL_TEXTURE_2D, input.texture);
            glGenerateMipmap(GL_TEXTURE_2D);
        }
        bool toScreen = i >= passes.size();
        if (toScreen) {
            glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
            glViewport(0, 0, (GLsizei) target.width, (GLsizei) target.height);
            drawPass(programs[i], input, viewportWidth, viewportHeight, screen, viewportWidth, viewportHeight);
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, passes[i].framebuffer);
            glViewport(0, 0, (GLsizei) passes[i].width, (GLsizei) passes[i].height);
            glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            glClear(GL_COLOR_BUFFER_BIT);
            drawPass(programs[i], input, passes[i].width, passes[i].height, FRAMEBUFFER_MVP, viewportWidth, viewportHeight);
        }
    }
    if (lastPassScaled) {
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
        glViewport(0, 0, (GLsizei) target.width, (GLsizei) target.height);
        drawPass(stock, passes.back(), viewportWidth, viewportHeight, screen, viewportWidth, viewportHeight);
    }

    // RetroArch swaps the feedback pass's target with the one it kept, so the next frame reads this one.
    if (feedback.texture) std::swap(passes[(size_t) spec->feedbackPass], feedback);
    current = (current + 1) % (unsigned) history.size();
    frameCount++;

    GLint highest = 0;
    for (const auto& program : programs) for (const auto& frame : program.frames) highest = std::max(highest, frame.unit);
    for (GLint unit = highest; unit >= 0; --unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    glUseProgram(0);
}

std::string PresetChain::describe() {
    std::string result;
    auto mean = [](const Target& target, GLenum format) {
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
        size_t count = (size_t) target.width * target.height;
        double sum[4] = { 0, 0, 0, 0 };
        if (format == GL_RGBA32F || format == GL_RGBA16F) {
            std::vector<float> pixels(count * 4);
            glReadPixels(0, 0, (GLsizei) target.width, (GLsizei) target.height, GL_RGBA, GL_FLOAT, pixels.data());
            for (size_t i = 0; i < count * 4; ++i) sum[i % 4] += pixels[i];
        } else {
            std::vector<uint8_t> pixels(count * 4);
            glReadPixels(0, 0, (GLsizei) target.width, (GLsizei) target.height, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
            for (size_t i = 0; i < count * 4; ++i) sum[i % 4] += pixels[i] / 255.0;
        }
        char text[160];
        snprintf(text, sizeof(text), "%ux%u %.4f %.4f %.4f %.4f", target.width, target.height,
                 sum[0] / (double) count, sum[1] / (double) count, sum[2] / (double) count, sum[3] / (double) count);
        return std::string(text);
    };
    const Target& original = history[(current + (unsigned) history.size() - 1) % (unsigned) history.size()];
    result += "original " + mean(original, GL_RGBA8);
    for (size_t i = 0; i < passes.size(); ++i) {
        const auto& pass = spec->passes[i];
        GLenum format = pass.floatFramebuffer ? floatFormat : (pass.srgbFramebuffer ? GL_SRGB8_ALPHA8 : GL_RGBA8);
        result += " | pass " + std::to_string(i) + " " + mean(passes[i], format);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return result;
}

std::string PresetChain::check(const PresetSpec& spec) {
    if (eglGetCurrentContext() != EGL_NO_CONTEXT) return "A shader can only be checked on a thread without a GL context";
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) return "No EGL display";
    const EGLint configAttributes[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE
    };
    EGLConfig config = nullptr;
    EGLint found = 0;
    if (!eglChooseConfig(display, configAttributes, &config, 1, &found) || found == 0) return "No OpenGL ES 3 configuration";
    const EGLint surfaceAttributes[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
    EGLSurface surface = eglCreatePbufferSurface(display, config, surfaceAttributes);
    const EGLint contextAttributes[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
    if (surface == EGL_NO_SURFACE || context == EGL_NO_CONTEXT || !eglMakeCurrent(display, surface, surface, context)) {
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        return "No OpenGL ES 3 context";
    }

    std::string failure;
    GLuint frame = 0, screen = 0, screenTexture = 0;
    try {
        // A 320x240 test card in place of a game, drawn into a 1440x1080 picture, the RP5's 4:3 at full height.
        const unsigned width = 320, height = 240, screenWidth = 1440, screenHeight = 1080;
        std::vector<uint8_t> pixels((size_t) width * height * 4);
        for (unsigned y = 0; y < height; ++y) for (unsigned x = 0; x < width; ++x) {
            uint8_t* pixel = &pixels[((size_t) y * width + x) * 4];
            pixel[0] = (uint8_t) (x * 255 / width); pixel[1] = (uint8_t) (y * 255 / height);
            pixel[2] = (uint8_t) (((x / 8 + y / 8) % 2) * 255); pixel[3] = 255;
        }
        glGenTextures(1, &frame);
        glBindTexture(GL_TEXTURE_2D, frame);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glGenTextures(1, &screenTexture);
        glBindTexture(GL_TEXTURE_2D, screenTexture);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, screenWidth, screenHeight);
        glGenFramebuffers(1, &screen);
        glBindFramebuffer(GL_FRAMEBUFFER, screen);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, screenTexture, 0);

        auto copy = std::make_shared<PresetSpec>(spec);
        {
            PresetChain chain(copy);
            PresetSource source { frame, width, height, false };
            PresetTarget target { screen, screenWidth, screenHeight };
            // Two frames, so history and feedback are read as well as written.
            chain.render(source, target);
            chain.render(source, target);
            glFinish();
            LOGI("Shader preset check drew: %s", chain.describe().c_str());
            GLenum error = glGetError();
            if (error != GL_NO_ERROR) failure = "The GPU reported error " + hex(error) + " while drawing it";
        }
    } catch (const std::exception& exception) {
        failure = exception.what();
        if (failure.empty()) failure = "It could not be built";
    }
    if (screen) glDeleteFramebuffers(1, &screen);
    if (screenTexture) glDeleteTextures(1, &screenTexture);
    if (frame) glDeleteTextures(1, &frame);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context);
    eglDestroySurface(display, surface);
    // The display is the process's own and the game's view uses it too: it is never terminated here.
    return failure;
}

}
