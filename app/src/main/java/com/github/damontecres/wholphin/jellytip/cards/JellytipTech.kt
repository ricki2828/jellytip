package com.github.damontecres.wholphin.jellytip.cards

import androidx.compose.runtime.mutableStateMapOf
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Premium formats per item from the companion (Ricki: "content should have a 4k or Atmos badge or other premium
 * format"): `tech: [...]` on `/v1/items/{id}/backdrops`, `techs: {itemId: [...]}` maps on rows/specials/carousels.
 * Tags are the nightly build's ids (atmos, dtsx, lossless, dovi, hdr, uhd, 4k-hdr, imax); display names
 * ("Dolby Atmos", "4K") are accepted too. Tiles show at most a video and an audio badge; details list them all.
 * Nothing here asks the server: it only keeps what payloads already carried.
 */
object JellytipTech {
    private val techs = mutableStateMapOf<UUID, Set<String>>()

    fun set(
        itemId: UUID,
        tags: JSONArray?,
    ) {
        tags ?: return
        set(itemId, (0 until tags.length()).map { tags.optString(it) })
    }

    fun set(
        itemId: UUID,
        tags: List<String>,
    ) {
        val set = tags.flatMap(::normalize).toSet()
        if (techs[itemId] != set) techs[itemId] = set
    }

    /** A `{"<itemId>": [...]}` object from a payload (ids with or without dashes). */
    fun setAll(map: JSONObject?) {
        map ?: return
        map.keys().forEach { key ->
            runCatching { key.toUUID() }.getOrNull()?.let { set(it, map.optJSONArray(key)) }
        }
    }

    /** Snapshot read (recomposes when an answer arrives): the first of [ids] the companion answered for. */
    fun of(vararg ids: UUID?): Set<String> = ids.firstNotNullOfOrNull { id -> id?.let { techs[it] } }.orEmpty()

    /** Tile badges, best first: one video ("4K DV", "4K HDR", "4K", "DV", "HDR"), one audio ("ATMOS", "DTS:X"). */
    fun tileBadges(tags: Set<String>): List<String> {
        val video =
            listOfNotNull(
                "4K".takeIf { UHD in tags },
                when {
                    DOVI in tags -> "DV"
                    HDR in tags -> "HDR"
                    else -> null
                },
            ).joinToString(" ").takeIf { it.isNotEmpty() }
        val audio =
            when {
                ATMOS in tags -> "ATMOS"
                DTSX in tags -> "DTS:X"
                else -> null
            }
        return listOfNotNull(video, audio)
    }

    /** Details info line, e.g. ["4K", "Dolby Vision", "Dolby Atmos"]. */
    fun fullNames(tags: Set<String>): List<String> =
        buildList {
            if (UHD in tags) add("4K")
            if (DOVI in tags) add("Dolby Vision") else if (HDR in tags) add("HDR")
            if (IMAX in tags) add("IMAX")
            if (ATMOS in tags) add("Dolby Atmos")
            if (DTSX in tags) add("DTS:X")
            if (LOSSLESS in tags && ATMOS !in tags && DTSX !in tags) add("Lossless audio")
        }

    /** Pure (unit tested): one payload tag → our ids. "4k-hdr" is both 4K and HDR. */
    fun normalize(raw: String): List<String> =
        when (raw.lowercase().filter { it.isLetterOrDigit() }) {
            "uhd", "4k", "2160p" -> listOf(UHD)
            "4khdr", "uhdhdr" -> listOf(UHD, HDR)
            "dovi", "dv", "dolbyvision" -> listOf(DOVI)
            "hdr", "hdr10", "hdr10plus", "hlg" -> listOf(HDR)
            "atmos", "dolbyatmos" -> listOf(ATMOS)
            "dtsx" -> listOf(DTSX)
            "lossless", "losslessaudio" -> listOf(LOSSLESS)
            "imax" -> listOf(IMAX)
            else -> emptyList()
        }

    const val UHD = "uhd"
    const val HDR = "hdr"
    const val DOVI = "dovi"
    const val ATMOS = "atmos"
    const val DTSX = "dtsx"
    const val LOSSLESS = "lossless"
    const val IMAX = "imax"
}
