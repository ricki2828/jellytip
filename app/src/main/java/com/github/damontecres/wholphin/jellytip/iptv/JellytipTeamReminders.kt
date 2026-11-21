package com.github.damontecres.wholphin.jellytip.iptv

import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/*
 * "Remind me for every <team> game" (web lib/teamReminders.ts + api/teamReminderStore.ts, 8efc5458 297a58b7; gap row 4):
 * GET / POST v1/iptv/reminders/teams, POST …/{id}/delete. The server fills the single-game reminders from them, so the
 * game reminder list is refreshed after a change. A 404 (older server) hides every team action.
 */
data class TeamReminder(
    val id: String,
    val teamId: String,
    val teamName: String,
    val sport: String?,
    val league: String?,
)

/** One side of a match as the action needs it (no team id = nothing offered: F1, golf, a guessed matchup). */
data class TeamSide(
    val teamId: String?,
    val name: String,
    val short: String?,
    val sport: String?,
    val league: String?,
)

object TeamReminderLogic {
    private fun JSONObject.str(k: String): String? = (opt(k) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    fun parse(body: String): List<TeamReminder> {
        val a = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.opt("id")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val teamId = o.str("teamId") ?: return@mapNotNull null
            TeamReminder(id, teamId, o.str("teamName") ?: teamId, o.str("sport"), o.str("league"))
        }
    }

    /** The server's short name when it is shorter, else the full name. */
    fun shortTeam(s: TeamSide): String = s.short?.takeIf { it.length <= s.name.length } ?: s.name

    /** Sides worth offering: with a team id, no duplicates, in order. */
    fun offerSides(sides: List<TeamSide>): List<TeamSide> = sides.filter { !it.teamId.isNullOrBlank() && it.name.isNotBlank() }.distinctBy { it.teamId }

    /** The sides of a fixture (matches only; events have no teams). */
    fun matchSides(
        m: IptvMatch?,
        sport: String?,
    ): List<TeamSide> {
        if (m == null || m.isEvent) return emptyList()
        return listOfNotNull(m.home, m.away).map { TeamSide(it.teamId, it.name, it.short, sport, m.league) }
    }

    fun remindEveryText(name: String) = "Remind me for every $name game"

    fun remindingText(name: String) = "Reminding for every $name game ✓"
}

@Singleton
class JellytipTeamReminders
    @Inject
    constructor(
        private val reminders: JellytipReminders,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val _items = MutableStateFlow<List<TeamReminder>?>(null)

        /** null = not known yet, or the server has no route ([available] false). */
        val items: StateFlow<List<TeamReminder>?> = _items
        private val _available = MutableStateFlow<Boolean?>(null)
        val available: StateFlow<Boolean?> = _available

        fun refresh() {
            scope.launch { load() }
        }

        private suspend fun load() {
            val api = JellytipApi.shared ?: return
            val (code, body) = api.userCall("v1/iptv/reminders/teams") ?: return
            when (code) {
                200 -> {
                    _available.value = true
                    _items.value = runCatching { TeamReminderLogic.parse(body) }.getOrNull() ?: emptyList()
                }
                404 -> _available.value = false
            }
        }

        fun reminderFor(teamId: String?): TeamReminder? = teamId?.let { id -> _items.value?.firstOrNull { it.teamId == id } }

        /** Turn it on / off for [side]; [onDone] gets an error text or null. */
        fun toggle(
            side: TeamSide,
            onDone: (String?) -> Unit = {},
        ) {
            val teamId = side.teamId ?: return
            scope.launch {
                val api = JellytipApi.shared ?: return@launch
                val existing = reminderFor(teamId)
                val res =
                    if (existing != null) {
                        api.userCall("v1/iptv/reminders/teams/${java.net.URLEncoder.encode(existing.id, "UTF-8")}/delete", "{}")
                    } else {
                        val body =
                            JSONObject().put("teamId", teamId).put("teamName", side.name)
                                .apply { side.sport?.let { put("sport", it) } }
                                .apply { side.league?.let { put("league", it) } }
                        api.userCall("v1/iptv/reminders/teams", body.toString())
                    }
                val code = res?.first ?: 0
                JellytipTrace.log("iptv", "team reminder ${if (existing != null) "off" else "on"} $teamId -> $code")
                val err =
                    when {
                        code == 404 && existing == null -> {
                            _available.value = false
                            "Team reminders aren't available on this server yet."
                        }
                        code in listOf(200, 201, 204) || (existing != null && code == 404) -> null
                        else -> runCatching { JSONObject(res?.second ?: "").optString("error").takeIf { it.isNotBlank() } }.getOrNull() ?: "Server said $code"
                    }
                load()
                onDone(err)
                if (err == null) {
                    // The server fills the game reminders a moment later
                    reminders.refresh()
                    delay(6_000)
                    reminders.refresh()
                }
            }
        }
    }

@dagger.hilt.android.lifecycle.HiltViewModel
class JellytipTeamRemindersViewModel
    @Inject
    constructor(
        val teams: JellytipTeamReminders,
        val navigationManager: com.github.damontecres.wholphin.services.NavigationManager,
    ) : androidx.lifecycle.ViewModel()
