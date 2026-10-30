package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipTrailerControl
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.main.HomePageCardContent
import com.github.damontecres.wholphin.ui.nav.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.ZoneOffset

/*
 * IPTV in Continue Watching / Next Up (~/ai/plans/jellytip-8h-oct30.md, Block 1): `GET /v1/me/continue` (in-progress
 * IPTV films, episodes, recordings) and `GET /v1/me/next` (the next IPTV episode) merged into the library rows by when
 * they were last played. IPTV tiles resume in the IPTV player. A server without the routes changes nothing.
 */

/** One IPTV entry for Continue Watching / Next Up. Times are epoch seconds, positions seconds. */
data class IptvResume(
    /** movie | episode | recording */
    val kind: String,
    val id: String,
    val seriesId: String?,
    val title: String,
    val seriesTitle: String?,
    val season: Int?,
    val episode: Int?,
    val pos: Double,
    val dur: Double,
    val at: Long,
    val poster: String?,
    val backdrop: String?,
    val tech: List<String>,
) {
    /** Tile text: "Series · S1 E2 · Title" for episodes. */
    val label: String
        get() =
            if (kind == "episode" && seriesTitle != null) {
                listOfNotNull(seriesTitle, if (season != null && episode != null) "S$season E$episode" else null, title.takeIf { it != seriesTitle })
                    .joinToString(" · ")
            } else {
                title
            }

    val key get() = "$kind:$id"

    fun asVodItem() =
        IptvVodItem(
            id = id,
            kind = if (kind == "episode") "series" else "movie",
            tmdbId = null,
            title = label,
            year = null,
            quality = emptyList(),
            poster = poster,
            backdrop = backdrop,
            overview = "",
            rating = null,
            providerName = null,
            progress = IptvProgress(pos, dur, false).takeIf { pos > 0 },
            tech = tech,
        )

    /** Where OK goes: the IPTV player at the saved position. */
    fun destination() =
        when (kind) {
            "recording" -> Destination.JellytipIptvPlay(title = label, recordingId = id, resumeMs = (pos * 1000).toLong())
            "episode" -> Destination.JellytipIptvPlay(title = label, seriesId = seriesId, episodeId = id, resumeMs = (pos * 1000).toLong())
            else -> Destination.JellytipIptvPlay(title = label, vodId = id, resumeMs = (pos * 1000).toLong())
        }
}

object IptvResumeParse {
    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    fun items(
        json: String,
        base: String,
    ): List<IptvResume> {
        val a = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (o.str("source") != null && o.str("source") != "iptv") return@mapNotNull null
            val kind = o.str("kind") ?: "movie"
            IptvResume(
                kind = kind,
                id = o.str("id") ?: return@mapNotNull null,
                seriesId = o.str("seriesId"),
                title = o.str("title") ?: return@mapNotNull null,
                seriesTitle = o.str("seriesTitle"),
                season = o.optInt("season", 0).takeIf { it > 0 },
                episode = o.optInt("episode", 0).takeIf { it > 0 },
                pos = o.optDouble("pos", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                dur = o.optDouble("dur", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                at = o.optLong("at", 0L),
                poster = IptvVodParse.image(base, o.str("poster")),
                backdrop = IptvVodParse.image(base, o.str("backdrop")),
                tech = o.optJSONArray("tech")?.let { t -> (0 until t.length()).mapNotNull { t.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty(),
            ).takeIf { kind != "episode" || it.seriesId != null }
        }
    }
}

/** The person's IPTV Continue Watching and Next Up (plain StateFlows; refreshed when Home shows). */
object JellytipIptvResumeStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _continue = MutableStateFlow<List<IptvResume>>(emptyList())
    val continueItems: StateFlow<List<IptvResume>> = _continue
    private val _next = MutableStateFlow<List<IptvResume>>(emptyList())
    val nextItems: StateFlow<List<IptvResume>> = _next

    @Volatile
    private var lastLoad = 0L

    /** Load both lists (at most every 20 s); a missing route or error keeps them as they were. */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastLoad < MIN_GAP_MS) return
        lastLoad = now
        scope.launch {
            val api = JellytipApi.shared ?: return@launch
            val base = runCatching { api.companionBase() }.getOrNull() ?: return@launch
            api.userCall("v1/me/continue?limit=20", timeoutMs = 6_000)?.let { (code, body) ->
                if (code == 200) runCatching { IptvResumeParse.items(body, base) }.getOrNull()?.let { _continue.value = it }
                JellytipTrace.log("iptv", "continue -> $code (${_continue.value.size})")
            }
            api.userCall("v1/me/next", timeoutMs = 6_000)?.let { (code, body) ->
                if (code == 200) runCatching { IptvResumeParse.items(body, base) }.getOrNull()?.let { _next.value = it }
            }
        }
    }

