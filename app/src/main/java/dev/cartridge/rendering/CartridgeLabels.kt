package dev.cartridge.rendering

import com.google.android.filament.*
import com.google.android.filament.gltfio.FilamentInstance
import dev.cartridge.storage.LabelPixels

/** Owns only replacement materials/textures. The glTF asset owns the original materials and mesh. */
internal class CartridgeLabels(private val engine: Engine, private val instances: Array<FilamentInstance?>) {
    private data class Binding(val renderable: Int, val primitive: Int, val original: MaterialInstance,
        var replacement: MaterialInstance? = null, var texture: Texture? = null, var key: String? = null)
    private var bindings: List<Binding>? = null
    var desired: List<LabelPixels?> = List(3) { null }
        set(value) { require(value.size == 3); field = value }
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    val keys get() = bindings?.map { it.key } ?: List(3) { null }

    fun update() {
        if (bindings == null && desired.all { it == null }) return
        val manager = engine.renderableManager
        val targets = bindings ?: instances.map { instance ->
            val found = instance!!.entities.flatMap { entity ->
                if (!manager.hasComponent(entity)) emptyList() else {
                    val renderable = manager.getInstance(entity)
                    (0 until manager.getPrimitiveCount(renderable)).mapNotNull { primitive ->
                        val material = manager.getMaterialInstanceAt(renderable, primitive)
                        if (material.name == "Replaceable fictional label") Binding(renderable, primitive, material) else null
                    }
                }
            }
            check(found.size == 1) { "The cartridge template needs one named label material per instance" }
            found.single()
        }.also { bindings = it }
        targets.forEachIndexed { index, binding ->
            val pixels = desired[index]
            if (binding.key == pixels?.key) return@forEachIndexed
            if (pixels == null) {
                manager.setMaterialInstanceAt(binding.renderable, binding.primitive, binding.original)
                binding.replacement?.let(engine::destroyMaterialInstance)
                binding.texture?.let(engine::destroyTexture)
                binding.replacement = null; binding.texture = null; binding.key = null
            } else {
                val texture = Texture.Builder().width(pixels.width).height(pixels.height).levels(0xff)
                    .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
                    .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8).build(engine)
                var replacement = binding.replacement
                try {
                    texture.setImage(engine, 0, Texture.PixelBufferDescriptor(pixels.rgba.duplicate(), Texture.Format.RGBA, Texture.Type.UBYTE))
                    texture.generateMipmaps(engine)
                    replacement = replacement ?: MaterialInstance.duplicate(binding.original, "Game label $index")
                    replacement.setParameter("baseColorMap", texture, sampler)
                    manager.setMaterialInstanceAt(binding.renderable, binding.primitive, replacement)
                    binding.texture?.let(engine::destroyTexture)
                    binding.replacement = replacement; binding.texture = texture; binding.key = pixels.key
                } catch (failure: Exception) {
                    if (binding.replacement == null) replacement?.let(engine::destroyMaterialInstance)
                    engine.destroyTexture(texture)
                    throw failure
                }
            }
        }
    }
    fun close() {
        bindings?.forEach { binding ->
            engine.renderableManager.setMaterialInstanceAt(binding.renderable, binding.primitive, binding.original)
            binding.replacement?.let(engine::destroyMaterialInstance)
            binding.texture?.let(engine::destroyTexture)
        }
        bindings = null
        desired = List(3) { null }
    }
}
