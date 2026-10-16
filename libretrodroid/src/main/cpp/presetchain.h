/*
 *     Cartridge: draws a RetroArch GLSL preset (.glslp) as the host's final pass. Added to LibretroDroid
 *     0.14.0 for Project Cartridge (ADR 0027). Every rule here follows RetroArch 1.22.2's GL driver
 *     (gfx/drivers/gl2.c, gfx/drivers_shader/shader_glsl.c, gfx/video_shader_parse.c); where it differs
 *     the comment says why.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#ifndef LIBRETRODROID_PRESETCHAIN_H
#define LIBRETRODROID_PRESETCHAIN_H

#include <GLES3/gl3.h>

#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace libretrodroid {

/** A preset already read from its files; the Kotlin side (ShaderPreset) fills it in. */
struct PresetSpec {
    enum Scale { NONE = 0, SOURCE = 1, VIEWPORT = 2, ABSOLUTE = 3 };
    enum Wrap { BORDER = 0, EDGE = 1, REPEAT = 2, MIRRORED_REPEAT = 3 };

    struct Pass {
        std::string source;
        std::string name;
        int filter = -1;
        int wrap = BORDER;
        bool mipmapInput = false;
        std::string alias;
        bool floatFramebuffer = false;
        bool srgbFramebuffer = false;
        unsigned frameCountMod = 0;
        int scaleTypeX = NONE;
        int scaleTypeY = NONE;
        float scaleX = 1.0F;
        float scaleY = 1.0F;
        // The same shader rewritten for GLSL ES (GlslCompat on the Kotlin side); empty when there is none.
        std::string compatSource;
    };

    struct Texture {
        std::string name;
        unsigned width = 0;
        unsigned height = 0;
        std::vector<uint8_t> rgba;
        bool linear = true;
        bool mipmap = false;
        int wrap = BORDER;
    };

    std::vector<Pass> passes;
    std::vector<Texture> textures;
    std::vector<std::pair<std::string, float>> parameters;
    int feedbackPass = -1;
};

/** The frame the core produced: a texture the size of the picture, its first row at the top or bottom. */
struct PresetSource {
    GLuint texture = 0;
    unsigned width = 0;
    unsigned height = 0;
    bool bottomLeftOrigin = false;
};

/**
 * Where the preset's picture goes: a framebuffer (0 is the screen) of the given size, and the parallelogram
 * the image covers there in normalized device coordinates: the image's top-left corner, and the vectors to
 * its top-right and bottom-left corners. Rotation and letterboxing are in those three points.
 */
struct PresetTarget {
    GLuint framebuffer = 0;
    unsigned width = 0;
    unsigned height = 0;
    std::array<float, 2> origin { -1.0F, 1.0F };
    std::array<float, 2> right { 2.0F, 0.0F };
    std::array<float, 2> down { 0.0F, -2.0F };
};

class PresetChain {
public:
    /** Compiles and links every pass and uploads every texture; throws std::runtime_error saying why not. */
    explicit PresetChain(std::shared_ptr<const PresetSpec> spec);
    ~PresetChain();
    PresetChain(const PresetChain&) = delete;
    PresetChain& operator=(const PresetChain&) = delete;

    /** Draws one frame. Leaves texture unit 0 active, no program in use and nothing bound to the units it used. */
    void render(const PresetSource& source, const PresetTarget& target);

    /**
     * Builds the preset and draws two frames with it in a small GL context of its own, made current on the
     * calling thread and destroyed afterwards. Returns an empty string when that worked, else the reason.
     */
    static std::string check(const PresetSpec& spec);

    /** Each pass's picture as its mean colour, for the log when a check runs; reads back from the GPU. */
    std::string describe();

private:
    enum class Ref { Original, History, Pass, Feedback, Lut };
    struct Frame {
        Ref ref;
        unsigned index;
        GLint texture;
        GLint textureSize;
        GLint inputSize;
        GLint texCoord;
        GLint unit = -1;
    };
    struct Program {
        GLuint id = 0;
        GLint mvp = -1;
        GLint vertexCoord = -1;
        GLint texCoord = -1;
        GLint color = -1;
        GLint lutTexCoord = -1;
        GLint texture = -1;
        GLint inputSize = -1;
        GLint outputSize = -1;
        GLint textureSize = -1;
        GLint finalViewportSize = -1;
        GLint frameCount = -1;
        GLint frameDirection = -1;
        // A source rewritten for GLSL ES declares the counters as floats (GlslCompat).
        bool floatCounters = false;
        GLint frameTimeDelta = -1;
        GLint originalFps = -1;
        GLint rotation = -1;
        GLint originalAspect = -1;
        GLint originalAspectRotated = -1;
        std::vector<Frame> frames;
        std::vector<std::pair<GLint, float>> parameters;
        unsigned frameCountMod = 0;
    };
    struct Target {
        GLuint framebuffer = 0;
        GLuint texture = 0;
        unsigned width = 0;
        unsigned height = 0;
    };

    void buildProgram(size_t index);
    void uploadTextures();
    void allocate(unsigned width, unsigned height, unsigned viewportWidth, unsigned viewportHeight);
    void releaseTargets();
    Target createTarget(unsigned width, unsigned height, GLenum format, bool mipmapped, int filter, int wrap);
    void setSampling(GLuint texture, int filter, int wrap, bool mipmapped);
    void drawPass(const Program& program, const Target& input, unsigned outputWidth, unsigned outputHeight,
                  const std::array<float, 16>& mvp, unsigned viewportWidth, unsigned viewportHeight);
    const Target& frameTarget(const Frame& frame) const;
    GLenum wrapMode(int wrap) const;

    std::shared_ptr<const PresetSpec> spec;
    std::vector<Program> programs;
    Program stock;
    Program normalize;
    GLint normalizeFlip = -1;
    GLint normalizePosition = -1;
    std::vector<Target> luts;

    // The picture as the chain sees it: the core's frame copied upright, the current one and those before it.
    std::vector<Target> history;
    unsigned current = 0;
    unsigned historyDepth = 0;
    std::vector<Target> passes;
    Target feedback;
    bool usesFeedback = false;
    bool lastPassScaled = false;
    unsigned frameCount = 0;

    unsigned allocatedWidth = 0;
    unsigned allocatedHeight = 0;
    unsigned allocatedViewportWidth = 0;
    unsigned allocatedViewportHeight = 0;

    GLint maxTextureSize = 0;
    GLint maxUnits = 0;
    bool borderClamp = false;
    GLenum floatFormat = GL_RGBA8;
};

}

#endif //LIBRETRODROID_PRESETCHAIN_H
