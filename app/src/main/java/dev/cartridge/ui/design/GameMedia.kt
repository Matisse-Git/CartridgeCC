package dev.cartridge.ui.design

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import dev.cartridge.domain.Media
import dev.cartridge.library.LabelRef
import dev.cartridge.library.LibraryGame
import dev.cartridge.library.isCaseArt
import dev.cartridge.storage.CoverFaces
import dev.cartridge.storage.LabelStore
import dev.cartridge.storage.VideoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The real media a game has on this device: its case art, the screenshots and video a scrape stored. Nothing here
 * invents art; a game without any is drawn as the placeholder box and a black backdrop.
 */
class GameMedia(private val labels: LabelStore, private val videos: VideoStore) {
    private val decoding = Dispatchers.IO.limitedParallelism(3)
    private val faces = object : LruCache<String, CoverFaces>(72 * 1024 * 1024) {
        override fun sizeOf(key: String, value: CoverFaces) = value.bytes
    }
    private val images = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Only case art is printed on a box; a cartridge label stays stored and the box shows its placeholder. */
    fun boxArt(label: LabelRef?): LabelRef? = label?.takeIf { it.isCaseArt }

    fun cachedFaces(label: LabelRef, media: Media) = faces.get(key(label, media))
    suspend fun faces(label: LabelRef, media: Media): CoverFaces? {
        val key = key(label, media)
        faces.get(key)?.let { return it }
        return withContext(decoding) { runCatching { labels.faces(label, media, FACE_HEIGHT) }.getOrNull() }?.also { faces.put(key, it) }
    }

    /** Screenshots in the order the backdrop and the strip use them: in-game first, then the title screen, then fan art. */
    fun shots(game: LibraryGame): List<LabelRef> = SHOT_TYPES.mapNotNull { game.media[it]?.image }
    fun video(game: LibraryGame): File? = videos.file(game.id)

    fun cachedImage(ref: LabelRef) = images.get(ref.assetId)
    suspend fun image(ref: LabelRef, width: Int = 1280): Bitmap? {
        images.get(ref.assetId)?.let { return it }
        return withContext(decoding) {
            val file = labels.file(ref.assetId) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
        }?.also { images.put(ref.assetId, it) }
    }

    private fun key(label: LabelRef, media: Media) = "${label.assetId}:${label.provenance?.type}:${media.name}"

    companion object {
        /** Tall enough for the focused box at 1.24× on a 1080p screen. */
        const val FACE_HEIGHT = 560
        val SHOT_TYPES = listOf("ss", "sstitle", "fanart")
    }
}

val LocalGameMedia = staticCompositionLocalOf<GameMedia?> { null }

@Composable fun rememberFaces(media: GameMedia?, label: LabelRef?, shape: Media): CoverFaces? {
    val art = media?.boxArt(label)
    return produceState(art?.let { media.cachedFaces(it, shape) }, art?.assetId, art?.provenance?.type, shape) {
        value = art?.let { media.faces(it, shape) }
    }.value
}

@Composable fun rememberImage(media: GameMedia?, ref: LabelRef?, width: Int = 1280): Bitmap? =
    produceState(ref?.let { media?.cachedImage(it) }, ref?.assetId) { value = ref?.let { media?.image(it, width) } }.value