    private const val MIN_GAP_MS = 20_000L
}

/**
 * The IPTV entries for a library row of kind [continueRow] / [nextUpRow] (both for the combined row); empty for other
 * rows. Loads on first use and whenever Home comes back (after watching something).
 */
@Composable
fun rememberIptvResume(
    continueRow: Boolean,
    nextUpRow: Boolean,
): List<IptvResume> {
    if (!continueRow && !nextUpRow) return emptyList()
    LaunchedEffect(Unit) { JellytipIptvResumeStore.refresh() }
    LifecycleResumeEffect(Unit) {
        JellytipIptvResumeStore.refresh()
        onPauseOrDispose { }
    }
    val cont by JellytipIptvResumeStore.continueItems.collectAsState()
    val next by JellytipIptvResumeStore.nextItems.collectAsState()
    return remember(cont, next, continueRow, nextUpRow) {
        (if (continueRow) cont else emptyList()) + (if (nextUpRow) next.filter { n -> cont.none { it.key == n.key } } else emptyList())
    }
}

private sealed interface ResumeTile {
    val at: Long

    data class Lib(
        val item: BaseItem,
        /** Index in the library row (hero / position). */
        val index: Int,
        override val at: Long,
    ) : ResumeTile

    data class Iptv(
        val r: IptvResume,
    ) : ResumeTile {
        override val at get() = r.at
    }
}

/**
 * A Continue Watching / Next Up row with IPTV entries merged in by last played (library items without a date keep
 * their place relative to each other, after dated ones of the same age). Library cards behave as in the plain row.
 */
@Composable
fun JellytipResumeRow(
    title: String,
    library: List<BaseItem?>,
    iptv: List<IptvResume>,
    viewOptions: HomeRowViewOptions,
    onClickLibrary: (Int, BaseItem) -> Unit,
    onLongClickLibrary: (Int, BaseItem) -> Unit,
    onFocusLibrary: (Int) -> Unit,
    onFocusIptv: () -> Unit,
    modifier: Modifier,
    viewModel: JellytipIptvVodViewModel = hiltViewModel(),
) {
    val tiles =
        remember(library, iptv) {
            val lib =
                library.mapIndexedNotNull { i, item ->
                    item ?: return@mapIndexedNotNull null
                    val played = item.data.userData?.lastPlayedDate?.toEpochSecond(ZoneOffset.UTC) ?: 0L
                    ResumeTile.Lib(item, i, played)
                }
            // Stable sort: equal (unknown) dates keep the library's own order
            (lib + iptv.map { ResumeTile.Iptv(it) }).sortedByDescending { it.at }
        }
    val h = viewOptions.heightDp.dp
    ItemRow(
        title = title,
        items = tiles,
        onClickItem = { _, t ->
            when (t) {
                is ResumeTile.Lib -> onClickLibrary(t.index, t.item)
                is ResumeTile.Iptv -> {
                    JellytipTrailerControl.stop()
                    viewModel.navigationManager.navigateTo(t.r.destination(), "continue IPTV ${t.r.kind}")
                }
            }
        },
        onLongClickItem = { _, t ->
            when (t) {
                is ResumeTile.Lib -> onLongClickLibrary(t.index, t.item)
                is ResumeTile.Iptv -> {
                    // Long press: the title's IPTV page (series page for an episode)
                    val id = if (t.r.kind == "episode") t.r.seriesId else t.r.id
                    if (t.r.kind != "recording" && id != null) {
                        viewModel.navigationManager.navigateTo(Destination.JellytipIptvDetails(id, t.r.seriesTitle ?: t.r.title), "continue IPTV details")
                    }
                }
            }
        },
        horizontalPadding = viewOptions.spacing.dp,
        modifier = modifier,
        cardContent = { index, t, cardModifier, onClick, onLongClick ->
            when (t) {
                is ResumeTile.Lib ->
                    HomePageCardContent(
                        index = index,
                        item = t.item,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        viewOptions = viewOptions,
                        modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusLibrary(t.index) },
                    )

                is ResumeTile.Iptv ->
                    IptvVodTile(
                        item = t.r.asVodItem(),
                        width = h * 16f / 9f,
                        height = h,
                        poster = false,
                        onClick = onClick,
                        modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusIptv() },
                    )

                null -> Box(Modifier)
            }
        },
    )
}
