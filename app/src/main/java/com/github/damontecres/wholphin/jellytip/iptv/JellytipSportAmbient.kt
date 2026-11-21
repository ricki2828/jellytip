package com.github.damontecres.wholphin.jellytip.iptv

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/*
 * Ambient scoreboard (round 3 item 9, mock-up 09). While a favourite team's game is on (or about to start), the idle
 * screen shows "WARRIORS V PANTHERS · ON NOW · OK TO WATCH" instead of film stills — in the app's own screensaver and
 * in the system screensaver (Jellytip's dream, once it is chosen in the TV's settings). Never a score (SportAmbient).
 * One fixtures call a minute while shown; nothing streams until OK. Burn-in: the card walks between nine spots every
 * three minutes; the existing dim setting still applies on top.
 */

/** The ambient card's OK, claimed by the in-app screensaver while a game is shown (MainActivity's screensaver branch). */
object SportAmbientKeys {
    @Volatile
    var onOk: (() -> Unit)? = null

    /** True = handled: the screensaver closes and the game opens. */
    fun ok(): Boolean {
        val f = onOk ?: return false
        f()
        return true
    }
}

/** Intent the system screensaver sends to open a game in the app (handled by IntentService). */
object SportAmbientIntent {
    const val ACTION_WATCH = "com.github.damontecres.wholphin.jellytip.WATCH_CHANNEL"
    const val EXTRA_CHANNEL = "jellytip_channel"
    const val EXTRA_MATCH = "jellytip_match"

    fun watch(
        context: Context,
        f: FxItem,
        nowS: Long,
    ): Intent? {
        val ch = SportFixtures.pickChannel(f, nowS).channel ?: return null
        // Explicit component: no intent-filter needed for the custom action
        return Intent(context, com.github.damontecres.wholphin.MainActivity::class.java)
            .setAction(ACTION_WATCH)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_CHANNEL, ch.id)
            .apply { SportFixtures.liveMatchId(f, ch)?.let { putExtra(EXTRA_MATCH, it) } }
    }
}

/** Polls fixtures once a minute while [active]; the current pick (null = no favourite on). */
suspend fun pollAmbient(
    service: JellytipIptvService,
    out: MutableStateFlow<SportAmbient.Pick?>,
) {
    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
        val list = runCatching { service.fixtures()?.second }.getOrNull()
        if (list != null) out.value = SportAmbient.pick(list.items, nowSec())
        delay(SportAmbient.POLL_MS)
    }
}

@HiltViewModel
class JellytipSportAmbientViewModel
    @Inject
    constructor(
        private val service: JellytipIptvService,
        private val playback: JellytipIptvPlayback,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        private val _pick = MutableStateFlow<SportAmbient.Pick?>(null)
        val pick: StateFlow<SportAmbient.Pick?> = _pick

        init {
            viewModelScope.launch { pollAmbient(service, _pick) }
        }

        fun watch() {
            val p = _pick.value ?: return
            val ch = SportFixtures.pickChannel(p.f, nowSec()).channel ?: return
            JellytipTrace.log("iptv", "ambient OK ${p.f.fid}")
            launchFixture(playback, navigationManager, p.f, ch, fromStart = false, from = "ambient")
        }
    }

/** The in-app screensaver's sport layer: draws the card when a favourite is on, else [fallback] (the stock stills). */
@Composable
fun JellytipSportAmbientOr(
    showClock: Boolean,
    modifier: Modifier = Modifier,
    viewModel: JellytipSportAmbientViewModel = hiltViewModel(),
    fallback: @Composable () -> Unit,
) {
    val pick by viewModel.pick.collectAsState()
    val p = pick
    DisposableEffect(p != null) {
        SportAmbientKeys.onOk = if (p != null) ({ viewModel.watch() }) else null
        onDispose { SportAmbientKeys.onOk = null }
    }
    if (p != null) SportAmbientScreen(p, showClock, modifier) else fallback()
}

private val HOME_WASH = Color(0xFF0E3A26)
private val AWAY_WASH = Color(0xFF3A1024)

@Composable
fun SportAmbientScreen(
    p: SportAmbient.Pick,
    showClock: Boolean,
    modifier: Modifier = Modifier,
) {
    val step by produceState(System.currentTimeMillis() / SportAmbient.DRIFT_MS) {
        while (true) {
            delay(SportAmbient.DRIFT_MS - System.currentTimeMillis() % SportAmbient.DRIFT_MS)
            value = System.currentTimeMillis() / SportAmbient.DRIFT_MS
        }
    }
    val (dx, dy) = SportAmbient.drift(step)
    val ax by animateFloatAsState(dx, tween(4_000), label = "ambient-x")
    val ay by animateFloatAsState(dy, tween(4_000), label = "ambient-y")
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color(0xFF06070A))
            .background(Brush.radialGradient(listOf(HOME_WASH.copy(alpha = 0.55f), Color.Transparent), radius = 1100f, center = androidx.compose.ui.geometry.Offset(420f, 420f)))
            .background(Brush.radialGradient(listOf(AWAY_WASH.copy(alpha = 0.55f), Color.Transparent), radius = 1100f, center = androidx.compose.ui.geometry.Offset(1500f, 680f))),
    ) {
        // Free room around the card (card ~ 60% wide, 45% tall)
        val roomX = maxWidth * 0.18f
        val roomY = maxHeight * 0.2f
        Column(
            Modifier.align(Alignment.Center).offset(x = roomX * ax, y = roomY * ay),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val f = p.f
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Crest(f.homeLogo, SportAmbient.short(f.home, f.homeShort), Color(0xFF1F8A5B))
                if (f.away.isNotBlank()) {
                    Text("V", color = Color(0xFFFFC72C), fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 64.sp)
                    Crest(f.awayLogo, SportAmbient.short(f.away, f.awayShort), Color(0xFFD6457A))
                }
            }
            Text(
                f.title.uppercase(),
                color = Color(0xFFEDEDED),
                fontFamily = JellytipFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 60.sp,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    p.badge,
                    color = Color.White,
                    fontFamily = JellytipFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    modifier = Modifier.background(if (p.live) JellytipPalette.Jelly else Color(0xFF3A3A3A), RoundedCornerShape(6.dp)).padding(horizontal = 14.dp, vertical = 4.dp),
                )
                Text(p.line, color = Color(0xFFB8BCC6), fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
            }
        }
        if (showClock) {
            val now by produceState(System.currentTimeMillis()) {
                while (true) {
                    delay(30_000)
                    value = System.currentTimeMillis()
                }
            }
            Text(
                Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a")),
                color = Color(0xFFBDBDBD),
                fontFamily = JellytipFontFamily,
                fontSize = 26.sp,
                // The clock drifts too (opposite way, smaller)
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 48.dp).offset(x = (-24).dp * ax, y = (-16).dp * ay),
            )
        }
    }
}

@Composable
private fun Crest(
    logo: String?,
    short: String,
    fill: Color,
) {
    var failed by remember(logo) { mutableStateOf(false) }
    Box(
        Modifier
            .size(200.dp)
            .background(if (logo != null && !failed) Color.White.copy(alpha = 0.06f) else fill, CircleShape)
            .border(BorderStroke(3.dp, Color.White.copy(alpha = 0.85f)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null && !failed) {
            AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, onError = { failed = true }, modifier = Modifier.size(140.dp))
        } else {
            Text(short, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Bold, fontSize = 52.sp)
        }
    }
}
