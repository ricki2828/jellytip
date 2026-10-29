package com.github.damontecres.wholphin.jellytip.cards

import androidx.compose.runtime.mutableStateMapOf
import java.util.UUID

/**
 * Which items have a textless Thumb (the companion's `thumbTitled: false`, or a home special's `untitled` list). Tiles
 * that draw such a Thumb overlay the title themselves (logo if there is one, else text); titled or unknown Thumbs are
 * drawn as they are. Snapshot state, so tiles already on screen pick up a late answer.
 */
object JellytipThumbTitles {
    private val untitled = mutableStateMapOf<UUID, Boolean>()

    /** Record the companion's answer for [itemId]: false = textless, true = titled, null = unknown (forget). */
    fun set(
        itemId: UUID,
        titled: Boolean?,
    ) {
        if (titled == false) {
            if (untitled[itemId] != true) untitled[itemId] = true
        } else if (untitled.containsKey(itemId)) {
            untitled.remove(itemId)
        }
    }

    fun markUntitled(ids: Collection<UUID>) {
        ids.forEach { set(it, false) }
    }

    /** True when [itemId]'s Thumb is known to carry no title text. */
    fun isUntitled(itemId: UUID): Boolean = untitled[itemId] == true
}
