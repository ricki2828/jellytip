package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * IPTV-only seasons on the library series page (web 97458f9a / eb04f1d0): seasons the library lacks but the person's
 * IPTV copy of the same show (same TMDB id, own or shared provider, English copy preferred, foreign dubs skipped) has.
 * Shown as "Season N · via IPTV" chips over an episode card strip; OK plays the episode via IPTV.
 */
object IptvSeasons {
    private val LANG_TOKEN = Regex("""[_\-\s]\(?([a-z]{2,3})\)?$""", RegexOption.IGNORE_CASE)
    private val FOREIGN =
        setOf(
            "ger", "de", "deu", "fra", "fre", "fr", "spa", "es", "ita", "it", "por", "pt", "rus", "ru", "tur", "tr", "ara", "ar",
            "pol", "pl", "nld", "dut", "nl", "swe", "sv", "dan", "da", "nor", "no", "fin", "fi", "hin", "hi", "jpn", "ja", "kor", "ko", "chi", "zh",
        )

    /** A provider title ending in a non-English language token ("Show (2023)_ger"): a confirmed foreign dub. */
    fun foreignCopy(title: String): Boolean = LANG_TOKEN.find(title.trim())?.groupValues?.get(1)?.lowercase() in FOREIGN

    fun providerOf(id: String): String = id.substringBefore(':')

    /** The IPTV series that is this library series: same TMDB id, an allowed provider, not a foreign dub. */
    fun pickSeries(
        items: List<IptvVodItem>,
        tmdbId: Int?,
        allowedProviders: Collection<String>,
    ): IptvVodItem? {
        if (tmdbId == null || tmdbId <= 0) return null
        return items.firstOrNull { it.kind == "series" && it.tmdbId == tmdbId && providerOf(it.id) in allowedProviders && !foreignCopy(it.title) }
    }

    /**
     * Seasons the library lacks (number >= 1, at least one episode), duplicate season blocks merged (first copy of an
     * episode wins), in season order, episodes in episode order. Specials never come from IPTV.
     */
    fun onlySeasons(
        library: Collection<Int>,
        iptv: List<IptvSeason>,
    ): List<IptvSeason> {
        val by = LinkedHashMap<Int, LinkedHashMap<Int, IptvEpisode>>()
        for (s in iptv) {
            if (s.season < 1 || s.season in library) continue
            val m = by.getOrPut(s.season) { LinkedHashMap() }
            s.episodes.forEach { e -> m.putIfAbsent(e.episode, e) }
        }
        return by.entries
            .filter { it.value.isNotEmpty() }
            .sortedBy { it.key }
            .map { (n, m) -> IptvSeason(n, m.values.sortedBy { it.episode }) }
    }

    private val cache = ConcurrentHashMap<String, Pair<Long, IptvVodDetail?>>()
    private const val TTL_MS = 10 * 60_000L

