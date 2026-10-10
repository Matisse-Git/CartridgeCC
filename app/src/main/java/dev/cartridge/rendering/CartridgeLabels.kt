package dev.cartridge.rendering

import com.google.android.filament.*
import com.google.android.filament.gltfio.FilamentInstance
import dev.cartridge.storage.LabelPixels

/** Owns only replacement materials/textures. The glTF asset owns the original materials and mesh. */
internal class CartridgeLabels(private val engine: Engine, private val instances: Array<FilamentInstance?>) {
    /** The label one pooled object should carry: whose it is, which image, and its pixels once decoded. */
    class Wanted(val owner: String, val key: String, val pixels: LabelPixels?)
    private data class Binding(val renderable: Int, val primitive: Int, val original: MaterialInstance,
        var replacement: MaterialInstance? = null, var texture: Texture? = null, var key: String? = null, var owner: String? = null)
    private var bindings: List<Binding>? = null
    var wanted: List<Wanted?> = List(instances.size) { null }
        set(value) { require(value.size == instances.size); field = value }
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    /** Diagnostic: label textures uploaded so far. Browsing should add one per object that enters the window. */
    var uploads = 0
        private set
    /** The label image this pooled object carries on the GPU now, if any. */
    fun key(slot: Int) = bindings?.get(slot)?.key
    /** True once this object carries a label of [owner]. An object is never drawn with another game's label. */
    fun shows(slot: Int, owner: String) = bindings?.get(slot)?.let { it.key != null && it.owner == owner } == true

    fun update() {
        if (bindings == null && wanted.all { it == null }) return
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
            // A label lies in or just above its recess floor. Bias it toward the eye so the two never
            // contend for depth; geometry and assets stay as authored.
            found.single().also { it.original.setPolygonOffset(LABEL_BIAS, LABEL_BIAS) }
        }.also { bindings = it }
        targets.forEachIndexed { index, binding ->
            val want = wanted[index]
            when {
                want == null -> release(manager, binding)
                // Two games may print the same image; the texture is already right.
                binding.key == want.key -> binding.owner = want.owner
                want.pixels != null -> bind(manager, binding, index, want.owner, want.pixels)
                // The object changed hands and its new label is still decoding: show nothing rather than the old game's.
                binding.owner != want.owner -> release(manager, binding)
                // Same game, new label still decoding: its current label stays up until the new one is ready.
                else -> Unit
            }
        }
    }

    private fun bind(manager: RenderableManager, binding: Binding, index: Int, owner: String, pixels: LabelPixels) {
        val texture = Texture.Builder().width(pixels.width).height(pixels.height).levels(0xff)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8).build(engine)
        var replacement = binding.replacement
        try {
            texture.setImage(engine, 0, Texture.PixelBufferDescriptor(pixels.rgba.duplicate(), Texture.Format.RGBA, Texture.Type.UBYTE))
            texture.generateMipmaps(engine)
            replacement = replacement ?: MaterialInstance.duplicate(binding.original, "Game label $index").also { it.setPolygonOffset(LABEL_BIAS, LABEL_BIAS) }
            replacement.setParameter("baseColorMap", texture, sampler)
            manager.setMaterialInstanceAt(binding.renderable, binding.primitive, replacement)
            binding.texture?.let(engine::destroyTexture)
            binding.replacement = replacement; binding.texture = texture; binding.key = pixels.key; binding.owner = owner
            uploads++
        } catch (failure: Exception) {
            if (binding.replacement == null) replacement?.let(engine::destroyMaterialInstance)
            engine.destroyTexture(texture)
            throw failure
        }
    }

    private fun release(manager: RenderableManager, binding: Binding) {
        if (binding.key == null) return
        manager.setMaterialInstanceAt(binding.renderable, binding.primitive, binding.original)
        binding.replacement?.let(engine::destroyMaterialInstance)
        binding.texture?.let(engine::destroyTexture)
        binding.replacement = null; binding.texture = null; binding.key = null; binding.owner = null
    }

    private companion object { const val LABEL_BIAS = -2f }
    fun close() {
        bindings?.forEach { binding -> release(engine.renderableManager, binding) }
        bindings = null
        wanted = List(instances.size) { null }
    }
}
