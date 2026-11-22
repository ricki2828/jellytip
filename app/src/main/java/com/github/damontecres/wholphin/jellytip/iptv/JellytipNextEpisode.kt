package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID

/**
 * Loading for [NextEpisode] (t5n 22-Nov): the IPTV series' seasons and the library series with the same TMDB id. The
 * library part is time-boxed; any failure = no library episodes (IPTV only), never an error on screen.
 */
object NextEpisodeLoader {
    const val LIBRARY_TIMEOUT_MS = 4_000L

    /** What the players need to offer and play the next episode. */
    data class UpNext(
        val ep: NextEpisode.Ep,
        /** The series name for the IPTV player's label ("Show · Episode"). */
        val seriesTitle: String,
        /** The IPTV series id (for an IPTV next). */
        val iptvSeriesId: String?,
        /** Where an IPTV next resumes (its own part-watched point), ms. */
        val resumeMs: Long = 0,
    ) {
        val label: String get() = "$seriesTitle · ${ep.title}"
    }

    private fun tmdbOf(ids: Map<String, String?>?): Int? = ids?.entries?.firstOrNull { it.key.equals("Tmdb", true) }?.value?.toIntOrNull()

    /** A library series' real episodes (no "missing" / unaired placeholders), as [NextEpisode.Ep]s. */
    private suspend fun libraryEpisodes(
        api: ApiClient,
        userId: UUID?,
        seriesId: UUID,
    ): List<NextEpisode.Ep> =
        api.tvShowsApi
            .getEpisodes(seriesId = seriesId, userId = userId)
            .content.items
            .filter { it.locationType != LocationType.VIRTUAL }
            .mapNotNull { e ->
                val s = e.parentIndexNumber ?: return@mapNotNull null
                val n = e.indexNumber ?: return@mapNotNull null
                // Its own resume point (Jellyfin clears it once played): ticks are 100 ns
                val resume = (e.userData?.playbackPositionTicks ?: 0L).takeIf { e.userData?.played != true }?.div(10_000) ?: 0L
                NextEpisode.Ep(NextEpisode.Source.LIBRARY, e.id.toString(), s, n, e.name ?: "Episode $n", resume)
            }

    /**
     * The library series with [tmdbId] (the SDK has no provider-id filter: title search, then TMDB match). The IPTV title
     * without its decorations first, then as given.
     */
    private suspend fun librarySeries(
        api: ApiClient,
        userId: UUID?,
        title: String,
        tmdbId: Int,
    ): UUID? {
        val clean = UpNextTiming.searchTitle(title)
        return searchSeries(api, userId, clean, tmdbId) ?: if (clean != title.trim()) searchSeries(api, userId, title.trim(), tmdbId) else null
    }

    private suspend fun searchSeries(
        api: ApiClient,
        userId: UUID?,
        title: String,
        tmdbId: Int,
    ): UUID? =
        api.itemsApi
            .getItems(
                GetItemsRequest(
                    userId = userId,
                    searchTerm = title,
                    includeItemTypes = listOf(BaseItemKind.SERIES),
                    recursive = true,
                    fields = listOf(ItemFields.PROVIDER_IDS),
                    enableUserData = false,
                    limit = 20,
                ),
            ).content.items
            .firstOrNull { tmdbOf(it.providerIds) == tmdbId }
            ?.id

    /** The library's episodes of the same show as this IPTV series; empty when there is none, on timeout or failure. */
    private suspend fun libraryFor(
        api: ApiClient,
        userId: UUID?,
        d: IptvVodDetail,
    ): List<NextEpisode.Ep> {
        val tmdb = d.item.tmdbId ?: return emptyList()
        return try {
            withTimeoutOrNull(LIBRARY_TIMEOUT_MS) {
                val id = librarySeries(api, userId, d.item.title, tmdb) ?: return@withTimeoutOrNull emptyList()
                libraryEpisodes(api, userId, id)
            } ?: emptyList<NextEpisode.Ep>().also { JellytipTrace.log("player", "next episode: library timed out, IPTV only") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "next episode: library lookup failed")
            emptyList()
        }
    }

