package com.github.damontecres.wholphin.jellytip.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsAnswer
import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsService
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/** Rating → age, as the server's map (and the web's lib/kids.ts). Pure; unit tested. Unknown or missing = 18. */
object KidsRatings {
    private val AGE =
        mapOf(
            "G" to 0, "U" to 0, "E" to 0, "TV-Y" to 0, "TV-G" to 0, "ALL" to 0,
            "PG" to 7, "TV-Y7" to 7, "TV-Y7-FV" to 7, "TV-PG" to 7,
            "PG-13" to 13, "12" to 13, "12A" to 13, "M" to 13, "R13" to 13, "RP13" to 13, "TV-14" to 13,
            "15" to 16, "R15" to 16, "R16" to 16, "RP16" to 16, "MA15+" to 16, "MA" to 16,
            "R" to 18, "R18" to 18, "18" to 18, "R18+" to 18, "NC-17" to 18, "X18+" to 18, "TV-MA" to 18, "X" to 18,
        )
    private val countryPrefix = Regex("""^[A-Z]{2}[-/:\s]+(?=\S)""")

    /** "NZ-R16" → 16, "GB-15" → 16, "PG-13" → 13 (a rating, not a prefix); unknown → 18. */
    fun age(rating: String?): Int {
        if (rating.isNullOrBlank()) return 18
        val r = rating.trim().uppercase(Locale.ROOT)
        return AGE[r] ?: AGE[r.replace(countryPrefix, "")] ?: 18
    }

    fun allowed(
        rating: String?,
        maxAge: Int,
    ): Boolean = age(rating) <= maxAge
}

data class KidsState(
    val active: Boolean,
    val maxAge: Int,
)

/**
 * Kids profile on the TV: defence in depth (the companion filters rows/specials/search; real enforcement is a Jellyfin
 * kids account with a parental-rating policy). Screens that read Jellyfin directly are closed; titles above [maxAge]
 * refuse details and playback; Continue Watching is filtered by rating (episodes take their series' rating).
 */
object JellytipKids {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<KidsState> =
        JellytipMe.prefs
            .map { p -> KidsState(p?.kidsEnabled == true, p?.kidsMaxAge ?: 18) }
            .stateIn(scope, SharingStarted.Eagerly, KidsState(false, 18))

    val active: Boolean get() = JellytipMe.prefs.value?.kidsEnabled == true
    val maxAge: Int get() = JellytipMe.prefs.value?.kidsMaxAge ?: 18

    /** Series id → its OfficialRating ("" = none), filled on demand for episodes that carry none. */
    val seriesRatings = mutableStateMapOf<UUID, String>()
    private val asked = HashSet<UUID>()

    /** Screens a kids profile never opens (they read Jellyfin or the provider directly, outside the server's filter). */
    fun blocked(d: Destination): Boolean =
        when (d) {
            is Destination.MediaItem ->
                d.type in
                    setOf(
                        BaseItemKind.PERSON, BaseItemKind.BOX_SET, BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW,
                        BaseItemKind.FOLDER, BaseItemKind.PLAYLIST, BaseItemKind.TV_CHANNEL, BaseItemKind.PROGRAM,
                    )
            is Destination.Favorites, is Destination.Discover, is Destination.DiscoveredItem, is Destination.DiscoverMoreResult,
            is Destination.JellytipDiscover, is Destination.JellytipCollection, is Destination.JellytipList,
            is Destination.JellytipLiveTv, is Destination.JellytipLiveTvPlayer, is Destination.JellytipIptvPlay,
            is Destination.JellytipIptvDetails, is Destination.Recordings,
            -> true
            else -> false
        }

    /** A title's rating for the kids check: its own, else (episodes, seasons) its series'. Null = not known yet. */
    fun ratingOf(item: BaseItem): String? {
        item.data.officialRating?.takeIf { it.isNotBlank() }?.let { return it }
        val sid = item.data.seriesId ?: return ""
        return seriesRatings[sid]
    }

    fun allows(item: BaseItem): Boolean {
        val r = ratingOf(item) ?: return false
        return KidsRatings.allowed(r.ifBlank { null }, maxAge)
    }

    /** Ask Jellyfin once for the ratings of these series (episodes in Continue Watching often carry none). */
    fun fetchSeriesRatings(
        api: ApiClient,
        ids: Collection<UUID>,
    ) {
        val todo = ids.filter { it !in seriesRatings && asked.add(it) }
        if (todo.isEmpty()) return
        scope.launch {
            todo.forEach { id ->
                val r = runCatching { api.userLibraryApi.getItem(itemId = id).content.officialRating }.getOrNull()
                com.github.damontecres.wholphin.jellytip.jellytipGlobalWrite { seriesRatings[id] = r.orEmpty() }
            }
        }
    }
}

@HiltViewModel
class KidsGateViewModel
    @Inject
    constructor(
        val api: ApiClient,
        val rows: JellytipRowsService,
    ) : ViewModel()

@Composable
fun KidsNotAvailable(
    modifier: Modifier = Modifier,
    text: String = "Not available in kids mode",
) {
    Box(modifier.fillMaxSize().background(JellytipPalette.Night), contentAlignment = Alignment.Center) {
        Text(text, color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontSize = 20.sp)
    }
}

/**
 * Details and playback of [itemId] in kids mode: shown only when its rating (or its series') is within the limit.
 * Unknown rating = 18 = refused. Nothing of the title is drawn while the check runs.
 */
@Composable
fun KidsRatingGate(
    itemId: UUID,
    modifier: Modifier = Modifier,
    viewModel: KidsGateViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val kids by JellytipKids.state.collectAsState()
    val ok by produceState<Boolean?>(null, itemId, kids.maxAge) {
        val dto = runCatching { viewModel.api.userLibraryApi.getItem(itemId = itemId).content }.getOrNull()
        val own = dto?.officialRating?.takeIf { it.isNotBlank() }
        val rating =
            own ?: dto?.seriesId?.let { sid ->
                runCatching { viewModel.api.userLibraryApi.getItem(itemId = sid).content.officialRating }.getOrNull()
            }
        value = dto != null && KidsRatings.allowed(rating, kids.maxAge)
        JellytipTrace.log("kids", "gate $itemId rating=$rating max=${kids.maxAge} -> $value")
    }
    when (ok) {
        true -> content()
        false -> KidsNotAvailable(modifier)
        null -> Box(modifier.fillMaxSize().background(JellytipPalette.Night))
    }
}

sealed interface KidsRows {
    data object Loading : KidsRows

    data object Failed : KidsRows

    data class Loaded(
        val answer: JellytipRowsAnswer,
    ) : KidsRows
}

/** Kids Home rows: always fresh from the server (never a cache from before kids mode); failure = error + Try again. */
@Composable
fun rememberKidsRows(
    page: String,
    retry: Int,
    viewModel: KidsGateViewModel = hiltViewModel(),
): KidsRows {
    val s by produceState<KidsRows>(KidsRows.Loading, page, retry) {
        value = KidsRows.Loading
        value = viewModel.rows.loadFresh(page)?.let { KidsRows.Loaded(it) } ?: KidsRows.Failed
    }
    return s
}

/** "Couldn't load" with Try again: in kids mode nothing unfiltered is ever shown instead. */
@Composable
fun KidsRetry(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.tryRequestFocus() }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontSize = 18.sp)
            Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Try again") }
        }
    }
}

