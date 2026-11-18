package dev.cartridge.ui.design

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.cartridge.library.LibraryGame
import dev.cartridge.storage.CoverFaces
import kotlinx.coroutines.delay
import java.io.File

/** What a backdrop is showing, for the pill that names it. [progress] is the video's place, 0..1, or null for a still. */
class BackdropState {
    var video by mutableStateOf(false)
    var progress by mutableStateOf<Float?>(null)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
}

/**
 * The game's own media behind the UI (§1.5, §5): its screenshot first, crossfading to its muted gameplay video after
 * [dwellMs] of rest; without a screenshot, a blurred, darkened enlargement of the box art; without that, black.
 * [sound] unmutes the video (R3). Nothing is invented.
 */
@Composable fun MediaBackdrop(game: LibraryGame?, faces: CoverFaces?, modifier: Modifier = Modifier, playVideo: Boolean = true,
    sound: Boolean = false, dwellMs: Long = DesignMotion.PREVIEW_DWELL_MS, shot: Int = 0, state: BackdropState = remember { BackdropState() }) {
    val media = LocalGameMedia.current
    val motion = LocalDesignMotion.current
    val shots = remember(game?.id, game?.media) { game?.let { media?.shots(it) }.orEmpty() }
    val ref = shots.getOrNull(shot.coerceAtMost(shots.lastIndex.coerceAtLeast(0)))
    val image = rememberImage(media, ref)
    val video = remember(game?.id) { game?.let { media?.video(it) } }
    Box(modifier.background(Palette.Canvas)) {
        Crossfade(Triple(game?.id, image, faces?.front), Modifier.fillMaxSize(), animationSpec = motion.fade(500), label = "backdrop") { (_, still, front) ->
            when {
                still != null -> Still(still)
                front != null && !front.isRecycled -> Image(front.asImageBitmap(), null, Modifier.fillMaxSize().then(
                    if (Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp) else Modifier).alpha(.5f), contentScale = ContentScale.Crop)
                else -> Box(Modifier.fillMaxSize())
            }
        }
        // The video waits for the focus to rest, then fades in once its first frame is drawn.
        var dwelt by remember(game?.id) { mutableStateOf(false) }
        LaunchedEffect(game?.id, playVideo) { dwelt = false; if (playVideo && video != null) { delay(dwellMs); dwelt = true } }
        if (video != null && playVideo && dwelt) key(video.path) {
            var shown by remember { mutableStateOf(false) }
            val alpha by animateFloatAsState(if (shown) 1f else 0f, motion.fade(600), label = "video")
            LoopingVideo(video, muted = !sound, Modifier.fillMaxSize().alpha(alpha), onFirstFrame = { shown = true }, state = state)
            DisposableEffect(Unit) { onDispose { state.video = false; state.progress = null } }
        } else SideEffect { state.video = false; state.progress = null }
    }
}

@Composable private fun Still(bitmap: Bitmap) {
    if (!bitmap.isRecycled) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
}

/** A muted, looping, centre-cropped video in a TextureView, released when it leaves the composition. */
@Composable fun LoopingVideo(file: File, muted: Boolean, modifier: Modifier = Modifier, onFirstFrame: () -> Unit = {}, state: BackdropState? = null) {
    val holder = remember(file.path) { VideoHolder(file) }
    val first by rememberUpdatedState(onFirstFrame)
    DisposableEffect(holder) { holder.onFirstFrame = { first(); state?.video = true }; onDispose { holder.release() } }
    LaunchedEffect(holder, muted) { holder.muted = muted }
    if (state != null) LaunchedEffect(holder) {
        while (true) {
            holder.progress()?.let { (position, duration) ->
                state.positionMs = position; state.durationMs = duration
                state.progress = if (duration > 0) position.toFloat() / duration else null
            }
            delay(250)
        }
    }
    AndroidView(factory = { context -> TextureView(context).also(holder::attach) }, modifier = modifier)
}

private class VideoHolder(private val file: File) : TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var view: TextureView? = null
    private var released = false
    var onFirstFrame: () -> Unit = {}
    var muted = true
        set(value) { field = value; runCatching { player?.setVolume(if (value) 0f else 1f, if (value) 0f else 1f) } }

    fun attach(texture: TextureView) {
        view = texture
        texture.surfaceTextureListener = this
        texture.surfaceTexture?.let { onSurfaceTextureAvailable(it, texture.width, texture.height) }
    }
    fun progress(): Pair<Long, Long>? = runCatching { player?.let { it.currentPosition.toLong() to it.duration.toLong() } }.getOrNull()

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released || player != null) return
        val out = Surface(texture).also { surface = it }
        player = MediaPlayer().apply {
            runCatching {
                setDataSource(file.path)
                setSurface(out)
                isLooping = true
                setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                setOnPreparedListener { it.start(); fit(it.videoWidth, it.videoHeight) }
                setOnVideoSizeChangedListener { _, w, h -> fit(w, h) }
                setOnInfoListener { _, what, _ -> if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onFirstFrame(); false }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }.onFailure { release() }
        }
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { player?.let { fit(it.videoWidth, it.videoHeight) } }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { release(); return true }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    /** object-fit: cover. */
    private fun fit(videoWidth: Int, videoHeight: Int) {
        val texture = view ?: return
        if (videoWidth <= 0 || videoHeight <= 0 || texture.width <= 0 || texture.height <= 0) return
        val scale = maxOf(texture.width.toFloat() / videoWidth, texture.height.toFloat() / videoHeight)
        texture.setTransform(Matrix().apply {
            setScale(videoWidth * scale / texture.width, videoHeight * scale / texture.height, texture.width / 2f, texture.height / 2f)
        })
    }
    fun release() {
        if (released) return
        released = true
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        surface?.release(); surface = null
    }
}
