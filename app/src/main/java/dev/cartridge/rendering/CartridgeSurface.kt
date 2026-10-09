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
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import dev.cartridge.domain.FocusSpring
import dev.cartridge.domain.CollectionLayout
import dev.cartridge.domain.GameSystem
import dev.cartridge.domain.MediaMotion
import dev.cartridge.storage.LabelPixels
import java.nio.ByteBuffer
import kotlin.math.abs

/** Main-thread-only Filament owner. Detach is the GPU hand-off barrier. */
@SuppressLint("ViewConstructor") // Created by Compose with a prepared asset buffer.
class CartridgeSurface(
    context: Context,
    bytes: CollectionAssets,
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
    private val receiverLoader: ResourceLoader
    private val tallLoader: ResourceLoader
    private val asset: FilamentAsset
    private val receiver: FilamentAsset
    private val tallAsset: FilamentAsset
    private val labels: CartridgeLabels
    private val tallLabels: CartridgeLabels
    private val instances = arrayOfNulls<FilamentInstance>(9)
    private val tallInstances = arrayOfNulls<FilamentInstance>(9)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val light: Int
    private val indirectLight: IndirectLight
    private val reflections: Texture
    private val choreographer = Choreographer.getInstance()
    private val spring = FocusSpring(initialSelection.toFloat())
    private val gridSpring = FocusSpring(0f)
    private var previousLayout = CollectionLayout.Horizontal
    var layout: CollectionLayout = CollectionLayout.Horizontal
    internal val poolCapacity get() = instances.size
    internal val meshInstanceCount get() = instances.size + tallInstances.size
    var systems: List<GameSystem> = List(9) { GameSystem.GBA }
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
    var reducedMotion: Boolean = false
    internal val focusPosition get() = spring.position
    internal val isClosed get() = closed
    internal val diagnosticState get() = "closed=$closed active=$active swap=${swapChain != null} ready=$reportedReady uiReady=${uiHelper.isReadyToRender} loads=${if (closed) "released" else "${resourceLoader.asyncGetLoadProgress()}/${tallLoader.asyncGetLoadProgress()}/${receiverLoader.asyncGetLoadProgress()}"}"
    /** Diagnostic only: CPU submission/scheduling, not a GPU presentation-time measurement. */
    internal var frameObserver: ((Long, Long, Boolean) -> Unit)? = null
    private var labelFailure = false
    fun setLabels(pixels: List<LabelPixels?>) {
        require(pixels.size <= instances.size)
        if (!closed) {
            val pooled = List(instances.size) { pixels.getOrNull(it) }
            val short = pooled.mapIndexed { index, label -> label.takeUnless { systems.getOrNull(index)?.tallCartridge == true } }
            val tall = pooled.mapIndexed { index, label -> label.takeIf { systems.getOrNull(index)?.tallCartridge == true } }
            if (labels.desired.map { it?.key } != short.map { it?.key } || tallLabels.desired.map { it?.key } != tall.map { it?.key }) labelFailure = false
            labels.desired = short; tallLabels.desired = tall
        }
    }

    init {
        Filament.init()
        Gltfio.init()
        Utils.init()
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
            .color(1f, .96f, .9f).intensity(75_000f)
            .direction(-.5f, -.8f, -1f).castShadows(false).build(engine, light)
        scene.addEntity(light)
        val environment = KTX1Loader.createIndirectLight(engine,
            ByteBuffer.allocateDirect(bytes.studio.size).apply { put(bytes.studio); flip() })
        indirectLight = checkNotNull(environment.indirectLight) { "Studio KTX needs spherical harmonics" }
        reflections = checkNotNull(environment.cubemap)
        indirectLight.intensity = 70_000f
        scene.indirectLight = indirectLight
        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)
        asset = checkNotNull(assetLoader.createInstancedAsset(ByteBuffer.wrap(bytes.cartridge), instances)) {
            "Cannot decode cartridge GLB"
        }
        check(resourceLoader.asyncBeginLoad(asset)) { "Cannot load cartridge resources" }
        asset.releaseSourceData()
        tallAsset = checkNotNull(assetLoader.createInstancedAsset(ByteBuffer.wrap(bytes.tallCartridge), tallInstances)) { "Cannot decode tall cartridge GLB" }
        tallLoader = ResourceLoader(engine)
        check(tallLoader.asyncBeginLoad(tallAsset)) { "Cannot load tall cartridge resources" }
        tallAsset.releaseSourceData()
        receiver = checkNotNull(assetLoader.createAsset(ByteBuffer.wrap(bytes.receiver))) { "Cannot decode receiving slot" }
        receiverLoader = ResourceLoader(engine)
        check(receiverLoader.asyncBeginLoad(receiver)) { "Cannot load receiving slot" }
        receiver.releaseSourceData()
        labels = CartridgeLabels(engine, instances)
        tallLabels = CartridgeLabels(engine, tallInstances)
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
        val started = if (frameObserver != null) System.nanoTime() else 0L
        choreographer.postFrameCallback(this)
        val dt = if (lastFrame == 0L) 1f / 60 else (frameTimeNanos - lastFrame) / 1_000_000_000f
        lastFrame = frameTimeNanos
        if (layout != previousLayout) { spring.snapTo(selection.toFloat()); gridSpring.snapTo(layout.gridCenter(selection, collectionCount)); previousLayout = layout }
        val focus = if (reducedMotion) spring.snapTo(selection.toFloat()) else spring.step(selection.toFloat(), dt)
        val gridCenter = if (reducedMotion) gridSpring.snapTo(layout.gridCenter(selection, collectionCount)) else gridSpring.step(layout.gridCenter(selection, collectionCount), dt)
        resourceLoader.asyncUpdateLoad()
        receiverLoader.asyncUpdateLoad()
        tallLoader.asyncUpdateLoad()
        while (true) {
            val count = asset.popRenderables(renderables)
            if (count == 0) break
            scene.addEntities(renderables.copyOf(count))
        }
        while (true) {
            val count = tallAsset.popRenderables(renderables)
            if (count == 0) break
            scene.addEntities(renderables.copyOf(count))
        }
        while (true) {
            val count = receiver.popRenderables(renderables)
            if (count == 0) break
            scene.addEntities(renderables.copyOf(count))
        }
        if (resourceLoader.asyncGetLoadProgress() >= 1f && tallLoader.asyncGetLoadProgress() >= 1f && !labelFailure) {
            try { labels.update(); tallLabels.update() }
            catch (failure: Exception) {
                labelFailure = true
                android.util.Log.e("CartridgeRenderer", "Label texture preparation failed", failure)
                onLabelError("Cannot prepare this label preview. Try another image.")
            }
        }
        for ((tall, pool) in listOf(false to instances, true to tallInstances)) pool.forEachIndexed { index, instance ->
            val global = index + windowStart
            val offset = global - focus
            val selected = global == selection
            val emphasis = if (layout == CollectionLayout.Grid) if (selected) 1f else 0f else (1f - abs(offset)).coerceIn(0f, 1f)
            Matrix.setIdentityM(matrix, 0)
            val pose = MediaMotion.pose(if (selected && !reducedMotion) insertion else 0f)
            val x = when (layout) { CollectionLayout.Horizontal -> offset * 1.85f; CollectionLayout.Vertical -> 0f; CollectionLayout.Grid -> (global % 3 - 1) * 1.35f }
            val y = when (layout) { CollectionLayout.Horizontal -> 0f; CollectionLayout.Vertical -> -offset * 1.30f; CollectionLayout.Grid -> (gridCenter - global / 3) * .85f }
            val visible = index < layout.visibleCount && global < collectionCount && (systems.getOrNull(index)?.tallCartridge == true) == tall
            Matrix.translateM(matrix, 0, x * (1f - pose.alignment), y * (1f - pose.alignment) + emphasis * lift * .5f + pose.height,
                if (visible) emphasis * .12f else -100f)
            Matrix.rotateM(matrix, 0, (-10f - emphasis * lift * 25f) * (1f - pose.alignment), 1f, 0f, 0f)
            val angle = if (layout == CollectionLayout.Grid) (global % 3 - 1) * -8f else offset.coerceIn(-1f, 1f) * -22f + 8f
            Matrix.rotateM(matrix, 0, angle * (1f - pose.alignment), 0f, 1f, 0f)
            val restScale = if (layout == CollectionLayout.Grid) 16f + emphasis * 2f else 22f + emphasis * 5f
            val scale = (restScale + (27f - restScale) * pose.alignment) * if (tall) .62f else 1f
            Matrix.scaleM(matrix, 0, scale, scale, scale)
            engine.transformManager.setTransform(engine.transformManager.getInstance(instance!!.root), matrix)
        }
        val slot = if (reducedMotion) 0f else MediaMotion.pose(insertion).slot
        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, 0f, -.90f + .17f * slot, if (slot > .001f) 0f else -100f)
        Matrix.scaleM(matrix, 0, 27f, 27f, 27f)
        engine.transformManager.setTransform(engine.transformManager.getInstance(receiver.root), matrix)
        val chain = swapChain ?: return
        var submitted = false
        if (uiHelper.isReadyToRender && renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            submitted = true
            onLabelsReady(windowStart, (0 until layout.visibleCount).map { if (systems.getOrNull(it)?.tallCartridge == true) tallLabels.keys[it] else labels.keys[it] })
            if (!reportedReady && resourceLoader.asyncGetLoadProgress() >= 1f && receiverLoader.asyncGetLoadProgress() >= 1f && tallLoader.asyncGetLoadProgress() >= 1f) {
                reportedReady = true
                onReady()
            }
        }
        frameObserver?.invoke(frameTimeNanos, System.nanoTime() - started, submitted)
    }

    fun close() {
        if (closed) return
        closed = true
        pauseRendering()
        uiHelper.detach()
        resourceLoader.asyncCancelLoad()
        receiverLoader.asyncCancelLoad()
        tallLoader.asyncCancelLoad()
        scene.removeEntities(asset.entities)
        scene.removeEntities(receiver.entities)
        scene.removeEntities(tallAsset.entities)
        labels.close()
        tallLabels.close()
        assetLoader.destroyAsset(asset)
        assetLoader.destroyAsset(receiver)
        assetLoader.destroyAsset(tallAsset)
        resourceLoader.destroy()
        receiverLoader.destroy()
        tallLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        engine.destroyIndirectLight(indirectLight)
        engine.destroyTexture(reflections)
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
