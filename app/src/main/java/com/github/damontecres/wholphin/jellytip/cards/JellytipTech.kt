package com.github.damontecres.wholphin.jellytip.cards

import androidx.compose.runtime.mutableStateMapOf
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Premium formats per item from the companion (Ricki: "content should have a 4k or Atmos badge or other premium
 * format"): `tech: [...]` on `/v1/items/{id}/backdrops`, `techs: {itemId: [...]}` maps on rows/specials/carousels.
 * Contract ("Tech badges", ~/ai/plans/jellytip-10x.md): 4K, DV, HDR10+, HDR, ATMOS, DTSX, TRUEHD, DTSHDMA; the
 * nightly build's row tags and display names are accepted too. Tiles show at most a video and an audio badge;
 * details list them all. A missing field means none.
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
        if (techs[itemId] != set) com.github.damontecres.wholphin.jellytip.jellytipGlobalWrite { techs[itemId] = set }
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

    /** Tile badges, best first: one video ("4K DV", "4K HDR10+", "4K HDR", "4K", "DV"...), one audio ("ATMOS", "DTS:X"). */
    fun tileBadges(tags: Set<String>): List<String> {
        val video =
            listOfNotNull(
                "4K".takeIf { UHD in tags },
                when {
                    DOVI in tags -> "DV"
                    HDR10_PLUS in tags -> "HDR10+"
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

    /** Details info line, the full merged list, e.g. ["4K", "Dolby Vision", "Dolby Atmos"]. */
    fun fullNames(tags: Set<String>): List<String> =
        buildList {
            if (UHD in tags) add("4K")
            if (DOVI in tags) add("Dolby Vision")
            if (HDR10_PLUS in tags) add("HDR10+") else if (HDR in tags && DOVI !in tags) add("HDR")
            if (IMAX in tags) add("IMAX")
            if (ATMOS in tags) add("Dolby Atmos")
            if (DTSX in tags) add("DTS:X")
            if (TRUEHD in tags && ATMOS !in tags) add("Dolby TrueHD")
            if (DTSHDMA in tags && DTSX !in tags) add("DTS-HD MA")
            if (LOSSLESS in tags && ATMOS !in tags && DTSX !in tags && TRUEHD !in tags && DTSHDMA !in tags) add("Lossless audio")
        }

    /** Pure (unit tested): one payload tag → our ids. "4k-hdr" is both 4K and HDR. */
    fun normalize(raw: String): List<String> =
        when (raw.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }) {
            "uhd", "4k", "2160p" -> listOf(UHD)
            "4khdr", "uhdhdr" -> listOf(UHD, HDR)
            "dovi", "dv", "dolbyvision" -> listOf(DOVI)
            "hdr10plus" -> listOf(HDR10_PLUS)
            "hdr", "hdr10", "hlg" -> listOf(HDR)
            "atmos", "dolbyatmos" -> listOf(ATMOS)
            "dtsx" -> listOf(DTSX)
            "truehd", "dolbytruehd" -> listOf(TRUEHD)
            "dtshdma", "dtshd" -> listOf(DTSHDMA)
            "lossless", "losslessaudio" -> listOf(LOSSLESS)
            "imax" -> listOf(IMAX)
            else -> emptyList()
        }

    const val UHD = "uhd"
    const val HDR = "hdr"
    const val HDR10_PLUS = "hdr10plus"
    const val TRUEHD = "truehd"
    const val DTSHDMA = "dtshdma"
    const val DOVI = "dovi"
    const val ATMOS = "atmos"
    const val DTSX = "dtsx"
    const val LOSSLESS = "lossless"
    const val IMAX = "imax"
}