    private fun resumeOf(
        d: IptvVodDetail,
        id: String,
    ): Long =
        d.seasons
            .firstNotNullOfOrNull { s -> s.episodes.firstOrNull { it.id == id } }
            ?.progress
            ?.takeIf { it.resumable && !it.played }
            ?.let { (it.positionSecs * 1000).toLong() } ?: 0L

    /**
     * IPTV player: the episode after [episodeId] of the IPTV series [d] (library episodes of the same show merged in by
     * the split-series rule); null when it's the last, or the episode isn't in the series.
     */
    suspend fun afterIptv(
        api: ApiClient,
        userId: UUID?,
        d: IptvVodDetail,
        episodeId: String,
    ): UpNext? {
        val current = NextEpisode.iptvEpisode(d.seasons, episodeId) ?: return null
        val next = NextEpisode.after(current, libraryFor(api, userId, d), d.seasons) ?: return null
        val resume = if (next.source == NextEpisode.Source.IPTV) resumeOf(d, next.id) else next.resumeMs
        return UpNext(next, d.item.title, d.item.id, resume)
    }

    /**
     * Library player at the end of an episode with no library next: the IPTV-only episode after it, if the series has
     * an IPTV copy (same lookup as the series page's IPTV seasons). Time-boxed; null on anything else.
     */
    suspend fun iptvAfterLibrary(
        api: ApiClient,
        userId: UUID?,
        seriesId: UUID,
        season: Int,
        episode: Int,
    ): UpNext? =
        try {
            withTimeoutOrNull(LIBRARY_TIMEOUT_MS) {
                val series = api.userLibraryApi.getItem(itemId = seriesId).content
                val tmdb = tmdbOf(series.providerIds) ?: return@withTimeoutOrNull null
                val d = IptvSeasons.lookup(series.name ?: return@withTimeoutOrNull null, tmdb) ?: return@withTimeoutOrNull null
                val library = libraryEpisodes(api, userId, seriesId)
                val current = NextEpisode.Ep(NextEpisode.Source.LIBRARY, "", season, episode, "")
                // Only an IPTV next: a library next is the library player's own business (its playlist may be short)
                NextEpisode
                    .after(current, library, d.seasons)
                    ?.takeIf { it.source == NextEpisode.Source.IPTV }
                    ?.let { UpNext(it, series.name ?: d.item.title, d.item.id, resumeOf(d, it.id)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "next episode: IPTV lookup after library failed")
            null
        }

    fun trace(
        up: UpNext,
        how: String,
    ) = JellytipTrace.log("player", "next episode ${up.ep.code} via ${up.ep.source.name.lowercase()} ($how)")

    fun traceCancelled(up: UpNext) = JellytipTrace.log("player", "next cancelled ${up.ep.code}")
}

/**
 * The Up next card for both players (wording like the library player's "Next up..." card; spoiler-free, no still): the
 * episode code and title, a play badge with the countdown, and "via IPTV" when the next comes from the other source. OK
 * plays it now (handled by the page's key handler), Back cancels. Bottom-right, inside the 5% safe area.
 */
@Composable
internal fun JellytipUpNextCard(
    up: NextEpisodeLoader.UpNext,
    secondsLeft: Long?,
    crossover: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .width(400.dp)
                .background(Color(0xF2161616), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        // The "focused" play badge (OK = play now): paper on ink, like the overlay's focused button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(56.dp).background(Color(0xFFF2F2F0), CircleShape),
        ) {
            if (secondsLeft != null && secondsLeft > 0) {
                Text("${secondsLeft}s", color = Color(0xFF0A0A0A), fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 18.sp)
            } else {
                Icon(painterResource(R.drawable.jt_play), contentDescription = null, tint = Color(0xFF0A0A0A), modifier = Modifier.size(24.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Text("Next up...", color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 14.sp)
            Text(
                "${up.ep.code} · ${up.ep.title}",
                color = JellytipPalette.Vanilla,
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val hint =
                listOfNotNull(
                    "via IPTV".takeIf { crossover && up.ep.source == NextEpisode.Source.IPTV },
                    "in your library".takeIf { crossover && up.ep.source == NextEpisode.Source.LIBRARY },
                    "OK: play now",
                    "Back: cancel",
                ).joinToString("  ·  ")
            Text(hint, color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 12.sp, maxLines = 1)
        }
    }
}
