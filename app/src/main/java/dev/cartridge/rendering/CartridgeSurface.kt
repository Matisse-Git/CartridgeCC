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
    private val tallLoader: ResourceLoader
    private val discLoader: ResourceLoader
    private val asset: FilamentAsset
    private val tallAsset: FilamentAsset
    private val discAsset: FilamentAsset
    private val labels: CartridgeLabels
    private val tallLabels: CartridgeLabels
    private val discLabels: CartridgeLabels
    private val instances = arrayOfNulls<FilamentInstance>(CollectionLayout.POOL)
    private val tallInstances = arrayOfNulls<FilamentInstance>(CollectionLayout.POOL)
    private val discInstances = arrayOfNulls<FilamentInstance>(CollectionLayout.POOL)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val light: Int
    private val indirectLight: IndirectLight
    private val reflections: Texture
    private val choreographer = Choreographer.getInstance()
    private val spring = FocusSpring(initialSelection.toFloat())
    private val gridSpring = FocusSpring(0f)
    private val stageSpring = FocusSpring(0f)
    private var previousLayout = CollectionLayout.Horizontal
    var layout: CollectionLayout = CollectionLayout.Horizontal
    internal val poolCapacity get() = instances.size
    internal val meshInstanceCount get() = instances.size + tallInstances.size + discInstances.size
    /** One shelf entry of the staged window: the template that shows it and the label it must carry. */
    class Staged(val system: GameSystem, val owner: String, val key: String, val pixels: LabelPixels?)
    private class Placed(val global: Int, val entry: Staged?)
    // By pooled object. Until a window is staged the first objects stand as the bare template, for diagnostics.
    private var placed = Array<Placed?>(CollectionLayout.POOL) { Placed(it, null) }
    private var windowStart = 0
    private var windowSize = 0
    private val matrix = FloatArray(16)
    private val renderables = IntArray(128)
    private var swapChain: SwapChain? = null
    private var closed = false
    private var active = false
    private var reportedReady = false
    private val created = android.os.SystemClock.uptimeMillis()
    private var lastFrame = 0L
    var selection: Int = initialSelection
    var collectionCount: Int = 1
    var lift: Float = 0f
    var insertion: Float = 0f
    var reducedMotion: Boolean = false
    /**
     * 0 browses the shelf. 1 presents the focused object alone at the left of the stage, beside a sheet;
     * its neighbours slide out of frame. The same geometry, materials and lighting are used throughout.
     */
    var presentation: Float = 0f
    private var spinX = 0f
    private var spinY = 0f
    private var yaw = 0f
    private var pitch = 0f
    private var spinRest = 0f
    private var spunSelection = initialSelection
    /**
     * Turn the focused object: right-stick deflection, -1..1 on each axis. It spins about its own upright axis
     * for as long as the stick is held and tips toward or away from the viewer; once the stick has rested it
     * eases home by the shorter way round. Moving the focus or starting a game returns it at once.
     */
    fun spin(x: Float, y: Float) { spinX = x.coerceIn(-1f, 1f); spinY = y.coerceIn(-1f, 1f) }
    /** Diagnostic: how far the focused object is turned and tipped, in degrees. */
    internal val spinAngles get() = yaw to pitch
    /** Let a newly chosen shelf arrive from the side it was reached from, instead of swapping in place. */
    fun arriveFrom(direction: Int) { if (!reducedMotion && direction != 0) spring.snapTo(selection + direction * 1.4f) }
    internal val focusPosition get() = spring.position
    /** Diagnostic: label textures uploaded since this renderer was created. */
    internal val labelUploads get() = labels.uploads + tallLabels.uploads + discLabels.uploads
    /** Diagnostic: staged objects that were on stage in the last frame without their own label. Browsing keeps this at zero. */
    internal var unlabelledOnStage = 0
        private set
    /** Diagnostic: staged objects, on stage or waiting beside it, whose own label is not on the GPU yet. */
    internal val stagedUnbound get() = placed.withIndex().count { (index, place) ->
        place?.entry?.let { !listOf(labels, tallLabels, discLabels)[format(it.system)].shows(index, it.owner) } == true }
    internal val isClosed get() = closed
    internal val diagnosticState get() = "closed=$closed active=$active swap=${swapChain != null} ready=$reportedReady uiReady=${uiHelper.isReadyToRender} loads=${if (closed) "released" else "${resourceLoader.asyncGetLoadProgress()}/${tallLoader.asyncGetLoadProgress()}/${discLoader.asyncGetLoadProgress()}"}"
    /** Frames handed to the display in each full second, for the optional readout. Main thread. */
    var onFrameRate: ((Int) -> Unit)? = null
    private var counted = 0
    private var countedSince = 0L
    /** Diagnostic only: CPU submission/scheduling, not a GPU presentation-time measurement. */
    internal var frameObserver: ((Long, Long, Boolean) -> Unit)? = null
    private var labelFailure = false
    private fun format(system: GameSystem) = when { system == GameSystem.PS1 -> 2; system.tallCartridge -> 1; else -> 0 }
    /**
     * Stage the bounded slice of the shelf that starts at [start]. Every shelf index keeps the pooled object
     * [CollectionLayout.slot] gives it, so moving the window re-labels only the objects that entered it;
     * the ones already on screen keep their object, their place and their texture.
     */
    fun setWindow(start: Int, entries: List<Staged>) {
        require(entries.size <= poolCapacity)
        if (closed) return
        val next = arrayOfNulls<Placed>(poolCapacity)
        entries.forEachIndexed { offset, entry -> next[CollectionLayout.slot(start + offset)] = Placed(start + offset, entry) }
        listOf(labels, tallLabels, discLabels).forEachIndexed { pool, target ->
            val wanted = List(poolCapacity) { slot -> next[slot]?.entry?.takeIf { format(it.system) == pool }?.let { CartridgeLabels.Wanted(it.owner, it.key, it.pixels) } }
            if (target.wanted.map { it?.key } != wanted.map { it?.key }) labelFailure = false
            target.wanted = wanted
        }
        placed = next; windowStart = start; windowSize = entries.size
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
        discAsset = checkNotNull(assetLoader.createInstancedAsset(ByteBuffer.wrap(bytes.disc), discInstances)) { "Cannot decode optical disc GLB" }
        discLoader = ResourceLoader(engine)
        check(discLoader.asyncBeginLoad(discAsset)) { "Cannot load optical disc resources" }
        discAsset.releaseSourceData()
        labels = CartridgeLabels(engine, instances)
        tallLabels = CartridgeLabels(engine, tallInstances)
        discLabels = CartridgeLabels(engine, discInstances)
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
        counted = 0; countedSince = 0L
        choreographer.removeFrameCallback(this)
    }
    override fun doFrame(frameTimeNanos: Long) {
        if (!active || closed) return
        val started = if (frameObserver != null) System.nanoTime() else 0L
        choreographer.postFrameCallback(this)
        val dt = if (lastFrame == 0L) 1f / 60 else (frameTimeNanos - lastFrame) / 1_000_000_000f
        lastFrame = frameTimeNanos
        if (layout != previousLayout) { spring.snapTo(selection.toFloat()); gridSpring.snapTo(layout.gridCenter(selection, collectionCount)); previousLayout = layout }
        if (selection != spunSelection) { spunSelection = selection; yaw = 0f; pitch = 0f }
        val step = dt.coerceIn(0f, .05f)
        if ((spinX != 0f || spinY != 0f) && insertion == 0f) {
            spinRest = 0f
            yaw += spinX * SPIN_RATE * step
            pitch = (pitch + spinY * TIP_RATE * step).coerceIn(-TIP_LIMIT, TIP_LIMIT)
        } else if (yaw != 0f || pitch != 0f) {
            spinRest += step
            if (spinRest > SPIN_HOLD || insertion > 0f) {
                // Home by the shorter way round.
                yaw = (yaw + 180f).mod(360f) - 180f
                val keep = if (reducedMotion) 0f else kotlin.math.exp(-SPIN_RETURN * step)
                yaw *= keep; pitch *= keep
                if (abs(yaw) < .05f && abs(pitch) < .05f) { yaw = 0f; pitch = 0f }
            }
        }
        val focus = if (reducedMotion) spring.snapTo(selection.toFloat()) else spring.step(selection.toFloat(), dt)
        val gridCenter = if (reducedMotion) gridSpring.snapTo(layout.gridCenter(selection, collectionCount.coerceAtLeast(1))) else gridSpring.step(layout.gridCenter(selection, collectionCount.coerceAtLeast(1)), dt)
        val stage = if (reducedMotion) stageSpring.snapTo(presentation) else stageSpring.step(presentation, dt)
        // Vertical stands to the right of its title; Grid keeps three full rows between the top bar and the title.
        fun restX(global: Int, offset: Float) = when (layout) { CollectionLayout.Horizontal -> offset * 1.66f; CollectionLayout.Vertical -> VERTICAL_X; CollectionLayout.Grid -> (global % 3 - 1) * 1.04f }
        fun restY(global: Int, offset: Float) = when (layout) { CollectionLayout.Horizontal -> 0f; CollectionLayout.Vertical -> -offset * 1.12f; CollectionLayout.Grid -> (gridCenter - global / 3) * .54f + .05f }
        val focusX = restX(selection, selection - focus); val focusY = restY(selection, selection - focus)
        resourceLoader.asyncUpdateLoad()
        tallLoader.asyncUpdateLoad()
        discLoader.asyncUpdateLoad()
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
            val count = discAsset.popRenderables(renderables)
            if (count == 0) break
            scene.addEntities(renderables.copyOf(count))
        }
        if (resourceLoader.asyncGetLoadProgress() >= 1f && tallLoader.asyncGetLoadProgress() >= 1f && discLoader.asyncGetLoadProgress() >= 1f && !labelFailure) {
            try { labels.update(); tallLabels.update(); discLabels.update() }
            catch (failure: Exception) {
                labelFailure = true
                android.util.Log.e("CartridgeRenderer", "Label texture preparation failed", failure)
                onLabelError("Cannot prepare this label preview. Try another image.")
            }
        }
        var unlabelled = 0
        for ((format, pool, bound) in listOf(Triple(0, instances, labels), Triple(1, tallInstances, tallLabels), Triple(2, discInstances, discLabels))) pool.forEachIndexed { index, instance ->
            val place = placed[index]
            val entry = place?.entry
            val global = place?.global ?: -1
            val offset = global - focus
            val selected = place != null && global == selection
            val emphasis = if (layout == CollectionLayout.Grid) if (selected) 1f else 0f else (1f - abs(offset)).coerceIn(0f, 1f)
            Matrix.setIdentityM(matrix, 0)
            val pose = MediaMotion.pose(if (selected && !reducedMotion) insertion else 0f)
            var x = restX(global, offset); var y = restY(global, offset)
            if (stage > .001f) {
                if (selected) { x += (STAGE_X - x) * stage; y += (STAGE_Y - y) * stage }
                else {
                    // Neighbours leave along the line from the focused object, so nothing crosses in front of it.
                    val dx = x - focusX; val dy = y - focusY; val length = kotlin.math.sqrt(dx * dx + dy * dy)
                    x += (if (length < .01f) 1f else dx / length) * stage * 6f; y += (if (length < .01f) 0f else dy / length) * stage * 6f
                }
            }
            // The window holds one row above and below the grid's three so a new row arrives labelled; they grow in and out.
            val arrival = if (layout != CollectionLayout.Grid) 1f else (2f - abs(global / 3 - gridCenter)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
            // An object appears only once it carries its own game's label, never a neighbour's or the template's.
            val visible = place != null && global < collectionCount && (selected || stage < .97f) && arrival > 0f &&
                format == format(entry?.system ?: GameSystem.GBA) && (entry == null || bound.shows(index, entry.owner))
            if (entry != null && format == format(entry.system) && global < collectionCount && !bound.shows(index, entry.owner) &&
                (if (layout == CollectionLayout.Grid) arrival > 0f else abs(offset) < 1.5f)) unlabelled++
            Matrix.translateM(matrix, 0, x * (1f - pose.alignment), y * (1f - pose.alignment) + emphasis * lift * .5f + pose.height,
                if (visible) emphasis * .12f else -100f)
            Matrix.rotateM(matrix, 0, (-10f - emphasis * lift * 25f) * (1f - pose.alignment), 1f, 0f, 0f)
            val browseAngle = if (layout == CollectionLayout.Grid) (global % 3 - 1) * -8f else offset.coerceIn(-1f, 1f) * -22f + 8f
            val angle = if (selected) browseAngle + (STAGE_YAW - browseAngle) * stage else browseAngle
            Matrix.rotateM(matrix, 0, angle * (1f - pose.alignment), 0f, 1f, 0f)
            if (selected && (yaw != 0f || pitch != 0f)) {
                Matrix.rotateM(matrix, 0, pitch * (1f - pose.alignment), 1f, 0f, 0f)
                Matrix.rotateM(matrix, 0, yaw * (1f - pose.alignment), 0f, 1f, 0f)
            }
            val browseScale = if (layout == CollectionLayout.Grid) 11.5f + emphasis * 3.5f else 22f + emphasis * 5f
            val restScale = if (selected) browseScale + (STAGE_SCALE - browseScale) * stage else browseScale
            val scale = (restScale + (27f - restScale) * pose.alignment) * when(format) { 1 -> .62f; 2 -> .40f; else -> 1f } * (if (visible) arrival else 1f)
            Matrix.scaleM(matrix, 0, scale, scale, scale)
            engine.transformManager.setTransform(engine.transformManager.getInstance(instance!!.root), matrix)
        }
        unlabelledOnStage = unlabelled
        val chain = swapChain ?: return
        var submitted = false
        if (uiHelper.isReadyToRender && renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            submitted = true
            onLabelsReady(windowStart, List(windowSize) { offset ->
                val index = CollectionLayout.slot(windowStart + offset)
                when (placed[index]?.entry?.system?.let(::format)) { 2 -> discLabels.key(index); 1 -> tallLabels.key(index); 0 -> labels.key(index); else -> null }
            })
            if (!reportedReady && resourceLoader.asyncGetLoadProgress() >= 1f && tallLoader.asyncGetLoadProgress() >= 1f && discLoader.asyncGetLoadProgress() >= 1f) {
                reportedReady = true
                android.util.Log.i("CartridgeRenderer", "Stage ready ${android.os.SystemClock.uptimeMillis() - created} ms after it was created")
                onReady()
            }
        }
        if (submitted) counted++
        if (countedSince == 0L) countedSince = frameTimeNanos
        else if (frameTimeNanos - countedSince >= 1_000_000_000L) { onFrameRate?.invoke(counted); counted = 0; countedSince = frameTimeNanos }
        frameObserver?.invoke(frameTimeNanos, System.nanoTime() - started, submitted)
    }

    fun close() {
        if (closed) return
        closed = true
        pauseRendering()
        uiHelper.detach()
        resourceLoader.asyncCancelLoad()
        tallLoader.asyncCancelLoad()
        discLoader.asyncCancelLoad()
        scene.removeEntities(asset.entities)
        scene.removeEntities(tallAsset.entities)
        scene.removeEntities(discAsset.entities)
        labels.close()
        tallLabels.close()
        discLabels.close()
        assetLoader.destroyAsset(asset)
        assetLoader.destroyAsset(tallAsset)
        assetLoader.destroyAsset(discAsset)
        resourceLoader.destroy()
        tallLoader.destroy()
        discLoader.destroy()
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
    private companion object {
        // World units at the focus plane: the view is about 4.2 wide, so this centres the object in the left half.
        const val STAGE_X = -1.0f
        const val STAGE_Y = .02f
        const val STAGE_YAW = 14f
        const val STAGE_SCALE = 25f
        const val VERTICAL_X = .50f
        // Degrees per second at full deflection; how far the object may tip; rest before it eases home; ease rate.
        const val SPIN_RATE = 210f
        const val TIP_RATE = 130f
        const val TIP_LIMIT = 55f
        const val SPIN_HOLD = 1.2f
        const val SPIN_RETURN = 7f
    }
}
