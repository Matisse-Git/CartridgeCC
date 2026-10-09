package dev.cartridge.rendering

import android.content.Context
import android.annotation.SuppressLint
import android.opengl.Matrix
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.*
import dev.cartridge.domain.FocusSpring
import dev.cartridge.storage.LabelPixels
import java.nio.ByteBuffer
import kotlin.math.abs

/** Main-thread-only Filament owner. Detach is the GPU hand-off barrier. */
@SuppressLint("ViewConstructor") // Created by Compose with a prepared asset buffer.
class CartridgeSurface(
    context: Context,
    bytes: ByteArray,
    initialSelection: Int,
    private val onReady: () -> Unit,
    private val onReleased: () -> Unit,
    private val onLabelsReady: (Int, List<String?>) -> Unit = { _, _ -> },
    private val onLabelError: (String) -> Unit = {},
) : SurfaceView(context), Choreographer.FrameCallback {
    private val engine: Engine
    private val renderer: Renderer
    private val scene: Scene
    private val view: com.google.android.filament.View
    private val camera: Camera
    private val materialProvider: UbershaderProvider
    private val assetLoader: AssetLoader
    private val resourceLoader: ResourceLoader
    private val asset: FilamentAsset
    private val labels: CartridgeLabels
    private val instances = arrayOfNulls<FilamentInstance>(3)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val light: Int
    private val indirectLight: IndirectLight
    private val choreographer = Choreographer.getInstance()
    private val spring = FocusSpring(initialSelection.toFloat())
    private val matrix = FloatArray(16)
    private val renderables = IntArray(128)
    private var swapChain: SwapChain? = null
    private var closed = false
    private var active = false
    private var reportedReady = false
    private var lastFrame = 0L
    var selection: Int = initialSelection
    var windowStart: Int = 0
    var collectionCount: Int = 1
    var lift: Float = 0f
    var insertion: Float = 0f
    private var labelFailure = false
    fun setLabels(pixels: List<LabelPixels?>) {
        require(pixels.size <= instances.size)
        if (!closed) {
            val pooled = List(instances.size) { pixels.getOrNull(it) }
            if (labels.desired.map { it?.key } != pooled.map { it?.key }) labelFailure = false
            labels.desired = pooled
        }
    }

    init {
        Filament.init()
        Gltfio.init()
        engine = Engine.create(Engine.Backend.OPENGL)
        renderer = engine.createRenderer()
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = doubleArrayOf(0.009, 0.008, 0.007, 1.0)
        }
        scene = engine.createScene()
        camera = engine.createCamera(EntityManager.get().create()).apply {
            setExposure(16f, 1f / 125, 100f)
            lookAt(0.0, 0.15, 3.8, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        }
        view = engine.createView().apply {
            scene = this@CartridgeSurface.scene
            camera = this@CartridgeSurface.camera
            antiAliasing = com.google.android.filament.View.AntiAliasing.FXAA
        }
        light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1f, .94f, .83f).intensity(85_000f)
            .direction(-.5f, -.8f, -1f).castShadows(false).build(engine, light)
        scene.addEntity(light)
        // Low-cost diffuse environment for M0; a prefiltered studio cubemap follows in M2.
        indirectLight = IndirectLight.Builder().irradiance(1, floatArrayOf(.7f, .68f, .63f))
            .intensity(25_000f).build(engine)
        scene.indirectLight = indirectLight
        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)
        asset = checkNotNull(assetLoader.createInstancedAsset(ByteBuffer.wrap(bytes), instances)) {
            "Cannot decode cartridge GLB"
        }
        check(resourceLoader.asyncBeginLoad(asset)) { "Cannot load cartridge resources" }
        asset.releaseSourceData()
        labels = CartridgeLabels(engine, instances)
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let(engine::destroySwapChain)
                swapChain = engine.createSwapChain(surface)
            }
            override fun onDetachedFromSurface() {
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                    swapChain = null
                }
            }
            override fun onResized(width: Int, height: Int) {
                if (width == 0 || height == 0) return
                view.viewport = Viewport(0, 0, width, height)
                camera.setProjection(35.0, width.toDouble() / height, .05, 50.0, Camera.Fov.VERTICAL)
                engine.flushAndWait()
            }
        }
        uiHelper.attachTo(this)
    }

    fun resumeRendering() {
        if (closed || active) return
        active = true
        lastFrame = 0
        choreographer.postFrameCallback(this)
    }
    fun pauseRendering() {
        active = false
        choreographer.removeFrameCallback(this)
    }
    override fun doFrame(frameTimeNanos: Long) {
        if (!active || closed) return
        choreographer.postFrameCallback(this)
        val dt = if (lastFrame == 0L) 1f / 60 else (frameTimeNanos - lastFrame) / 1_000_000_000f
        lastFrame = frameTimeNanos
        val focus = spring.step(selection.toFloat(), dt)
        resourceLoader.asyncUpdateLoad()
        while (true) {
            val count = asset.popRenderables(renderables)
            if (count == 0) break
            scene.addEntities(renderables.copyOf(count))
        }
        if (resourceLoader.asyncGetLoadProgress() >= 1f && !labelFailure) {
            try { labels.update() }
            catch (failure: Exception) {
                labelFailure = true
                android.util.Log.e("CartridgeRenderer", "Label texture preparation failed", failure)
                onLabelError("Cannot prepare this label preview. Try another image.")
            }
        }
        instances.forEachIndexed { index, instance ->
            val offset = index + windowStart - focus
            val emphasis = (1f - abs(offset)).coerceIn(0f, 1f)
            Matrix.setIdentityM(matrix, 0)
            val progress = insertion.coerceIn(0f, 1f)
            val rise = (progress / .35f).coerceIn(0f, 1f) * .32f
            val slide = ((progress - .5f) / .5f).coerceIn(0f, 1f) * 2.1f
            // Inactive pool instances live beyond the far clip; no singular zero-scale transforms.
            Matrix.translateM(matrix, 0, offset * 1.85f, emphasis * (lift * .5f + rise - slide),
                if (index + windowStart < collectionCount) emphasis * .12f else -100f)
            Matrix.rotateM(matrix, 0, -12f - emphasis * (lift * 25f + progress * 18f), 1f, 0f, 0f)
            Matrix.rotateM(matrix, 0, (offset.coerceIn(-1f, 1f) * -22f + 8f) * (1f - emphasis * progress), 0f, 1f, 0f)
            val scale = 22f + emphasis * 5f
            Matrix.scaleM(matrix, 0, scale, scale, scale)
            engine.transformManager.setTransform(engine.transformManager.getInstance(instance!!.root), matrix)
        }
        val chain = swapChain ?: return
        if (uiHelper.isReadyToRender && renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            onLabelsReady(windowStart, labels.keys)
            if (!reportedReady && resourceLoader.asyncGetLoadProgress() >= 1f) {
                reportedReady = true
                onReady()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        pauseRendering()
        uiHelper.detach()
        resourceLoader.asyncCancelLoad()
        scene.removeEntities(asset.entities)
        labels.close()
        assetLoader.destroyAsset(asset)
        resourceLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        engine.destroyIndirectLight(indirectLight)
        engine.destroyEntity(light)
        EntityManager.get().destroy(light)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        EntityManager.get().destroy(camera.entity)
        engine.flushAndWait()
        engine.destroy()
        onReleased()
    }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
}