    /** The person's IPTV copy of [title] ([tmdbId]) with its seasons; null when there is none or anything fails. */
    suspend fun lookup(
        title: String,
        tmdbId: Int,
    ): IptvVodDetail? {
        val api = JellytipApi.shared ?: return null
        val key = "${api.currentUserKey()}|$tmdbId"
        cache[key]?.let { (at, v) -> if (System.currentTimeMillis() - at < TTL_MS) return v }
        val got =
            try {
                // Providers this person owns or has shared with them (same as the web's season lookup)
                val owned =
                    api.userCall("v1/iptv/providers")?.takeIf { it.first == 200 }?.let { (_, b) ->
                        val a = JSONObject(b).optJSONArray("providers")
                        (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
                    }
                if (owned.isNullOrEmpty()) null else lookupOwned(api, title, tmdbId, owned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        cache[key] = System.currentTimeMillis() to got // no IPTV / not found is cached too: no refetch on every series page
        return got
    }

    private suspend fun lookupOwned(
        api: JellytipApi,
        title: String,
        tmdbId: Int,
        owned: List<String>,
    ): IptvVodDetail? {
        val (code, body) = api.userCall("v1/iptv/vod?kind=series&includeLibrary=1&q=${URLEncoder.encode(title, "UTF-8")}&limit=50") ?: return null
        if (code != 200) return null
        val base = api.companionBase().also { IptvVodParse.imageBase = it }
        val hit = pickSeries(IptvVodParse.items(JSONObject(body).optJSONArray("items"), base), tmdbId, owned) ?: return null
        val (dc, db) = api.userCall("v1/iptv/vod/${URLEncoder.encode(hit.id, "UTF-8")}") ?: return null
        return if (dc == 200) IptvVodParse.detail(db, base) else null
    }

    /** The person's IPTV copy of a library series and the seasons only it has; null when there are none. */
    suspend fun forSeries(
        series: BaseItem,
        librarySeasons: Collection<Int>,
    ): Pair<IptvVodDetail, List<IptvSeason>>? {
        val tmdb =
            series.data.providerIds
                ?.entries
                ?.firstOrNull { it.key.equals("Tmdb", true) }
                ?.value
                ?.toIntOrNull() ?: return null
        val d = lookup(series.name ?: return null, tmdb) ?: return null
        val only = onlySeasons(librarySeasons.toSet(), d.seasons)
        return if (only.isEmpty()) null else d to only
    }
}

@Composable
fun JellytipIptvSeasonsSection(
    series: BaseItem,
    d: IptvVodDetail,
    seasons: List<IptvSeason>,
    modifier: Modifier = Modifier,
    viewModel: JellytipIptvVodViewModel = hiltViewModel(),
) {
    if (seasons.isEmpty()) return
    var sel by remember(d.item.id) { mutableIntStateOf(0) }
    val season = seasons[sel.coerceIn(0, seasons.size - 1)]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        Text(
            "More seasons via IPTV" + (d.item.providerName?.let { " · $it" } ?: ""),
            color = JellytipPalette.Vanilla.copy(alpha = 0.8f),
            fontFamily = JellytipFontFamily,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 64.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 64.dp),
            modifier = Modifier.focusGroup().focusRestorer(),
        ) {
            itemsIndexed(seasons, key = { _, s -> s.season }) { i, s ->
                Surface(
                    onClick = { sel = i },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors =
                        ClickableSurfaceDefaults.colors(
                            containerColor = if (s == season) JellytipPalette.Vanilla.copy(alpha = 0.18f) else Color.Transparent,
                            focusedContainerColor = Color.White,
                            contentColor = JellytipPalette.Vanilla,
                            focusedContentColor = Color.Black,
                        ),
                ) {
                    Text("Season ${s.season} · via IPTV", fontFamily = JellytipFontFamily, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
                }
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 64.dp),
            modifier = Modifier.fillMaxWidth().focusGroup().focusRestorer(),
        ) {
            items(season.episodes, key = { it.id }) { e ->
                IptvEpisodeCard(e) {
                    val resume = e.progress?.takeIf { p -> p.resumable }?.let { p -> (p.positionSecs * 1000).toLong() } ?: 0L
                    JellytipTrace.log("iptv", "series page: IPTV-only S${season.season}E${e.episode} ${e.id}")
                    viewModel.navigationManager.navigateTo(
                        Destination.JellytipIptvPlay(title = "${series.name ?: d.item.title} · ${e.title}", seriesId = d.item.id, episodeId = e.id, resumeMs = resume),
                        "series page IPTV episode OK",
                    )
                }
            }
        }
    }
}

/** An IPTV episode card like the library's: still, "N. Title", progress bar, ✓ when watched (under the card, D-pad reachable). */
@Composable
internal fun IptvEpisodeCard(
    e: IptvEpisode,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF141414), focusedContainerColor = Color(0xFF141414)),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(6.dp))),
            modifier = Modifier.size(240.dp, 135.dp),
        ) {
            e.still?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
            Text(
                text = "${e.episode}. ${e.title}" + (e.runtimeMin?.let { "  ·  ${it}m" } ?: ""),
                color = Color.White,
                fontFamily = JellytipFontFamily,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
            e.progress?.takeIf { it.resumable && it.durationSecs > 0 }?.let { p ->
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth((p.positionSecs / p.durationSecs).toFloat().coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(JellytipPalette.Jelly),
                )
            }
        }
        if (e.progress?.played == true) {
            Text("✓ Watched", color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 11.sp)
        }
    }
}
