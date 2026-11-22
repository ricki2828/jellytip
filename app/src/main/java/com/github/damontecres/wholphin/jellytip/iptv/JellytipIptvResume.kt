package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.cards.libRef
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
    /** Optional (absent = today's tile): title logo, text-free backdrop, "the backdrop has no text", TMDB ids (later commits). */
    val logo: String? = null,
    val textlessBackdrop: String? = null,
    val backdropTextless: Boolean = false,
    /** Movies: `tmdbId`. */
    val tmdbId: Int? = null,
    /** Episodes: `seriesTmdbId`. */
    val seriesTmdbId: Int? = null,
    /** Films: the library item this title is also in (`libraryId`) and every source (`sources`). Absent = unknown. */
    val libraryId: String? = null,
    val sources: List<IptvSource> = emptyList(),
    /** Episodes: the library series the person can open (`seriesLibraryId`) and its sources (`seriesSources`). */
    val seriesLibraryId: String? = null,
    val seriesSources: List<IptvSource> = emptyList(),
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

    /**
     * The source-neutral Continue Watching tile: an episode is its SERIES title with the library's "E5" corner pill (what the
     * library's episode tile shows), a film is its title; the bar comes from [pos] / [dur] like the library's.
     */
    fun asTileItem() =
        asVodItem().copy(
            title = if (kind == "episode") (seriesTitle ?: title) else title,
            tmdbId = if (kind == "episode") seriesTmdbId else tmdbId,
            logo = logo,
            textlessBackdrop = textlessBackdrop,
            backdropTextless = backdropTextless,
            episodeNumber = if (kind == "episode") episode else null,
            infoLine = com.github.damontecres.wholphin.jellytip.cards.JellytipContinueInfo.forIptv(this),
        )

    /** The library series of an IPTV episode entry (the server's `seriesLibraryId`, else the library entry of `seriesSources`). */
    val seriesLibraryUuid: java.util.UUID?
        get() = if (kind == "episode") com.github.damontecres.wholphin.jellytip.cards.JellytipTitleOpen.libraryIdOf(seriesLibraryId, seriesSources) else null

    /** Where OK goes: the IPTV player at the saved position. */
    fun destination() =
        when (kind) {
            "recording" -> Destination.JellytipIptvPlay(title = label, recordingId = id, resumeMs = (pos * 1000).toLong())
            "episode" -> Destination.JellytipIptvPlay(title = label, seriesId = seriesId, episodeId = id, resumeMs = (pos * 1000).toLong())
            else -> Destination.JellytipIptvPlay(title = label, vodId = id, resumeMs = (pos * 1000).toLong())
        }
}

/**
 * Continue Watching labels and hiding for IPTV entries (web 3059b998 / 7ca4a22f, lib/continueRow.ts). Pure apart from the
 * per-person hidden store.
 */
object IptvContinueRules {
    const val DONE_PCT = 95.0
    const val DONE_LEFT_S = 120.0

    /** "23 min left", "1h 5m left"; under a minute counts as 1 min. */
    fun leftText(seconds: Double): String {
        val m = maxOf(1, Math.ceil(seconds / 60.0).toInt())
        return if (m >= 60) "${m / 60}h" + (if (m % 60 > 0) " ${m % 60}m" else "") + " left" else "$m min left"
    }

    /** "S2 E5 · 23 min left", "23 min left" (film), "Next: S2 E6", or "In progress" when the length is unknown. */
    fun rowLabel(r: IptvResume): String {
        val ep = if (r.kind == "episode" && r.season != null && r.episode != null) "S${r.season} E${r.episode}" else null
        if (r.pos <= 0 && r.kind == "episode") return ep?.let { "Next: $it" } ?: "Up next"
        val left = if (r.dur > 0) leftText(maxOf(0.0, r.dur - r.pos)) else "In progress"
        return listOfNotNull(ep, left).joinToString(" · ")
    }

