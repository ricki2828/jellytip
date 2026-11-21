package com.github.damontecres.wholphin.jellytip.inbox

import org.json.JSONArray
import org.json.JSONObject

/*
 * Availability inbox (web de17f4d4 196173f8 1e770e3f cdee08a9, lib/inbox.ts): what the TV can fetch now, diffed against
 * a per-person snapshot kept on the device. News = a requested title reached the library or IPTV, or a followed show
 * (favourite / Next Up) got an episode it had not shown before. The first look only seeds the snapshot. Pure; tested in
 * InboxTest.
 */
object Inbox {
    const val EXPIRY_MS = 30L * 24 * 3600_000
    const val THROTTLE_MS = 15 * 60_000L
    const val MAX_EVENTS = 200
    const val MAX_TITLES = 500
    const val MAX_EPS_PER_SERIES = 150
    const val MAX_SERIES = 300
    const val MAX_CHECKED = 800
    const val FLOOD_LIMIT = 20
    const val MAX_REQUESTS = 100

    data class Event(
        val id: String,
        /** library | iptv | episode | episodes | summary */
        val kind: String,
        val title: String,
        val requested: Boolean = false,
        val season: Int? = null,
        val episode: Int? = null,
        val count: Int? = null,
        /** lib:<itemId> | iptv:<vodId> | home */
        val link: String,
        val at: Long,
        val read: Boolean = false,
    )

    data class Request(
        val key: String,
        val tmdbId: Int,
        /** movie | tv */
        val media: String,
        val title: String,
        val at: Long,
    )

    data class Store(
        val userId: String,
        val seeded: Boolean = false,
        val lastRefresh: Long = 0,
        /** title key -> rank (0 nowhere, 1 IPTV, 2 library) */
        val titles: Map<String, Int> = emptyMap(),
        /** series id -> episode ids and "#SxE" keys already seen */
        val eps: Map<String, List<String>> = emptyMap(),
        val events: List<Event> = emptyList(),
        val requests: List<Request> = emptyList(),
        val checked: Map<String, Long> = emptyMap(),
    )

    data class ObservedTitle(
        val r: Int,
        val title: String,
        val requested: Boolean = false,
        val libId: String? = null,
        val iptvId: String? = null,
        /** Not looked up on IPTV this time: keep a known IPTV rank. */
        val partial: Boolean = false,
    )

    data class ObservedEpisode(
        val id: String,
        val show: String,
        val season: Int?,
        val episode: Int?,
        val at: Long,
    )

    /** A source that failed is null: its part of the snapshot stays as it was. */
    data class Observed(
        val titles: Map<String, ObservedTitle>? = null,
        val eps: Map<String, List<ObservedEpisode>>? = null,
        val checked: List<String> = emptyList(),
    )

    fun epKey(season: Int?, episode: Int?): String? = if (season != null && episode != null) "#${season}x$episode" else null

    fun reqKey(
        media: String,
        tmdbId: Int,
    ) = "$media:$tmdbId"

    fun tidy(
        events: List<Event>,
        now: Long,
    ): List<Event> {
        val seen = LinkedHashMap<String, Event>()
        for (e in events) {
            if (now - e.at > EXPIRY_MS) continue
            val prev = seen[e.id]
            seen[e.id] = if (prev != null) prev.copy(read = prev.read || e.read, at = minOf(prev.at, e.at)) else e
        }
        return seen.values.sortedByDescending { it.at }.take(MAX_EVENTS)
    }

    fun unread(
        s: Store,
        now: Long,
    ) = s.events.count { !it.read && now - it.at <= EXPIRY_MS }

    fun markAllRead(s: Store) = s.copy(events = s.events.map { if (it.read) it else it.copy(read = true) })

    /** A request enters the snapshot at rank 0, so its first arrival is news (not a silent seed). */
    fun addRequest(
        s: Store,
        r: Request,
    ): Store {
        val requests = (listOf(r) + s.requests.filter { it.key != r.key }).take(MAX_REQUESTS)
        val titles = if (s.titles.containsKey(r.key)) s.titles else s.titles + (r.key to 0)
        return s.copy(requests = requests, titles = titles)
    }

