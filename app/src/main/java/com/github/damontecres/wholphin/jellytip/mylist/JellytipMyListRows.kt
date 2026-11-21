package com.github.damontecres.wholphin.jellytip.mylist

import com.github.damontecres.wholphin.jellytip.rows.JellytipRowsAnswer
import com.github.damontecres.wholphin.jellytip.rows.JellytipServerRow

/**
 * My list on the Movies / Shows pages (web 1ec4d7bf): the server's Home "mylist" row for that media type (library +
 * IPTV favourites, newest first), drawn first on the page and titled "My list". Pure, so it is unit tested.
 */
object JellytipMyListRows {
    /** `movie` / `tv` for the Movies / Shows pages; null for any other page. */
    fun kindOf(page: String): String? =
        when (page) {
            "movies" -> "movie"
            "shows" -> "tv"
            else -> null
        }

    fun pick(
        home: JellytipRowsAnswer?,
        kind: String,
    ): JellytipServerRow? =
        home?.rows?.firstOrNull {
            it.family == "mylist" && it.mediaType == kind && (it.itemIds.isNotEmpty() || it.iptvItems.isNotEmpty())
        }

    /**
     * [answer] with My list in front for the Movies / Shows pages. An old server (null answer) keeps the old carousels;
     * a page that already has a My list row, or an empty list, is left as it is.
     */
    fun withMyList(
        page: String,
        answer: JellytipRowsAnswer?,
        home: JellytipRowsAnswer?,
    ): JellytipRowsAnswer? {
        val kind = kindOf(page) ?: return answer
        answer ?: return null
        if (answer.rows.any { it.family == "mylist" }) return answer
        val mine = pick(home, kind) ?: return answer
        return answer.copy(rows = listOf(mine.copy(id = "mylist-$page", title = "My list")) + answer.rows)
    }
}
