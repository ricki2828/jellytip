package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import com.github.damontecres.wholphin.jellytip.home.JellytipPalette
import com.github.damontecres.wholphin.jellytip.me.JellytipMe
import com.github.damontecres.wholphin.ui.components.EditTextBox
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Settings → Live sport (web components/SportSettings.tsx + SportPicker.tsx; gap rows 6, 22, 23): favourite teams and
 * leagues, sports followed / hidden, women's / replays, show scores, panel style, flip alerts, live alerts, default
 * spoiler delay, break mode (this TV) and the "Not interested" list. Every change is a PATCH of v1/me/prefs (one retry
 * after a 409); values set on the web show here and the other way round. D-pad: one list, OK changes the focused row.
 */

private const val PREFS_LOCAL = "jellytip_sport"

@Composable
private fun Row2(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(label, fontFamily = JellytipFontFamily, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontFamily = JellytipFontFamily, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Head(text: String) =
    Text(text, color = JellytipPalette.Vanilla.copy(alpha = 0.7f), fontFamily = JellytipFontFamily, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))

@Composable
fun JellytipSportSettingsPage(modifier: Modifier = Modifier) {
    val prefs by JellytipMe.prefs.collectAsState()
    val teamVm: JellytipTeamRemindersViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val teamList by teamVm.teams.items.collectAsState()
    val teamsAvailable by teamVm.teams.available.collectAsState()
    LaunchedEffect(Unit) { teamVm.teams.refresh() }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val local = remember { ctx.getSharedPreferences(PREFS_LOCAL, android.content.Context.MODE_PRIVATE) }
    var breakMode by remember { mutableStateOf(local.getBoolean("breakMode", true)) }
    var message by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // First focus once the prefs are there (the list isn't drawn before)
    val loaded = prefs != null
    LaunchedEffect(loaded) {
        if (loaded) {
            delay(50)
            first.tryRequestFocus()
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(3_000)
            message = null
        }
    }

    /** PATCH with one retry after a 409 (the answer carried the server's copy; [body] is rebuilt from it). */
    fun save(
        what: String,
        /** The row goes away on success (a remove): focus moves to "Add a team…" instead of being lost. */
        removes: Boolean = false,
        body: (SportSettings) -> JSONObject,
    ) {
        scope.launch {
            var ok = false
            for (attempt in 0..1) {
                val cur = JellytipMe.prefs.value?.sport ?: SportSettings()
                val (code, err) = JellytipMe.patch(body(cur))
                if (code == 200) {
                    ok = true
                    break
                }
                if (code != 409) {
                    message = "Couldn't save $what" + (err?.let { " ($it)" } ?: "")
                    break
                }
            }
            if (!ok && message == null) message = "Couldn't save $what"
            if (ok && removes) {
                // "Add a team…" may be scrolled away (not composed): bring it back first, then focus it
                listState.scrollToItem(1)
                delay(50)
                first.tryRequestFocus()
            }
            JellytipTrace.log("me", "sport settings $what ok=$ok")
        }
    }

    fun sportPatch(
        key: String,
        value: Any,
    ) = save(key) { JSONObject().put("sport", JSONObject().put(key, value)) }

    val p = prefs
    Column(modifier.fillMaxSize().padding(start = 40.dp, top = 24.dp, end = 40.dp)) {
        Text("Live sport", color = JellytipPalette.Vanilla, fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 26.sp)
        Text(
            message ?: "Teams, leagues and sports you follow come first in Live TV, Game Centre and on Home.",
            color = JellytipPalette.Vanilla.copy(alpha = 0.6f),
            fontFamily = JellytipFontFamily,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        if (p == null) {
            Text("Loading…", color = JellytipPalette.Vanilla.copy(alpha = 0.6f), fontFamily = JellytipFontFamily)
            return@Column
        }
        val s = p.sport
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Row2("Fantasy matchups", "OK to open") {
                    teamVm.navigationManager.navigateTo(com.github.damontecres.wholphin.ui.nav.Destination.JellytipFantasy, "live sport: fantasy")
                }
            }
            item { Head("Your teams and leagues") }
            item { Row2("Add a team…", "OK to search", Modifier.focusRequester(first)) { search = true } }
            items(s.teams, key = { "t:$it" }) { n ->
                Row2(n, "OK to remove") { save("teams", removes = true) { SportPrefs.teamsBody(SportPrefs.removeTeam(n, it)) } }
            }
            items(s.leagues, key = { "l:$it" }) { n ->
                Row2(n, "League · OK to remove") { save("leagues", removes = true) { SportPrefs.leaguesBody(SportPrefs.removeLeague(n, it)) } }
            }
            if (s.sportsSupported) {
                item { Head("Sports I follow (OK: follow → hide → clear)") }
                items(SportPrefs.SPORTS, key = { "s:${it.first}" }) { (value, label) ->
                    val st = SportPrefs.sportState(value, s)
                    Row2(
                        label,
                        when (st) {
                            SportPrefs.SportState.FOLLOW -> "★ Following"
                            SportPrefs.SportState.MUTE -> "⊘ Hidden"
                            SportPrefs.SportState.NEUTRAL -> "Not set"
                        },
                    ) {
                        // The state the viewer saw decides the next one (a 409 retry applies it to the fresh copy, never re-flips)
                        val to = SportPrefs.next(st)
                        save("sports") { cur -> SportPrefs.sportsBody(SportPrefs.applySport(value, to, cur)) }
                    }
                }
            }
            item {
                val on = SportPrefs.hasTag(s, SportPrefs.TAG_WOMENS)
                Row2("Hide women's competitions", if (on) "On" else "Off") { save("women's") { cur -> SportPrefs.mutedBody(SportPrefs.withTag(cur, SportPrefs.TAG_WOMENS, !on)) } }
            }
            item {
                val on = SportPrefs.hasTag(s, SportPrefs.TAG_REPLAYS)
                Row2("Hide replays and highlights", if (on) "On" else "Off") { save("replays") { cur -> SportPrefs.mutedBody(SportPrefs.withTag(cur, SportPrefs.TAG_REPLAYS, !on)) } }
            }
            if (teamsAvailable != false) {
                item { Head("Team reminders (every game)") }
                val tl = teamList.orEmpty()
                if (tl.isEmpty()) {
                    item { Text("None. In Upcoming sport, OK on a game offers 'Remind me for every <team> game'.", color = JellytipPalette.Vanilla.copy(alpha = 0.55f), fontFamily = JellytipFontFamily, fontSize = 13.sp) }
                }
                items(tl, key = { "tr:${it.id}" }) { r ->
                    Row2(r.teamName + (r.league?.let { " · $it" } ?: ""), "OK to stop") {
                        teamVm.teams.toggle(TeamSide(r.teamId, r.teamName, null, r.sport, r.league), turnOn = false) { err ->
                            message = err ?: "Stopped reminders for ${r.teamName}"
                            if (err == null) scope.launch {
                                listState.scrollToItem(1)
                                delay(50)
                                first.tryRequestFocus()
                            }
                        }
                    }
                }
            }
            item { Head("While you watch") }
            item { Row2("Show scores (spoilers) in match info", if (p.sportSpoilers) "On" else "Off") { sportPatch("spoilers", !p.sportSpoilers) } }
            item { Row2("Spoiler delay (channels you haven't set)", "${p.sportDelaySec} s") { sportPatch("delaySec", SportPrefs.nextDelay(p.sportDelaySec)) } }
            item {
                val label = mapOf("key" to "Key moments", "all" to "Everything", "off" to "Off")
                val next = mapOf("key" to "all", "all" to "off", "off" to "key")
                Row2("Live alerts", label[p.sportAlerts] ?: "Key moments") { sportPatch("alerts", next[p.sportAlerts] ?: "all") }
            }
            item { Row2("Polymarket flip alerts (needs scores on)", if (p.sportFlipAlerts) "On" else "Off") { sportPatch("flipAlerts", !p.sportFlipAlerts) } }
            item {
                Row2("Live panel", if (p.sportPanelStyle == "floating") "Floating" else "Docked (picture steps aside)") {
                    JellytipMe.setPanelStyle(if (p.sportPanelStyle == "floating") "docked" else "floating")
                }
            }
            item {
                Row2("Panel opens by itself at breaks (this TV)", if (breakMode) "On" else "Off") {
                    breakMode = !breakMode
                    local.edit().putBoolean("breakMode", breakMode).apply()
                    JellytipTrace.log("me", "break mode $breakMode")
                }
            }
            val muted = s.mutedSports.map { "sports" to it } + s.mutedLeagues.map { "leagues" to it } + s.mutedTags.filterNot { it.equals(SportPrefs.TAG_WOMENS, true) || it.equals(SportPrefs.TAG_REPLAYS, true) }.map { "tags" to it }
            item { Head("Not interested") }
            if (muted.isEmpty()) {
                item { Text("Nothing hidden. Hold OK on a live sport tile to hide a league or sport.", color = JellytipPalette.Vanilla.copy(alpha = 0.55f), fontFamily = JellytipFontFamily, fontSize = 13.sp) }
            }
            items(muted, key = { "m:${it.first}:${it.second}" }) { (kind, v) ->
                Row2(v, "Hidden · OK to show again") { save("not interested", removes = true) { cur -> SportPrefs.mutedBody(SportPrefs.unmute(cur, kind, v)) } }
            }
        }
    }
    if (search) {
        TeamSearchDialog(
            settings = prefs?.sport ?: SportSettings(),
            onToggle = { hit ->
                save("teams") { cur -> SportPrefs.teamsBody(if (SportPrefs.isFollowing(hit, cur)) SportPrefs.unfollow(hit, cur) else SportPrefs.follow(hit, cur)) }
            },
            onDismiss = { search = false },
        )
    }
}

