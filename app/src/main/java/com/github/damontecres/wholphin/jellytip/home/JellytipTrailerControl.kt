package com.github.damontecres.wholphin.jellytip.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.damontecres.wholphin.data.model.BaseItem
import java.util.UUID

/** What an OK (click) on a trailer does. */
enum class TrailerOk { SOUND_ON, START_WITH_SOUND, OPEN_DETAILS }

/**
 * State of the home hero trailer, shared between the hero (plays it) and the home specials that take the player over.
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

    /** The hero buttons have focus: Back returns to the tile (MainActivity must not take it as "mute"). */
    var heroButtonsFocused = false

    /** The hero's own trailer is playing (not a special's video). */
    val isHeroPlaying get() = url != null && owner == null

    /** The hero Sound button: sound for the trailer playing now; the next hero trailer starts from the setting again. */
    fun toggleHeroSound() {
        if (isHeroPlaying) {
            sound = !sound
            heroSoundChoice = sound
        } else {
            // No trailer yet: the choice applies to the next hero trailers (this run of the app), flipping what they'd do now
            heroSoundChoice = !(heroSoundChoice ?: heroSoundSetting)
        }
        com.github.damontecres.wholphin.jellytip.debug.JellytipTrace.player("hero", "sound toggle -> playing=$isHeroPlaying sound=$sound choice=$heroSoundChoice")
    }

    /** The hero Sound button's choice for this run (null = not touched: the settings decide). */
    var heroSoundChoice by mutableStateOf<Boolean?>(null)
        private set

    /** ui.heroSound or the trailer-sound setting (published by the hero): what a hero trailer does without a choice. */
    var heroSoundSetting by mutableStateOf(false)

    /** A special's clip that autoplays on focus (Four favourites, Closet picks) starts as the hero would: Sound button, else ui.heroSound / trailer sound. */
    fun clipAutoSound(): Boolean = heroSoundChoice ?: heroSoundSetting

    /**
     * OK on a special's clip (web Specials.tsx: first press = sound, again = mute): the sound it should have now.
     * Not playing (autoplay off, or ended): start it with sound.
     */
    fun clipOkSound(
        playing: Boolean,
        sound: Boolean,
    ): Boolean = if (playing) !sound else true

    /**
     * OK on a trailer (Ricki 22-Nov): the focus preview stays muted, an explicit OK means sound. Playing muted = sound on;
     * not playing = start it with sound; already playing with sound = the next OK opens the details (Trailer Break).
     */
    fun trailerOk(
        playing: Boolean,
        sound: Boolean,
    ): TrailerOk =
        when {
            !playing -> TrailerOk.START_WITH_SOUND
            !sound -> TrailerOk.SOUND_ON
            else -> TrailerOk.OPEN_DETAILS
        }

    /** OK on [owner]'s trailer tile (Trailer Break): turns the sound on itself; the caller starts / opens for the others. */
    fun specialOk(owner: String): TrailerOk {
        val answer = trailerOk(isPlayingForOwner(owner), sound)
        if (answer == TrailerOk.SOUND_ON) sound = true
        com.github.damontecres.wholphin.jellytip.debug.JellytipTrace.player("special:$owner", "OK -> $answer")
        return answer
    }

    /** The hero found a trailer for this item (its Trailer button shows). */
    var heroTrailerFor by mutableStateOf<UUID?>(null)

    /** The hero's Trailer button asked for this item's trailer with sound (the hero starts it). */
    var heroTrailerRequest by mutableStateOf<UUID?>(null)

    /** OK on the hero's Trailer button for [itemId]: sound on now, or a start with sound; with sound already, nothing. */
    fun heroTrailerOk(itemId: UUID): TrailerOk {
        val answer = trailerOk(isHeroPlaying && this.itemId == itemId, sound)
        when (answer) {
            TrailerOk.SOUND_ON -> sound = true
            TrailerOk.START_WITH_SOUND -> heroTrailerRequest = itemId
            TrailerOk.OPEN_DETAILS -> {}
        }
        com.github.damontecres.wholphin.jellytip.debug.JellytipTrace.player("hero", "trailer OK $itemId -> $answer")
        return answer
    }

    /** What the Sound button shows: the playing trailer's sound, else the choice / setting the next trailer will use. */
    fun heroSoundShown(): Boolean = if (isHeroPlaying) sound else heroSoundChoice ?: heroSoundSetting
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
        if (event.keyCode != android.view.KeyEvent.KEYCODE_BACK || heroButtonsFocused) return false
        return when (event.action) {
            android.view.KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) muteOnUp = playing && sound
                muteOnUp
            }

            android.view.KeyEvent.ACTION_UP -> {
                if (!muteOnUp) return false
                muteOnUp = false
                sound = false
                // Back-mute on the hero's trailer also mutes the next ones (the Sound button turns it back on)
                if (isHeroPlaying && heroSoundChoice == true) heroSoundChoice = false
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

    /** Focus moved or page left: stop and reset to the default sound state next time. */
    fun stop() {
        if (url != null) {
            com.github.damontecres.wholphin.jellytip.debug.JellytipTrace.player(owner?.let { "special:$it" } ?: "hero", "stop $itemId")
        }
        itemId = null
        url = null
        trailerItemId = null
        owner = null
        // The player's volume only follows changes of [sound]: reset it so the next start with sound is a real change (fades in)
        sound = false
    }
}
