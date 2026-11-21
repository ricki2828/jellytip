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

    const val PAGE_PREFIX = "jt-mylist:"

    /** Hub collection id of the My list page for a row's media type (movie / tv). */
    fun pageId(mediaType: String): String = PAGE_PREFIX + (if (mediaType == "tv") "series" else "movie")

    /** `movie` / `series` for a My list page id; null for any other collection id. */
    fun pageKind(id: String): String? = id.removePrefix(PAGE_PREFIX).takeIf { id.startsWith(PAGE_PREFIX) && (it == "movie" || it == "series") }

    /** The whole list as a mixed row (newest first, the server's order). */
    fun pageRow(
        id: String,
        kind: String,
        entries: List<JellytipMyListEntry>,
    ): JellytipServerRow {
        val order =
            entries.map {
                when (it) {
                    is JellytipMyListEntry.Library -> it.id.toString()
                    is JellytipMyListEntry.Iptv -> it.item.id
                }
            }
        return JellytipServerRow(
            id = id,
            source = "jellytip",
            family = "mylist",
            title = if (kind == "series") "My list · Shows" else "My list · Films",
            layout = "poster",
            mediaType = if (kind == "series") "tv" else "movie",
            itemIds = entries.filterIsInstance<JellytipMyListEntry.Library>().map { it.id }.distinct(),
            collection = null,
            iptvItems = entries.filterIsInstance<JellytipMyListEntry.Iptv>().map { it.item }.distinctBy { it.id },
            order = order,
        )
    }
}