    fun dueForRefresh(
        s: Store,
        now: Long,
    ) = now - s.lastRefresh >= THROTTLE_MS

    /** Fold one look into the store (see the file note). */
    fun apply(
        s: Store,
        obs: Observed,
        now: Long,
    ): Store {
        val first = !s.seeded
        val fresh = ArrayList<Event>()
        val titles = LinkedHashMap<String, Int>()
        if (obs.titles == null) titles.putAll(s.titles)
        obs.titles?.forEach { (key, o) ->
            val prev = s.titles[key]
            val r = if (o.partial && prev != null) maxOf(o.r, prev) else o.r
            titles[key] = r
            if (first || prev == null || r <= prev) return@forEach
            if (r == 2 && o.libId != null) {
                fresh += Event("lib:$key", "library", o.title, requested = o.requested, link = "lib:${o.libId}", at = now)
            } else if (r == 1 && o.iptvId != null) {
                fresh += Event("iptv:$key", "iptv", o.title, requested = o.requested, link = "iptv:${o.iptvId}", at = now)
            }
        }
        // Titles this look didn't report (a request recorded while it ran) keep their entry
        if (obs.titles != null) s.titles.forEach { (k, v) -> titles.putIfAbsent(k, v) }
        val eps = LinkedHashMap(s.eps)
        obs.eps?.forEach { (sid, list) ->
            val prev = s.eps[sid]
            val newest = list.sortedByDescending { it.at }
            val ids = newest.map { it.id }
            val keys = newest.mapNotNull { epKey(it.season, it.episode) }
            if (!first && prev != null) {
                // Known by id or by season+episode (a re-import gets new ids for the same episode)
                val known = prev.toHashSet()
                val seenNow = HashSet<String>()
                val added =
                    list.filter { e ->
                        val sxe = epKey(e.season, e.episode)
                        if (e.id in known || (sxe != null && sxe in known)) return@filter false
                        seenNow.add(sxe ?: e.id)
                    }
                if (added.size == 1) {
                    val e = added[0]
                    fresh += Event("ep:${e.id}", "episode", e.show, season = e.season, episode = e.episode, link = "lib:${e.id}", at = now)
                } else if (added.size > 1) {
                    val k = added.map { it.id }.sorted()
                    fresh += Event("eps:$sid:${k[0]}:${k.size}", "episodes", added[0].show, count = added.size, link = "lib:$sid", at = now)
                }
            }
            eps.remove(sid) // re-insert last: the oldest-touched series are the ones the cap drops
            val old = prev.orEmpty()
            fun keep(
                mine: List<String>,
                more: List<String>,
            ) = (mine + more).distinct().take(MAX_EPS_PER_SERIES)
            eps[sid] = keep(ids, old.filterNot { it.startsWith("#") }) + keep(keys, old.filter { it.startsWith("#") })
        }
        val cappedTitles = if (titles.size > MAX_TITLES) titles.entries.toList().takeLast(MAX_TITLES).associate { it.key to it.value } else titles
        val cappedEps = if (eps.size > MAX_SERIES) eps.entries.toList().takeLast(MAX_SERIES).associate { it.key to it.value } else eps
        val checked = LinkedHashMap(s.checked)
        obs.checked.forEach { checked[it] = now }
        val cappedChecked = if (checked.size > MAX_CHECKED) checked.entries.sortedBy { it.value }.takeLast(MAX_CHECKED).associate { it.key to it.value } else checked
        // A look that found a lot is one summary, not a wall of events
        val arrivals = fresh.sumOf { it.count ?: 1 }
        val events = if (fresh.size > FLOOD_LIMIT) listOf(Event("sum:$now", "summary", "New arrivals", count = arrivals, link = "home", at = now)) else fresh
        return s.copy(
            seeded = true,
            lastRefresh = now,
            titles = cappedTitles,
            eps = cappedEps,
            checked = cappedChecked,
            events = tidy(events + s.events, now),
        )
    }

