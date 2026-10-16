package dev.cartridge.shaders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.swordfish.libretrodroid.ShaderPreset
import java.io.File

/**
 * The downloaded RetroArch GLSL shader library (ADR 0027) and what the host needs from one of its presets.
 * Reading and checking block: call them off Main.
 */
object ShaderLibrary {
    /** The installed library: the repository's tree as it was published, presets in their own folders. */
    fun root(context: Context) = File(context.filesDir, "shaders/library")

    /** [preset] read with everything it names and its images decoded; [PresetReader.Unusable] says why not. */
    fun load(root: File, preset: File): ShaderPreset {
        val read = PresetReader(root).read(preset)
        var budget = MAX_TEXTURE_BYTES
        val textures = read.textures.map { texture ->
            val image = decode(texture.file) ?: throw PresetReader.Unusable("Texture ${texture.name} (${texture.file.name}) is not an image this device reads")
            budget -= image.third.size.toLong()
            if (budget < 0) throw PresetReader.Unusable("The preset's textures are too large")
            ShaderPreset.Texture(texture.name, image.first, image.second, image.third, texture.linear, texture.mipmap, wrap(texture.wrap))
        }
        val canonicalRoot = root.canonicalFile
        val passes = read.passes.map { pass ->
            // Written the way the RP5's compiler gets right, whichever way the host ends up compiling it.
            val source = GlslCompat.selectedCallsAsBranches(pass.source)
            ShaderPreset.Pass(
                source = source,
                name = pass.file.relativeTo(canonicalRoot).invariantSeparatorsPath,
                filter = pass.filter,
                wrap = wrap(pass.wrap),
                mipmapInput = pass.mipmapInput,
                alias = pass.alias,
                floatFramebuffer = pass.floatFramebuffer,
                srgbFramebuffer = pass.srgbFramebuffer,
                frameCountMod = pass.frameCountMod,
                scaleTypeX = ShaderPreset.Scale.valueOf(pass.scaleTypeX.name),
                scaleTypeY = ShaderPreset.Scale.valueOf(pass.scaleTypeY.name),
                scaleX = pass.scaleX,
                scaleY = pass.scaleY,
                compatSource = GlslCompat.forGlslEs(source),
            )
        }
        return ShaderPreset(passes, textures, read.parameters, read.feedbackPass)
    }

    /** Null when [preset] builds and draws on this GPU, else why not; see [ShaderPreset.check]. */
    fun check(preset: ShaderPreset): String? = preset.check()

    private fun wrap(wrap: PresetReader.Wrap) = ShaderPreset.Wrap.valueOf(wrap.name)

    /** Width, height and RGBA bytes, the image's top row first and its colours not premultiplied. */
    private fun decode(file: File): Triple<Int, Int, ByteArray>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_TEXTURE_SIDE || bounds.outHeight > MAX_TEXTURE_SIDE) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val rgba = ByteArray(pixels.size * 4)
            for (i in pixels.indices) {
                val argb = pixels[i]
                rgba[i * 4] = (argb shr 16).toByte()
                rgba[i * 4 + 1] = (argb shr 8).toByte()
                rgba[i * 4 + 2] = argb.toByte()
                rgba[i * 4 + 3] = (argb ushr 24).toByte()
            }
            return Triple(width, height, rgba)
        } finally {
            bitmap.recycle()
        }
    }

    private const val MAX_TEXTURE_SIDE = 4096
    private const val MAX_TEXTURE_BYTES = 128L * 1024 * 1024
}
