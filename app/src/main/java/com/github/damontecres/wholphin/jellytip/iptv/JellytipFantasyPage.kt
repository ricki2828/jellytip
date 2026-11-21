package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * Fantasy matchups (web components/FantasyMatchups.tsx, 06d37d03 2c5f4b03 c4c36e3e; gap row 5): each league's score,
 * who leads, your projection, recent scoring, and both line-ups; OK on a player playing now or soon opens their game
 * (the fixture's best channel, with its match id, so the companion and fantasy tab are there). Data: v1/fantasy/live,
 * refreshed every 60 s while the page shows. Nothing is delay-gated here: no picture is playing.
 */

/** The fixture a starter plays in: its match id, else its NFL team on a live / upcoming NFL fixture. Pure (tested). */
object FantasyWatch {
    fun fixtureFor(
        s: FantasyStarter,
        items: List<FxItem>,
    ): FxItem? {
        if (s.gameState != "in" && s.gameState != "pre") return null
        val live = items.filter { it.status == "in" || it.status == "pre" }
        s.matchId?.let { id -> live.firstOrNull { it.fid == id || it.matchId == id }?.let { return it } }
        val team = s.nflTeam?.uppercase() ?: return null
        return live.firstOrNull { f ->
            (f.leagueKey ?: "").lowercase().startsWith("nfl") && (f.homeShort?.uppercase() == team || f.awayShort?.uppercase() == team)
        }
    }

    fun stateText(s: FantasyStarter): String =
        when (s.gameState) {
            "in" -> "Live"
            "pre" -> "Pregame"
            "post" -> "Final"
            else -> "–"
        }
}

@HiltViewModel
class JellytipFantasyPageViewModel
    @Inject
    constructor(
        val service: JellytipIptvService,
        val playback: JellytipIptvPlayback,
        val navigationManager: NavigationManager,
    ) : ViewModel()

@Composable
private fun PlayerRow(
    s: FantasyStarter,
    watch: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { watch?.invoke() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.05f),
                focusedContainerColor = Color.White,
                contentColor = JellytipPalette.Vanilla,
                focusedContentColor = Color.Black,
            ),
        modifier = modifier.fillMaxWidth().height(48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(listOfNotNull(s.name, s.pos, s.nflTeam).joinToString(" · "), fontFamily = JellytipFontFamily, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(FantasyWatch.stateText(s) + if (watch != null) " · OK to watch" else "", fontFamily = JellytipFontFamily, fontSize = 12.sp, maxLines = 1)
            Text(pts(s.points), fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
fun JellytipFantasyPage(
    modifier: Modifier = Modifier,
    viewModel: JellytipFantasyPageViewModel = hiltViewModel(),
) {
    val live by JellytipFantasy.live.collectAsState()
    var fixtures by remember { mutableStateOf<List<FxItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        while (true) {
            JellytipFantasy.refresh(55_000)
            viewModel.service.fixtures(maxAgeMs = 60_000)?.second?.let { fixtures = it.items }
            delay(60_000)
        }
    }
    val hasLeagues = live?.leagues?.isNotEmpty() == true
    LaunchedEffect(hasLeagues) {
        if (hasLeagues) {
            delay(80)
            first.tryRequestFocus()
        }
    }
    Column(modifier.fillMaxSize().padding(start = 40.dp, top = 24.dp, end = 40.dp)) {
        Text("Fantasy", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp)
        val l0 = live
        if (l0 == null || l0.leagues.isEmpty()) {
            Text(
                if (l0 == null) "Loading… (link your fantasy account on the web: Settings → Fantasy)" else "No fantasy matchups this week.",
                color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
                fontFamily = JellytipFontFamily,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp)) {
            l0.leagues.forEachIndexed { li, l ->
                item(key = "h:${l.id}") {
                    Column(Modifier.padding(top = if (li == 0) 0.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(l.name, color = Color.White, fontFamily = JellytipFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            "${l.meTeam ?: "You"} ${pts(l.mePoints)} – ${pts(l.oppPoints)} ${l.oppTeam ?: "Opponent"}",
                            color = JellytipPalette.Vanilla,
                            fontFamily = JellytipFontFamily,
                            fontSize = 16.sp,
                        )
                        FantasyGameLogic.summaryLine(l)?.let { Text(it, color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 13.sp) }
                        val recent = l0.events.filter { it.league == l.id || it.league == l.name }.sortedByDescending { IptvParse.isoSeconds(it.at) ?: 0L }.take(4)
                        recent.forEach { e ->
                            Text(
                                "${if (e.side == "opp") "Opponent · " else ""}${if (e.delta >= 0) "+" else ""}${pts(e.delta)} ${e.name}" + (e.reason?.let { " · $it" } ?: ""),
                                color = if (e.side == "opp") Color(0xFFE5534B) else Color(0xFF22C55E),
                                fontFamily = JellytipFontFamily,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                listOf("me" to "Your team", "opp" to "Opponent").forEach { (side, label) ->
                    val players = l.starters.filter { it.side == side }.sortedWith(compareBy({ if (it.gameState == "in") 0 else if (it.gameState == "pre") 1 else 2 }, { -(it.points ?: 0.0) }))
                    item(key = "s:${l.id}:$side") {
                        Text(label, color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (players.isEmpty()) {
                        item(key = "e:${l.id}:$side") { Text("No starters available yet.", color = JellytipPalette.Vanilla.copy(alpha = 0.5f), fontFamily = JellytipFontFamily, fontSize = 12.sp) }
                    }
                    items(players, key = { p -> "p:${l.id}:$side:${p.id ?: p.name}" }) { p ->
                        val fx = FantasyWatch.fixtureFor(p, fixtures)
                        val watch: (() -> Unit)? =
                            fx?.let { f ->
                                {
                                    val ch = SportFixtures.pickChannel(f, nowSec()).channel
                                    if (ch != null) {
                                        JellytipTrace.log("iptv", "fantasy: watch ${p.name}'s game ${f.fid}")
                                        scope.launch { launchFixture(viewModel.playback, viewModel.navigationManager, f, ch, fromStart = false, from = "fantasy", forceMatch = true) }
                                    }
                                }
                            }
                        val isFirst = li == 0 && side == "me" && p === players.firstOrNull()
                        PlayerRow(p, watch, if (isFirst) Modifier.focusRequester(first) else Modifier)
                    }
                }
            }
        }
    }
}