/** Type a team: GET v1/sport/teams?q= (crest-less list: name · league), OK follows / unfollows; Back closes. */
@Composable
private fun TeamSearchDialog(
    settings: SportSettings,
    onToggle: (TeamHit) -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TeamHit>?>(null) }
    val field = remember { FocusRequester() }
    val firstHit = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        field.tryRequestFocus()
    }
    LaunchedEffect(q) {
        val term = q.trim()
        if (term.length < 2) {
            hits = null
            return@LaunchedEffect
        }
        delay(350)
        val api = JellytipApi.shared ?: return@LaunchedEffect
        val res = api.userCall("v1/sport/teams?q=${java.net.URLEncoder.encode(term, "UTF-8")}")
        hits = if (res?.first == 200) runCatching { SportPrefs.parseTeams(res.second) }.getOrNull() ?: emptyList() else emptyList()
    }
    com.github.damontecres.wholphin.ui.components.BasicDialog(onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(24.dp).width(520.dp)) {
            Text("Find a team", fontFamily = JellytipFontFamily, fontSize = 16.sp)
            EditTextBox(
                value = q,
                onValueChange = { q = it },
                modifier = Modifier.fillMaxWidth().focusRequester(field),
                // The keyboard's Search key jumps to the results
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { firstHit.tryRequestFocus() }),
            )
            val h = hits
            when {
                h == null -> Text("Type at least two letters.", fontFamily = JellytipFontFamily, fontSize = 13.sp)
                h.isEmpty() -> Text("No teams found.", fontFamily = JellytipFontFamily, fontSize = 13.sp)
                else ->
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 360.dp)) {
                        items(h, key = { "${it.leagueKey}:${it.id}" }) { t ->
                            Row2(
                                t.line,
                                if (SportPrefs.isFollowing(t, settings)) "★ Following" else "OK to follow",
                                if (t === h.first()) Modifier.focusRequester(firstHit) else Modifier,
                            ) { onToggle(t) }
                        }
                    }
            }
        }
    }
}
