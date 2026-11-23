package com.github.damontecres.wholphin.jellytip.journeys

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.home.JellytipHomeTokens
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The active journey as one slide of the Home hero carousel (Journeys v1, Ricki's B2): the film's backdrop, "<JOURNEY> · STEP n OF m",
 * tonight's film, ▶ Play (one OK from Home plays it) and See the path. Only while it has a playable step tonight.
 */

/** Home: the active journey of whoever is signed in, refreshed each time Home shows; null = no slide. */
@Composable
fun rememberJellytipJourneySlide(service: JellytipJourneysV1Service): JvSummary? {
    LaunchedEffect(Unit) { service.refreshActive() }
    val active by service.active.collectAsState()
    val user = JellytipApi.shared?.currentUserKey() ?: return null
    return active?.takeIf { it.first == user }?.second?.takeIf { it.nowIndex != null && it.nowTitle != null }
}

/** The slide's step label ("THE ROAD TO HEAT · STEP 3 OF 6"). */
fun journeySlideKicker(s: JvSummary): String = JourneysV1.kicker(s.title, s.nowIndex ?: 0, s.total.coerceAtLeast((s.nowIndex ?: 0) + 1))

@Composable
fun JourneyHeroBackdrop(
    s: JvSummary,
    modifier: Modifier = Modifier,
) {
    var failed by remember(s.backdrop) { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(JellytipPalette.Night)) {
        if (s.backdrop != null && !failed) {
            AsyncImage(model = s.backdrop, contentDescription = null, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to JellytipPalette.Night, 1f to Color(0xFF3A2A20))))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to JellytipPalette.Night.copy(alpha = 0.85f), 0.45f to JellytipPalette.Night.copy(alpha = 0.55f), 0.8f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 0.95f to JellytipPalette.Night)))
    }
}

/** In the film hero header's place (bottom left of the header area). */
@Composable
fun JourneyHeroHeader(
    s: JvSummary,
    modifier: Modifier = Modifier,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    with(LocalDensity.current) {
        Box(modifier.fillMaxWidth().height((screenHeight * JellytipHomeTokens.HEADER_HEIGHT_FRACTION).dp), contentAlignment = Alignment.BottomStart) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .padding(start = JellytipHomeTokens.START_PAD_PX.toDp(), bottom = JellytipHomeTokens.BOTTOM_PAD_PX.toDp())
                        .width(JellytipHomeTokens.TEXT_BLOCK_W_PX.toDp())
                        .testTag("journey-slide"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(journeySlideKicker(s), color = JvColors.Peach, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.curated) CuratedPill()
                }
                Text(s.nowTitle.orEmpty(), color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Your journey · ${s.done} of ${s.total} seen", color = JvColors.Dim, fontFamily = JellytipFontFamily, fontSize = 14.sp)
            }
        }
    }
}

/** The hero button row for a journey slide: ▶ Play <film> (plays tonight's step), See the path. */
fun journeyHeroButtons(
    s: JvSummary,
    scope: CoroutineScope,
    navigationManager: NavigationManager,
): List<Pair<String, () -> Unit>> =
    listOf(
        "▶  Play ${s.nowTitle.orEmpty()}" to { playTonight(s, scope, navigationManager) },
        "See the path" to { navigationManager.navigateTo(Destination.JellytipJourney(s.id, v1 = true), "home hero journey path") },
    )

/** Home's Play: the journey's steps fresh (tonight's may have moved), then the same play as the journeys page. Failed: the journey page. */
private fun playTonight(
    s: JvSummary,
    scope: CoroutineScope,
    navigationManager: NavigationManager,
) {
    val service = JellytipJourneysV1Service.shared
    val api = JellytipApi.shared
    if (service == null || api == null) {
        navigationManager.navigateTo(Destination.JellytipJourney(s.id, v1 = true), "home hero journey (no service)")
        return
    }
    scope.launch {
        val j = (service.journey(s.id) as? JvLoad.Ok)?.value
        val now = j?.let { JourneysV1.nowIndex(it.steps) }
        if (j == null || now == null) {
            navigationManager.navigateTo(Destination.JellytipJourney(s.id, v1 = true), "home hero journey (no step)")
        } else {
            jvPlay(scope, service, api, navigationManager, j, now)
        }
    }
}
