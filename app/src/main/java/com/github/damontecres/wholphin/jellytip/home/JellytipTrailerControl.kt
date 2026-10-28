package com.github.damontecres.wholphin.jellytip.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.nav.Destination
import java.util.UUID

/**
 * State of the home hero trailer, shared between the hero (plays it) and the card click handlers (OK presses).
 * OK while a trailer plays: 1st press unmutes (hint chip), 2nd opens it full screen from the current position.
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

    /** Set by the player composable: current playback position in ms. */
    var positionProvider: (() -> Long)? = null

    /** After a full-screen trailer the hero must not restart the same item's trailer until focus moves on. */
    var dismissedFor: UUID? = null

    val playing get() = url != null

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
     * OK on [item]. Returns true when the press was consumed (unmute or full screen); false lets the normal action
     * (open details) run.
     */
    fun onOk(
        item: BaseItem,
        navigateTo: (Destination) -> Unit,
    ): Boolean {
        if (!isPlayingFor(item)) return false
        if (!sound) {
            sound = true
            hintTick = System.nanoTime()
            return true
        }
        val id = trailerItemId ?: return false
        val position = positionProvider?.invoke() ?: 0L
        dismissedFor = item.id
        stop()
        navigateTo(Destination.Playback(itemId = id, positionMs = position))
        return true
    }
}
