package com.github.damontecres.wholphin.jellytip.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.damontecres.wholphin.data.model.BaseItem
import java.util.UUID

/**
 * State of the home hero trailer, shared between the hero (plays it) and the card click handlers (OK presses).
 * OK while a trailer plays: 1st press unmutes (hint chip), the next opens details as usual.
 * One home page is on screen at a time, so a process-wide holder is enough.
 */
object JellytipTrailerControl {
    /** Item the hero is playing a trailer for, with the stream [url] and the trailer's own item id. */
    var itemId by mutableStateOf<UUID?>(null)
        private set
    var url by mutableStateOf<String?>(null)
        private set
    private var trailerItemId: UUID? = null

    var sound by mutableStateOf(false)
    var hintTick by mutableLongStateOf(0L) // bumped each time the "sound on" hint should show

    /**
     * Who shows the video: null = the hero; otherwise a home "special" (Trailer Break, Closet Picks) that took the one
     * home player over. Only the owner draws a PlayerSurface for it.
     */
    var owner by mutableStateOf<String?>(null)
        private set

    /** The one home ExoPlayer (created by the hero on first use), so a special can draw it in its own frame. */
    var player by mutableStateOf<androidx.media3.common.Player?>(null)

    /**
     * Id of the video special (Trailer Break, Closet Picks) that holds focus, or null. While set, the hero must not start
     * (or keep) its own trailer. An id, not a flag, so moving straight from one video special to another cannot clear it.
     */
    var focusedSpecial by mutableStateOf<String?>(null)

    val specialVideoFocused get() = focusedSpecial != null

    val playing get() = url != null

    /**
     * Back while the hero trailer plays with sound on = mute (trailer keeps playing, focus and drawer untouched).
     * Consumes both key events of the press, or neither: the key-up is taken only when this press's key-down was.
     * (29-Oct P0: a trailer that started with sound between the key-down and key-up of a Back press took only the
     * key-up; the system's predictive back had already started on the key-down and never finished, which froze the
     * screen on the previous page while the back stack moved on.)
     */
    fun onBackKey(event: android.view.KeyEvent): Boolean {
        if (event.keyCode != android.view.KeyEvent.KEYCODE_BACK) return false
        return when (event.action) {
            android.view.KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) muteOnUp = playing && sound
                muteOnUp
            }

            android.view.KeyEvent.ACTION_UP -> {
                if (!muteOnUp) return false
                muteOnUp = false
                sound = false
                true
            }

            else -> muteOnUp
        }
    }

    private var muteOnUp = false

    fun start(
        item: BaseItem,
        url: String,
        trailerItemId: UUID,
        sound: Boolean,
    ) {
        this.itemId = item.id
        this.url = url
        this.trailerItemId = trailerItemId
        this.sound = sound
        this.owner = null
        com.github.damontecres.wholphin.jellytip.debug.JellytipTrace.player("hero", "start ${item.id} ${item.name} sound=$sound")
    }

    /** A special takes the home player over for [url] (its frame draws the surface; the hero shows its still). */
    fun startSpecial(
        owner: String,
        url: String,
        sound: Boolean,
        itemId: UUID? = null,
    ) {
        this.itemId = itemId
        this.url = url
        this.trailerItemId = null
        this.sound = sound
        this.owner = owner
        com.github.damontecres.wholphin.jellytip.debug.JellytipTrace.player("special:$owner", "start $itemId sound=$sound")
    }

    /** True while [owner]'s video is playing. */
    fun isPlayingForOwner(owner: String) = url != null && this.owner == owner

    /** Stop only if [owner] (null = hero) currently holds the player, so the hero never stops a special's video. */
    fun stopIfOwner(owner: String?) {
        if (this.owner == owner) stop()
    }

    /**
     * OK on a video special: unmute when its video plays muted (hint chip) and return true; false = sound already on or not
     * playing, so the caller does its normal action.
     */
    fun onOkOwner(owner: String): Boolean {
        if (!isPlayingForOwner(owner) || sound) return false
        sound = true
        hintTick = System.nanoTime()
        return true
    }

    /** Focus moved or page left: stop and reset to the default sound state next time. */
    fun stop() {
        if (url != null) {
            com.github.damontecres.wholphin.jellytip.debug.JellytipTrace.player(owner?.let { "special:$it" } ?: "hero", "stop $itemId")
        }
        itemId = null
        url = null
        trailerItemId = null
        owner = null
    }

    /** True if [item]'s trailer is playing in the hero. */
    fun isPlayingFor(item: BaseItem) = url != null && owner == null && itemId == item.id

    /**
     * OK on [item] while its trailer plays muted = unmute (stay on home, hint chip). Returns true when consumed;
     * false (sound already on, or no trailer) lets the normal action run: open details.
     */
    fun onOk(item: BaseItem): Boolean {
        if (!isPlayingFor(item) || sound) return false
        sound = true
        hintTick = System.nanoTime()
        return true
    }
}
