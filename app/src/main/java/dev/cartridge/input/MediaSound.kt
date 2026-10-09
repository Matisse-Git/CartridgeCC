package dev.cartridge.input

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import dev.cartridge.R

/** One original contact sound, loaded once per Activity; optional and independent of game audio. */
class MediaSound(context: Context) : AutoCloseable {
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private var ready = false
    private var closed = false
    private val sample: Int
    init {
        pool.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        sample = pool.load(context, R.raw.media_click, 1)
    }
    fun contact(eject: Boolean = false) {
        if (ready && !closed) pool.play(sample, .3f, .3f, 1, 0, if (eject) .9f else 1f)
    }
    override fun close() { if (!closed) { closed = true; pool.release() } }
}
