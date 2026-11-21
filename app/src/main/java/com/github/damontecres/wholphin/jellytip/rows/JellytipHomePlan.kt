package com.github.damontecres.wholphin.jellytip.rows

/*
 * Home row plan, TV part (web d3f68e43 a673b5de, lib/homePlan.ts): the server's rows minus thin ones (under 4 tiles), with
 * titles already shown in 3 earlier rows left out of a row when it keeps at least 4. Pinned rows are never touched. The web's
 * "80 % watched" filter needs each row's items first, so the TV leaves it out (a row vanishing after load would move focus).
 */
object JellytipHomePlan {
    const val MIN_ROW_ITEMS = 4
    const val DEDUPE_AT = 3

    /** Rows whose library ids can be thinned: plain library rows (no franchise, person, collection, order, IPTV, Top 10). */
    fun filterable(r: JellytipServerRow): Boolean =
        !r.isFranchise && r.person == null && r.collection == null && r.order.isEmpty() && r.missing.isEmpty() && r.iptvItems.isEmpty() && !r.isTop10

    fun count(r: JellytipServerRow) = r.itemIds.size + r.iptvItems.size + r.missing.size

    /** Never dropped for being short: a trilogy, a "Starring X" row, a collection, My list. */
    fun keepsShort(r: JellytipServerRow) = r.isFranchise || r.person != null || r.collection != null || r.family == "mylist"

    /** [pinned] null = the person's prefs aren't known yet: no thin-row drop (a pinned row could go), dedupe only. */
    fun plan(
        rows: List<JellytipServerRow>,
        pinned: Set<String>?,
    ): List<JellytipServerRow> {
        val seen = HashMap<String, Int>()
        val out = ArrayList<JellytipServerRow>()
        for (r in rows) {
            val pin = pinned != null && r.id in pinned
            if (pinned != null && !pin && count(r) < MIN_ROW_ITEMS && !keepsShort(r)) continue
            val shown =
                if (!pin && filterable(r)) {
                    val kept = r.itemIds.filter { (seen[key(it)] ?: 0) < DEDUPE_AT }
                    if (kept.size >= MIN_ROW_ITEMS) kept else r.itemIds
                } else {
                    r.itemIds
                }
            if (filterable(r) || pin) shown.map(::key).toSet().forEach { seen[it] = (seen[it] ?: 0) + 1 }
            out += if (shown.size == r.itemIds.size) r else r.copy(itemIds = shown)
        }
        return out
    }

    private fun key(id: java.util.UUID) = id.toString().replace("-", "")
}
