/*
 *     Cartridge: desktop GLSL turned into GLSL ES through SPIR-V. See glsltranslate.h.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#include "glsltranslate.h"

#include <glslang/Public/ResourceLimits.h>
#include <glslang/Public/ShaderLang.h>
#include <SPIRV/GlslangToSpv.h>
#include <spirv_glsl.hpp>

#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>

namespace libretrodroid {

namespace {

std::once_flag initialized;

const EShMessages MESSAGES = EShMsgDefault;

std::unique_ptr<glslang::TShader> parse(EShLanguage stage, const std::string& source) {
    auto shader = std::make_unique<glslang::TShader>(stage);
    const char* text = source.c_str();
    shader->setStrings(&text, 1);
    // OpenGL's dialect of SPIR-V keeps plain uniforms, as the presets declare them; glslang gives every input,
    // output, uniform and sampler a location or binding of its own.
    shader->setEnvInput(glslang::EShSourceGlsl, stage, glslang::EShClientOpenGL, 100);
    shader->setEnvClient(glslang::EShClientOpenGL, glslang::EShTargetOpenGL_450);
    shader->setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_0);
    shader->setAutoMapLocations(true);
    shader->setAutoMapBindings(true);
    if (!shader->parse(GetDefaultResources(), 330, false, MESSAGES)) {
        throw std::runtime_error(std::string(stage == EShLangVertex ? "vertex stage: " : "fragment stage: ") + shader->getInfoLog());
    }
    return shader;
}

std::string toEs(const glslang::TIntermediate& intermediate) {
    std::vector<unsigned int> spirv;
    glslang::SpvOptions options;
    options.disableOptimizer = true;
    glslang::GlslangToSpv(intermediate, spirv, &options);

    spirv_cross::CompilerGLSL compiler(std::move(spirv));
    spirv_cross::CompilerGLSL::Options es = compiler.get_common_options();
    es.es = true;
    es.version = 300;
    es.enable_420pack_extension = false;
    // Desktop GL computes everything at full precision; so does what is written for it here.
    es.fragment.default_float_precision = spirv_cross::CompilerGLSL::Options::Highp;
    es.fragment.default_int_precision = spirv_cross::CompilerGLSL::Options::Highp;
    compiler.set_common_options(es);

    // GLSL ES 3.00 has no bindings or uniform locations: samplers and uniforms are found by name, as before.
    auto resources = compiler.get_shader_resources();
    for (const auto& resource : resources.sampled_images) {
        compiler.unset_decoration(resource.id, spv::DecorationBinding);
        compiler.unset_decoration(resource.id, spv::DecorationDescriptorSet);
        compiler.unset_decoration(resource.id, spv::DecorationLocation);
    }
    for (const auto& resource : resources.gl_plain_uniforms) {
        compiler.unset_decoration(resource.id, spv::DecorationLocation);
        compiler.unset_decoration(resource.id, spv::DecorationBinding);
    }
    return compiler.compile();
}

}

std::pair<std::string, std::string> translateToEs(const std::string& vertex, const std::string& fragment) {
    std::call_once(initialized, [] { glslang::InitializeProcess(); });
    auto vertexShader = parse(EShLangVertex, vertex);
    auto fragmentShader = parse(EShLangFragment, fragment);
    std::string result[2];
    {
        // A program links before its shaders go, as glslang requires.
        glslang::TProgram program;
        program.addShader(vertexShader.get());
        program.addShader(fragmentShader.get());
        if (!program.link(MESSAGES)) throw std::runtime_error(std::string("link: ") + program.getInfoLog());
        if (!program.mapIO()) throw std::runtime_error(std::string("input and output mapping: ") + program.getInfoLog());
        try {
            result[0] = toEs(*program.getIntermediate(EShLangVertex));
            result[1] = toEs(*program.getIntermediate(EShLangFragment));
        } catch (const spirv_cross::CompilerError& failure) {
            throw std::runtime_error(std::string("SPIRV-Cross: ") + failure.what());
        }
    }
    return { result[0], result[1] };
}

}
