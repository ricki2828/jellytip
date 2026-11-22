package com.github.damontecres.wholphin.jellytip.cards

import androidx.compose.runtime.compositionLocalOf
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvContinueRules
import com.github.damontecres.wholphin.jellytip.iptv.IptvResume
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * The Continue Watching / Next Up info line, the same text on library and IPTV tiles: "23 min left", "S2 E5 · 23 min left"
 * (episode in progress), "Next: S2 E6" (episode not started). Unknown runtime = only the episode part; a film with nothing
 * to say = null. Pure (no Compose).
 */
object JellytipContinueInfo {
    private const val TICKS_PER_SEC = 10_000_000.0

    fun text(
        isEpisode: Boolean,
        season: Int?,
        episode: Int?,
        posSecs: Double,
        durSecs: Double,
    ): String? {
        val ep = if (isEpisode && season != null && episode != null) "S$season E$episode" else null
        if (posSecs <= 0 && isEpisode) return ep?.let { "Next: $it" }
        val left = if (durSecs > 0 && posSecs > 0) IptvContinueRules.leftText(maxOf(0.0, durSecs - posSecs)) else null
        return listOfNotNull(ep, left).joinToString(" · ").ifBlank { null }
    }

    fun forLibrary(item: BaseItem): String? {
        val d = item.data
        val ud = d.userData
        return text(
            isEpisode = item.type == BaseItemKind.EPISODE,
            season = d.parentIndexNumber,
            episode = d.indexNumber,
            posSecs = (ud?.playbackPositionTicks ?: 0L) / TICKS_PER_SEC,
            durSecs = (d.runTimeTicks ?: 0L) / TICKS_PER_SEC,
        )
    }

    fun forIptv(r: IptvResume): String? = text(r.kind == "episode", r.season, r.episode, r.pos, r.dur)
}

/**
 * Provided ONLY around the Continue Watching / Next Up row: the info line for a library item drawn through the library card
 * path ([JellytipTitledImage]). Null (the default) everywhere else = no line.
 */
val LocalJellytipTileInfo = compositionLocalOf<((BaseItem) -> String?)?> { null }
