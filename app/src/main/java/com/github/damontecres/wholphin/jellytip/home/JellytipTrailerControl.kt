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

    val playing get() = url != null

    /**
     * Back while the hero trailer plays with sound on = mute (trailer keeps playing, focus and drawer untouched).
     * Consumes both key events of the press; anything else (muted, not playing, full screen) falls through.
     */
    fun onBackKey(event: android.view.KeyEvent): Boolean {
        if (event.keyCode != android.view.KeyEvent.KEYCODE_BACK) return false
        if (!(playing && sound) && !muteOnUp) return false
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            muteOnUp = true
        } else if (event.action == android.view.KeyEvent.ACTION_UP) {
            muteOnUp = false
            sound = false
        }
        return true
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
    }

    /** Focus moved or page left: stop and reset to the default sound state next time. */
    fun stop() {
        itemId = null
        url = null
        trailerItemId = null
    }

    /** True if [item]'s trailer is playing in the hero. */
    fun isPlayingFor(item: BaseItem) = url != null && itemId == item.id

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
