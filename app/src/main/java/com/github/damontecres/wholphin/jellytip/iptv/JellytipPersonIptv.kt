package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.github.damontecres.wholphin.jellytip.JellytipApi
import com.github.damontecres.wholphin.jellytip.debug.JellytipTrace
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID

/*
 * Person pages (~/ai/plans/jellytip-search-10x.md, "D"): under the library filmography, an "On IPTV" row from
 * `GET /v1/people/{id}/credits` (`iptv.iptvItems`, titles the library lacks, verified on the caller's IPTV). A
 * `pending: true` answer is fetched once more after 5 s. Empty, missing route or error = no row.
 */

private suspend fun personIptv(personId: UUID): Pair<List<IptvVodItem>, Boolean>? {
    val api = JellytipApi.shared ?: return null
    val (code, body) = api.userCall("v1/people/$personId/credits", timeoutMs = 8_000) ?: return null
    if (code != 200) return null
    return runCatching {
        val o = JSONObject(body)
        val base = runCatching { api.companionBase() }.getOrNull() ?: IptvVodParse.imageBase
        IptvVodParse.items(o.optJSONObject("iptv")?.optJSONArray("iptvItems"), base) to o.optBoolean("pending", false)
    }.getOrNull()
}

@Composable
fun JellytipPersonIptvRow(
    personId: UUID,
    modifier: Modifier = Modifier,
) {
    val items by produceState<List<IptvVodItem>>(emptyList(), personId) {
        val first = personIptv(personId) ?: return@produceState
        value = first.first
        if (first.second) {
            delay(PENDING_RETRY_MS)
            personIptv(personId)?.let { value = it.first }
        }
        JellytipTrace.log("iptv", "person $personId on IPTV: ${value.size}")
    }
    if (items.isEmpty()) return
    IptvVodRow(
        title = "On IPTV",
        items = items,
        poster = false,
        heightDp = ROW_HEIGHT_DP,
        onFocusTile = {},
        modifier = modifier,
    )
}

private const val PENDING_RETRY_MS = 5_000L
private const val ROW_HEIGHT_DP = 106
