package com.github.damontecres.wholphin.jellytip.rows

import com.github.damontecres.wholphin.jellytip.mylist.JellytipMyListParse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ricki's real Home / Movies / Shows rows (palawan:~/jellytip-iptv/fake/fixtures/rows_*_ricki.json, 31-Oct; trimmed
 * to 6 IPTV + 3 library titles per row, ids replaced, provider PROVIDER): every row with `iptvItems` must parse with
 * all of them and reach a row branch that draws them all (the same ordering the Top 10, franchise and mixed rows use).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class ServerRowsIptvTest {
    private val pages = JSONObject(requireNotNull(javaClass.classLoader?.getResource("rows_ricki.json")).readText())

    /** IPTV tiles the row's branch in JellytipServerRowView draws (no library titles loaded yet). */
    private fun iptvTilesDrawn(row: JellytipServerRow): Int =
        when {
            row.isTop10 || row.isFranchise || (row.iptvItems.isNotEmpty() && row.itemIds.isNotEmpty()) ->
                JellytipMyListParse.ordered(row.order, emptyList(), row.iptvItems, { 0 }, { 1 }).sum()
            row.iptvItems.isNotEmpty() -> row.iptvItems.size // IptvVodRow
            else -> 0
        }

    @Test
    fun everyIptvTitleIsDrawnOnEveryPage() {
        for (page in listOf("home", "movies", "shows")) {
            val json = pages.getJSONObject(page)
            val sent = json.getJSONArray("rows").let { a -> (0 until a.length()).associate { a.getJSONObject(it).getString("id") to (a.getJSONObject(it).optJSONArray("iptvItems")?.length() ?: 0) } }
            val answer = requireNotNull(JellytipRowsParser.parse(json.toString())) { "$page: no rows" }
            assertEquals("$page: rows parsed", sent.keys.toList(), answer.rows.map { it.id })
            answer.rows.forEach { row ->
                assertEquals("$page ${row.id}: iptvItems parsed", sent[row.id], row.iptvItems.size)
                assertEquals("$page ${row.id}: IPTV tiles drawn", sent[row.id], iptvTilesDrawn(row))
            }
        }
    }

    @Test
    fun franchiseRowsWithOnlyIptvAreFranchiseRows() {
        val rows = requireNotNull(JellytipRowsParser.parse(pages.getJSONObject("home").toString())).rows
        val franchises = rows.filter { it.id.startsWith("franchise:") }
        assertEquals(3, franchises.size)
        franchises.forEach { assertEquals("${it.id} is a franchise row", true, it.isFranchise) }
    }
}