    /** Finished: from 95 % watched, or under two minutes left (needs a known length). */
    fun nearlyDone(
        pos: Double,
        dur: Double,
    ): Boolean = dur > 0 && (pos / dur * 100 >= DONE_PCT || dur - pos < DONE_LEFT_S)

    /** Still hidden: hidden after it was last played (playing it again brings it back). */
    fun stillHidden(
        r: IptvResume,
        hiddenAt: Long?,
    ): Boolean = hiddenAt != null && r.at <= hiddenAt

    private const val PREFS = "jellytip_continue_hidden"

    private fun prefs(context: android.content.Context) = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun hiddenAt(
        context: android.content.Context,
        user: String,
        key: String,
    ): Long? = prefs(context).getLong("$user|$key", -1L).takeIf { it >= 0 }

    fun hide(
        context: android.content.Context,
        user: String,
        key: String,
        at: Long,
    ) = prefs(context).edit().putLong("$user|$key", at).apply()
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
                logo = IptvVodParse.image(base, IptvVodParse.strictString(o, "logo")),
                textlessBackdrop = IptvVodParse.image(base, IptvVodParse.strictString(o, "textlessBackdrop")),
                backdropTextless = o.opt("backdropTextless") == true,
                tmdbId = (o.opt("tmdbId") as? Number)?.toInt()?.takeIf { it > 0 },
                seriesTmdbId = (o.opt("seriesTmdbId") as? Number)?.toInt()?.takeIf { it > 0 },
                libraryId = o.str("libraryId"),
                sources = IptvVodParse.sources(o.optJSONArray("sources")),
                seriesLibraryId = o.str("seriesLibraryId"),
                seriesSources = IptvVodParse.sources(o.optJSONArray("seriesSources")),
            ).takeIf { kind != "episode" || it.seriesId != null }
                ?.also {
                    // "Go to the show" / "Go to the film" (and every other open of the title) lands on the library page
                    val open = com.github.damontecres.wholphin.jellytip.cards.JellytipTitleOpen
                    if (it.kind == "episode") {
                        it.seriesId?.let { s -> open.rememberFrom(s, "series", it.seriesLibraryId, it.seriesSources) }
                    } else if (it.kind == "movie") {
                        open.rememberFrom(it.id, "movie", it.libraryId, it.sources)
                    }
                }
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
    // Hidden ones (this person, until played again) and finished ones are left out; the tile says what's left / next
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val user = remember { com.github.damontecres.wholphin.jellytip.JellytipApi.shared?.currentUserKey().orEmpty() }
    var hideTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var menuFor by remember { androidx.compose.runtime.mutableStateOf<IptvResume?>(null) }
    val shownIptv =
        remember(iptv, hideTick, library) {
            // One tile per title, library wins: an IPTV film the row also has as a library film (same TMDB id) is left out; an episode
            // would need its series' TMDB id on the library episode, which the item does not carry, so only films are matched today
            com.github.damontecres.wholphin.jellytip.cards.JellytipSourceMerge.filterResume(
                library.filterNotNull().map { it.libRef() },
                iptv,
            ).filter { r ->
                !(r.pos > 0 && IptvContinueRules.nearlyDone(r.pos, r.dur)) &&
                    !IptvContinueRules.stillHidden(r, IptvContinueRules.hiddenAt(ctx, user, r.key))
            }
        }
    menuFor?.let { r ->
        val close = { menuFor = null }
        com.github.damontecres.wholphin.jellytip.JellytipDialog(
            showDialog = true,
            title = r.label,
            dialogItems =
                listOfNotNull(
                    (if (r.kind == "episode") r.seriesId else r.id)?.takeIf { r.kind != "recording" }?.let { id ->
                        com.github.damontecres.wholphin.ui.components.DialogItem(if (r.kind == "episode") "Go to the show" else "Go to the film", dismissOnClick = true) {
                            viewModel.navigationManager.navigateTo(Destination.JellytipIptvDetails(id, r.seriesTitle ?: r.title), "continue IPTV details")
                        }
                    },
                    com.github.damontecres.wholphin.ui.components.DialogItem("Start from beginning", dismissOnClick = true) {
                        JellytipTrailerControl.stop()
                        viewModel.navigationManager.navigateTo(r.copy(pos = 0.0).destination(), "continue IPTV restart")
                    },
                    com.github.damontecres.wholphin.ui.components.DialogItem("Mark as watched", dismissOnClick = true) {
                        com.github.damontecres.wholphin.jellytip.me.JellytipMe.setIptvPlayed(r.id, true) { err ->
                            if (err != null) android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_SHORT).show()
                        }
                        IptvContinueRules.hide(ctx, user, r.key, System.currentTimeMillis() / 1000)
                        hideTick++
                    }.takeIf { r.kind != "recording" },
                    com.github.damontecres.wholphin.ui.components.DialogItem("Hide from Continue Watching", dismissOnClick = true) {
                        IptvContinueRules.hide(ctx, user, r.key, System.currentTimeMillis() / 1000)
                        hideTick++
                    },
                ),
            onDismissRequest = close,
            dismissOnClick = true,
        )
    }
    val tiles =
        remember(library, shownIptv) {
            val lib =
                library.mapIndexedNotNull { i, item ->
                    item ?: return@mapIndexedNotNull null
                    val played = item.data.userData?.lastPlayedDate?.toEpochSecond(ZoneOffset.UTC) ?: 0L
                    ResumeTile.Lib(item, i, played)
                }
            // Stable sort: equal (unknown) dates keep the library's own order
            (lib + shownIptv.map { ResumeTile.Iptv(it) }).sortedByDescending { it.at }
        }
    val h = viewOptions.heightDp.dp
    // The library tiles of this row draw the same info line the IPTV tiles do ("23 min left", "S2 E5 · 23 min left", "Next: S2 E6")
    androidx.compose.runtime.CompositionLocalProvider(
        com.github.damontecres.wholphin.jellytip.cards.LocalJellytipTileInfo provides com.github.damontecres.wholphin.jellytip.cards.JellytipContinueInfo::forLibrary,
    ) {
    ItemRow(
        title = title,
        items = tiles,
        onClickItem = { _, t ->
            when (t) {
                is ResumeTile.Lib -> onClickLibrary(t.index, t.item)
                is ResumeTile.Iptv -> {
                    JellytipTrailerControl.stop()
                    // An episode the library also has plays from the library (seeks, subtitles) at the IPTV copy's position
                    viewModel.openResume(t.r)
                }
            }
        },
        onLongClickItem = { _, t ->
            when (t) {
                is ResumeTile.Lib -> onLongClickLibrary(t.index, t.item)
                // Long press: go to the title, start over, mark watched, hide (web 3059b998)
                is ResumeTile.Iptv -> menuFor = t.r
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
                        // As the library's Continue Watching tile: the title set in the art, "E5" in the corner pill, the bar
                        item = t.r.asTileItem(),
                        width = h * 16f / 9f,
                        height = h,
                        poster = false,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        modifier = cardModifier.onFocusChanged { if (it.isFocused) onFocusIptv() },
                    )

                null -> Box(Modifier)
            }
        },
    )
    }
}

/** The library copy of an IPTV episode: same season and episode number (Specials = season 0 never matched by a missing season). */
object IptvResumeLibrary {
    /** How long OK waits for the library lookup before playing the IPTV copy (inside the nav gate's OK window). */
    const val LOOKUP_MS = 1_500L

    data class Ep(
        val id: java.util.UUID,
        val season: Int?,
        val episode: Int?,
    )

    fun pick(
        eps: List<Ep>,
        season: Int?,
        episode: Int?,
    ): java.util.UUID? {
        if (season == null || episode == null) return null
        return eps.firstOrNull { it.season == season && it.episode == episode }?.id
    }
}
