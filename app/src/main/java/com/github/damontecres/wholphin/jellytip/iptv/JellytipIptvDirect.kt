package com.github.damontecres.wholphin.jellytip.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/**
 * "Direct from provider" (gap row 8, web 77d514b0 10df4b7c): per provider, owner only. On, the owner's native players
 * (this TV sends client=native) get the provider's own link from /play and fetch it themselves, for providers that
 * block server streaming. Server: GET v1/iptv/providers -> {providers:[{id, name, owner, direct, directSuggested}]},
 * POST v1/iptv/providers/{id} {"direct": bool} (owner only, 404 otherwise).
 */
data class IptvDirectPref(
    val id: String,
    val name: String,
    val direct: Boolean,
    /** The server saw streams refused while the provider's API works: Direct would likely help. */
    val suggested: Boolean,
)

object IptvDirectLogic {
    /** The person's own providers only (Direct is never offered for a shared one). */
    fun parse(body: String): List<IptvDirectPref> {
        val a = JSONObject(body).optJSONArray("providers") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (!o.optBoolean("owner", false)) return@mapNotNull null
            val id = (o.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = (o.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: "Provider"
            IptvDirectPref(id, name, o.optBoolean("direct", false), o.optBoolean("directSuggested", false))
        }
    }

    fun label(
        p: IptvDirectPref,
        many: Boolean,
    ): String = if (many) "Direct from provider · ${p.name}" else "Direct from provider"

    fun value(p: IptvDirectPref): String =
        when {
            p.direct -> "On"
            p.suggested -> "Off · suggested: this provider blocks server streaming"
            else -> "Off"
        }

    const val NOTE = "On: this TV plays straight from the provider. Jellytip can't see those streams, so it can't stop two at once."
}

@HiltViewModel
class JellytipIptvDirectViewModel
    @Inject
    constructor(
        private val service: JellytipIptvService,
    ) : ViewModel() {
        /** null = not loaded or no answer; empty = no own provider (rows hidden). */
        private val _prefs = MutableStateFlow<List<IptvDirectPref>?>(null)
        val prefs: StateFlow<List<IptvDirectPref>?> = _prefs
        private val _message = MutableStateFlow<String?>(null)
        val message: StateFlow<String?> = _message
        private var busy = false

        fun load() {
            viewModelScope.launch { service.directPrefs()?.let { _prefs.value = it } }
        }

        fun toggle(p: IptvDirectPref) {
            if (busy) return
            busy = true
            viewModelScope.launch {
                val on = !p.direct
                val code = service.setDirect(p.id, on)
                JellytipTrace.log("iptv", "direct ${p.id} -> $on: $code")
                _message.value =
                    if (code == 200) {
                        if (on) "Direct on for ${p.name}, from the next stream." else "Direct off for ${p.name}, from the next stream."
                    } else {
                        "Couldn't change Direct (${if (code == 0) "no answer" else code})."
                    }
                service.directPrefs()?.let { _prefs.value = it }
                busy = false
            }
        }
    }
