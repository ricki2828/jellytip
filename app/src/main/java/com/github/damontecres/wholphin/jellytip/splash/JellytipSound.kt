package com.github.damontecres.wholphin.jellytip.splash

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.annotation.RawRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Plays the pre-rendered splash sounds (see tools/jellytip-sounds/render.py).
 *
 * The player outlives the composable that started it: the Rhodes tail is allowed to ring on after the
 * visuals end. [stop] fades it out quickly, used when the user skips.
 */
object JellytipSound {
    private val main = Handler(Looper.getMainLooper())
    private var current: MediaPlayer? = null

    /** Decode the asset ahead of time so [start] is instant. Call off the main thread. */
    suspend fun prepare(
        context: Context,
        @RawRes resId: Int,
    ): MediaPlayer? =
        withContext(Dispatchers.IO) {
            try {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    context.resources.openRawResourceFd(resId).use { afd ->
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }
                    prepare()
                }
            } catch (ex: Exception) {
                Timber.w(ex, "Jellytip: could not prepare sound %s", resId)
                null
            }
        }

    /** Start a prepared player; releases it when done. Must be called on the main thread. */
    fun start(player: MediaPlayer?) {
        if (player == null) return
        try {
            current?.let { release(it) }
            current = player
            player.setOnCompletionListener {
                release(it)
                if (current === it) current = null
            }
            player.start()
        } catch (ex: Exception) {
            Timber.w(ex, "Jellytip: could not start sound")
            release(player)
        }
    }

    /** Throw away a prepared player that was never started. */
    fun discard(player: MediaPlayer?) {
        if (player != null && player !== current) release(player)
    }

    /** Fade out and release whatever is playing. */
    fun stop() {
        val p = current ?: return
        current = null
        var step = 0
        val steps = 6
        val tick =
            object : Runnable {
                override fun run() {
                    step++
                    try {
                        val v = (1f - step / steps.toFloat()).coerceAtLeast(0f)
                        p.setVolume(v, v)
                    } catch (ex: Exception) {
                        // player already released
                    }
                    if (step >= steps) release(p) else main.postDelayed(this, 30)
                }
            }
        main.post(tick)
    }

    private fun release(p: MediaPlayer) {
        try {
            p.release()
        } catch (ex: Exception) {
            Timber.v(ex, "Jellytip: release failed")
        }
    }
}