    /** "<Title> is now in your library", "New episode: <Show> S2E5", … */
    fun text(e: Event): String =
        when (e.kind) {
            "episode" -> "New episode: ${e.title}" + (if (e.season != null && e.episode != null) " S${e.season}E${e.episode}" else "")
            "episodes" -> "${e.title}: ${e.count} new episodes"
            "summary" -> "${e.count} new arrivals for your shows and requests"
            "iptv" -> "${e.title} is now available via IPTV"
            else -> if (e.requested) "Your request for ${e.title} is now in your library" else "${e.title} is now in your library"
        }

    // ---- storage (one JSON per person) ----

    fun toJson(s: Store): String =
        JSONObject()
            .put("v", 2)
            .put("userId", s.userId)
            .put("seeded", s.seeded)
            .put("lastRefresh", s.lastRefresh)
            .put("titles", JSONObject().apply { s.titles.forEach { (k, v) -> put(k, v) } })
            .put("eps", JSONObject().apply { s.eps.forEach { (k, v) -> put(k, JSONArray(v)) } })
            .put("checked", JSONObject().apply { s.checked.forEach { (k, v) -> put(k, v) } })
            .put(
                "events",
                JSONArray().apply {
                    s.events.forEach { e ->
                        put(
                            JSONObject()
                                .put("id", e.id)
                                .put("kind", e.kind)
                                .put("title", e.title)
                                .put("requested", e.requested)
                                .put("season", e.season ?: JSONObject.NULL)
                                .put("episode", e.episode ?: JSONObject.NULL)
                                .put("count", e.count ?: JSONObject.NULL)
                                .put("link", e.link)
                                .put("at", e.at)
                                .put("read", e.read),
                        )
                    }
                },
            ).put(
                "requests",
                JSONArray().apply { s.requests.forEach { r -> put(JSONObject().put("key", r.key).put("tmdbId", r.tmdbId).put("media", r.media).put("title", r.title).put("at", r.at)) } },
            ).toString()

    /** Another person's data, junk and old shapes come back empty. */
    fun fromJson(
        raw: String?,
        userId: String,
    ): Store {
        raw ?: return Store(userId)
        return runCatching {
            val o = JSONObject(raw)
            if (o.optInt("v") != 2 || o.optString("userId") != userId) return Store(userId)
            fun intOrNull(j: JSONObject, k: String) = if (j.has(k) && !j.isNull(k)) j.optInt(k) else null
            val t = o.getJSONObject("titles")
            val e = o.getJSONObject("eps")
            val c = o.getJSONObject("checked")
            Store(
                userId = userId,
                seeded = o.optBoolean("seeded"),
                lastRefresh = o.optLong("lastRefresh"),
                titles = t.keys().asSequence().associateWith { t.getInt(it) },
                eps = e.keys().asSequence().associateWith { k -> e.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } },
                checked = c.keys().asSequence().associateWith { c.getLong(it) },
                events =
                    o.getJSONArray("events").let { a ->
                        (0 until a.length()).mapNotNull { i ->
                            val x = a.optJSONObject(i) ?: return@mapNotNull null
                            Event(
                                x.getString("id"), x.getString("kind"), x.getString("title"), x.optBoolean("requested"),
                                intOrNull(x, "season"), intOrNull(x, "episode"), intOrNull(x, "count"), x.getString("link"), x.getLong("at"), x.optBoolean("read"),
                            )
                        }
                    },
                requests =
                    o.getJSONArray("requests").let { a ->
                        (0 until a.length()).mapNotNull { i ->
                            val x = a.optJSONObject(i) ?: return@mapNotNull null
                            Request(x.getString("key"), x.getInt("tmdbId"), x.getString("media"), x.getString("title"), x.getLong("at"))
                        }
                    },
            )
        }.getOrElse { Store(userId) }
    }
}
