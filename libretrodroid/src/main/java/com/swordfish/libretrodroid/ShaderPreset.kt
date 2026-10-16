/*
 *     Cartridge: a RetroArch GLSL preset (.glslp), already read from its files, handed to the host's
 *     final drawing pass. Added to LibretroDroid 0.14.0 for Project Cartridge (ADR 0027); the meaning of
 *     every field is RetroArch 1.22.2's (gfx/video_shader_parse.c, gfx/drivers/gl2.c).
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

package com.swordfish.libretrodroid

/**
 * One preset. [passes] run in order; the last one draws to the screen unless it has a scale of its own, in
 * which case a plain copy follows it. [feedbackPass] is the pass whose previous output `FeedbackTexture`
 * shows, or -1.
 */
class ShaderPreset(
    val passes: List<Pass>,
    val textures: List<Texture> = emptyList(),
    val parameters: Map<String, Float> = emptyMap(),
    val feedbackPass: Int = -1,
) {
    /** How a pass's size follows something else: [NONE] leaves it to the defaults RetroArch gives a pass without one. */
    enum class Scale { NONE, SOURCE, VIEWPORT, ABSOLUTE }

    enum class Wrap { BORDER, EDGE, REPEAT, MIRRORED_REPEAT }

    /**
     * [source] is the whole .glsl file, both stages in it. [filter] is -1 when the preset leaves it to the
     * frontend, else 0 nearest or 1 linear; like [wrap] and [mipmapInput] it describes how this pass reads its
     * input. [scaleX] and [scaleY] are factors, or pixels for [Scale.ABSOLUTE]. [compatSource] is the same
     * shader rewritten for GLSL ES, compiled only when [source] does not compile.
     */
    class Pass(
        val source: String,
        val name: String,
        val filter: Int = -1,
        val wrap: Wrap = Wrap.BORDER,
        val mipmapInput: Boolean = false,
        val alias: String = "",
        val floatFramebuffer: Boolean = false,
        val srgbFramebuffer: Boolean = false,
        val frameCountMod: Int = 0,
        val scaleTypeX: Scale = Scale.NONE,
        val scaleTypeY: Scale = Scale.NONE,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val compatSource: String? = null,
    ) {
        // Read by the native side, which takes enums by their position.
        @Suppress("unused") private val modes = intArrayOf(scaleTypeX.ordinal, scaleTypeY.ordinal, wrap.ordinal)
    }

    /** A lookup texture: [rgba] is [width] × [height] pixels, four bytes each, the image's top row first. */
    class Texture(
        val name: String,
        val width: Int,
        val height: Int,
        val rgba: ByteArray,
        val linear: Boolean = true,
        val mipmap: Boolean = false,
        val wrap: Wrap = Wrap.BORDER,
    ) {
        @Suppress("unused") private val wrapMode = wrap.ordinal
    }

    /**
     * Builds this preset and draws two frames with it in a small GL context of its own, made current on the
     * calling thread and destroyed afterwards; the thread must have no GL context. Null when that worked,
     * else why not. It shares nothing with a game's context.
     */
    fun check(): String? = LibretroDroid.checkShaderPreset(GLRetroShader(LibretroDroid.SHADER_PRESET, preset = this))

    // Read by the native side.
    @Suppress("unused") private val parameterNames = parameters.keys.toTypedArray()
    @Suppress("unused") private val parameterValues = parameters.values.toFloatArray()
    @Suppress("unused") private val passArray = passes.toTypedArray()
    @Suppress("unused") private val textureArray = textures.toTypedArray()
}
