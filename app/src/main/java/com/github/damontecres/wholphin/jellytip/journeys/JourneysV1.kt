package com.github.damontecres.wholphin.jellytip.journeys

import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse
import org.json.JSONArray
import org.json.JSONObject

/*
 * Journeys v1 (plan ~/ai/plans/jellytip-journeys-100x.md, Ricki's decisions 23-Nov; contract
 * ~/ai/state/fleet/jellytip-journeys-v1-spec.md): server-built journeys (`GET /v1/journeys`, `GET /v1/journeys/{id}`), per caller,
 * already filtered by the kids / limited policy. Curated ones carry a "why" per step. Pure: parsing, tonight's step, the path
 * strip, row order that does not re-rank on Back. Tested in JourneysV1Test.
 */

enum class JvSource {
    LIBRARY,
    IPTV,
    GETTABLE,
    MISSING,
    ;

    val playable get() = this == LIBRARY || this == IPTV

    companion object {
        fun of(s: String?): JvSource =
            when (s) {
                "library" -> LIBRARY
                "iptv" -> IPTV
                "gettable" -> GETTABLE
                else -> MISSING
            }
    }
}

data class JvSummary(
    val id: String,
    /** curated | list | director | franchise */
    val kind: String,
    val title: String,
    val curated: Boolean,
    /** active | paused | none | dropped | done */
    val status: String,
    val done: Int,
    val total: Int,
    val playable: Int,
    val poster: String?,
    val backdrop: String?,
    /** 0-based index of tonight's step; null = none playable / finished. */
    val nowIndex: Int?,
    val nowTitle: String?,
    val touchedAt: Long?,
)

data class JvStep(
    val n: Int,
    val tmdbId: Int?,
    val title: String,
    val year: Int?,
    val runtimeMin: Int?,
    val rating: Double?,
    val why: String?,
    val source: JvSource,
    val itemId: String?,
    val iptv: IptvVodItem?,
    val seen: Boolean,
    val rewatch: Boolean,
    val skipped: Boolean,
    val poster: String?,
    val backdrop: String?,
)

data class JvJourney(
    val summary: JvSummary,
    val premise: String?,
    val maxRating: String?,
    val steps: List<JvStep>,
)

data class JvHome(
    val active: String?,
    val journeys: Map<String, JvSummary>,
    val yours: List<String>,
    val start: List<String>,
    val finish: List<String>,
    val builtAt: Long?,
) {
    fun row(ids: List<String>) = ids.mapNotNull { journeys[it] }

    val activeSummary: JvSummary? get() = active?.let { journeys[it] }

    val isEmpty get() = yours.isEmpty() && start.isEmpty() && finish.isEmpty() && activeSummary == null
}

/** One dot of the path strip. */
enum class JvDot {
    /** ✓ seen */
    DONE,

    /** ● tonight */
    NOW,

    /** ○ still to come, playable */
    NEXT,

    /** Not playable for this person yet (gettable / missing): greyed, "Get it" for an admin. */
    GAP,

    /** Passed over ("Skip this one" / a later step was picked). */
    SKIPPED,
}

object JourneysV1 {
    // ---------- parsing (lenient: a bad entry is dropped, never the whole answer) ----------

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k, "").takeIf { it.isNotBlank() }

    private fun JSONObject.intOrNull(k: String): Int? = if (!has(k) || isNull(k)) null else (opt(k) as? Number)?.toInt()

    private fun JSONObject.longOrNull(k: String): Long? = if (!has(k) || isNull(k)) null else (opt(k) as? Number)?.toLong()

    private fun JSONObject.dblOrNull(k: String): Double? = if (!has(k) || isNull(k)) null else (opt(k) as? Number)?.toDouble()?.takeIf { !it.isNaN() }

    private fun ids(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }.distinct()

    /** TMDB art is absolute https; anything else goes through the IPTV image rule (relative to the companion, no odd schemes). */
    private fun art(
        base: String,
        raw: String?,
    ): String? = IptvVodParse.image(base, raw)

    fun summary(
        o: JSONObject?,
        base: String,
    ): JvSummary? {
        o ?: return null
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: return null
        return JvSummary(
            id = id,
            kind = o.str("kind") ?: "list",
            title = title,
            curated = o.optBoolean("curated", false),
            status = o.str("status") ?: "none",
            done = (o.intOrNull("done") ?: 0).coerceAtLeast(0),
            total = (o.intOrNull("total") ?: 0).coerceAtLeast(0),
            playable = (o.intOrNull("playable") ?: 0).coerceAtLeast(0),
            poster = art(base, o.str("poster")),
            backdrop = art(base, o.str("backdrop")),
            nowIndex = o.intOrNull("nowIndex")?.takeIf { it >= 0 },
            nowTitle = o.str("nowTitle"),
            touchedAt = o.longOrNull("touchedAt"),
        )
    }

    fun home(
        body: String,
        base: String,
    ): JvHome? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val list = o.optJSONArray("journeys") ?: return null
        val all = LinkedHashMap<String, JvSummary>()
        for (i in 0 until list.length()) summary(list.optJSONObject(i), base)?.let { all.putIfAbsent(it.id, it) }
        val rows = o.optJSONObject("rows")
        // An id is in at most one row (spec); a server slip must not draw one card twice
        val seen = HashSet<String>()

        fun row(k: String) = ids(rows?.optJSONArray(k)).filter { it in all && seen.add(it) }
        val yours = row("yours")
        val finish = row("finish")
        val start = row("start")
        return JvHome(o.str("active")?.takeIf { it in all }, all, yours, start, finish, o.longOrNull("builtAt"))
    }

    fun step(
        o: JSONObject?,
        base: String,
    ): JvStep? {
        o ?: return null
        val title = o.str("title") ?: return null
        val source = JvSource.of(o.str("source"))
        val iptv = if (source == JvSource.IPTV) IptvVodParse.item(o.optJSONObject("iptv"), base) else null
        val itemId = o.str("itemId")?.replace("-", "")?.lowercase()
        // A playable source without what it needs to play is not playable here
        val usable =
            when (source) {
                JvSource.LIBRARY -> if (itemId != null) source else JvSource.MISSING
                JvSource.IPTV -> if (iptv != null) source else JvSource.MISSING
                else -> source
            }
        return JvStep(
            n = o.intOrNull("n") ?: 0,
            tmdbId = o.intOrNull("tmdbId")?.takeIf { it > 0 },
            title = title,
            year = o.intOrNull("year")?.takeIf { it > 0 },
            runtimeMin = o.intOrNull("runtimeMin")?.takeIf { it > 0 },
            rating = o.dblOrNull("rating")?.takeIf { it > 0 },
            why = o.str("why"),
            source = usable,
            itemId = if (usable == JvSource.LIBRARY) itemId else null,
            iptv = iptv,
            seen = o.optBoolean("seen", false),
            rewatch = o.optBoolean("rewatch", false),
            skipped = o.optBoolean("skipped", false),
            poster = art(base, o.str("poster")),
            backdrop = art(base, o.str("backdrop")),
        )
    }

    fun journey(
        body: String,
        base: String,
    ): JvJourney? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val s = summary(o, base) ?: return null
        val a = o.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until a.length()).mapNotNull { step(a.optJSONObject(it), base) }
        return JvJourney(s, o.str("premise"), o.str("maxRating"), steps)
    }

    // ---------- tonight's step ----------

    /**
     * Tonight's step: the first step not seen, not skipped and playable. Earlier unseen steps that can't play yet are passed
     * over (the path offers the next playable one meanwhile, plan §4). When every unseen playable step was skipped, the first
     * skipped one is offered again (spec: never null just from skips). Null = nothing left to play.
     */
    fun nowIndex(steps: List<JvStep>): Int? =
        steps.indexOfFirst { !it.seen && !it.skipped && it.source.playable }.takeIf { it >= 0 }
            ?: steps.indexOfFirst { !it.seen && it.source.playable }.takeIf { it >= 0 }

    /** The step "Get it" fetches (admin): the first unseen gettable one, as the server's /get picks it. */
    fun getIndex(steps: List<JvStep>): Int? = steps.indexOfFirst { !it.seen && it.source == JvSource.GETTABLE }.takeIf { it >= 0 }

    fun dot(
        steps: List<JvStep>,
        i: Int,
        now: Int? = nowIndex(steps),
    ): JvDot {
        val s = steps[i]
        return when {
            s.seen -> JvDot.DONE
            i == now -> JvDot.NOW
            s.skipped -> JvDot.SKIPPED
            !s.source.playable -> JvDot.GAP
            else -> JvDot.NEXT
        }
    }

    /** "THE ROAD TO HEAT · STEP 3 OF 6": the position among the steps this person sees (authored numbers can have gaps). */
    fun kicker(
        title: String,
        index: Int,
        total: Int,
    ): String = "${title.uppercase()} · STEP ${index + 1} OF $total"

    fun runtime(min: Int?): String? =
        min?.takeIf { it > 0 }?.let {
            val h = it / 60
            val m = it % 60
            when {
                h == 0 -> "$m m"
                m == 0 -> "$h h"
                else -> "$h h $m m"
            }
        }

    /** "2 h 2 m · ★ 7.4 · Library" (the year sits with the title): the source is a small fact, library and IPTV look the same. */
    fun meta(s: JvStep): String =
        listOfNotNull(
            runtime(s.runtimeMin),
            s.rating?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) },
            when (s.source) {
                JvSource.LIBRARY -> "Library"
                JvSource.IPTV -> "IPTV"
                JvSource.GETTABLE -> "Not here yet"
                JvSource.MISSING -> "Not available"
            },
            if (s.rewatch) "Rewatch" else null,
        ).joinToString(" · ")

    /** "2/6": the card's progress label; fraction for its bar. */
    fun progressLabel(s: JvSummary) = "${s.done}/${s.total}"

    fun progress(s: JvSummary): Float = if (s.total <= 0) 0f else (s.done.toFloat() / s.total).coerceIn(0f, 1f)

    // ---------- the path strip ----------

    data class PathWindow(
        val from: Int,
        val to: Int,
        /** Steps hidden before [from] / after [to] (drawn as "+n"). */
        val before: Int,
        val after: Int,
    )

    /**
     * The strip shows at most [max] steps around tonight's: one done step before it for context (two when nothing follows),
     * the rest after. A 99-step list never runs off the screen.
     */
    fun window(
        size: Int,
        now: Int?,
        max: Int = PATH_MAX,
    ): PathWindow {
        if (size <= 0) return PathWindow(0, -1, 0, 0)
        if (size <= max) return PathWindow(0, size - 1, 0, 0)
        val anchor = (now ?: (size - 1)).coerceIn(0, size - 1)
        var from = (anchor - 1).coerceAtLeast(0)
        var to = from + max - 1
        if (to > size - 1) {
            to = size - 1
            from = (to - max + 1).coerceAtLeast(0)
        }
        return PathWindow(from, to, from, size - 1 - to)
    }

    const val PATH_MAX = 5

    // ---------- Back lands on the same card (no re-rank) ----------

    /**
     * A refetch while the page stays open (back from the player / details): ids already shown keep their places, new ones go
     * at the end, gone ones go. The server re-ranks by taste and recency; the page must not move the card he came back to.
     */
    fun stable(
        old: List<String>,
        fresh: List<String>,
    ): List<String> {
        val f = fresh.toSet()
        val kept = old.filter { it in f }
        val k = kept.toSet()
        return kept + fresh.filter { it !in k }
    }

    fun stable(
        old: JvHome?,
        fresh: JvHome,
    ): JvHome {
        old ?: return fresh
        // A card moving between rows (started: start -> yours) moves; within a row the order holds
        return fresh.copy(yours = stable(old.yours, fresh.yours), start = stable(old.start, fresh.start), finish = stable(old.finish, fresh.finish))
    }

    // ---------- events ----------

    /** Playing step [i] while tonight's is [now] (earlier, unseen): that one was passed over, a skip. */
    fun skipFor(
        steps: List<JvStep>,
        i: Int,
        now: Int? = nowIndex(steps),
    ): JvStep? = if (now != null && i > now) steps[now] else null

    /** Only an explicit 404 is "no such journey"; anything else is a load failure (Retry, backoff), never cached. */
    fun isNotFound(status: Int?) = status == 404

    /** The list route itself unknown (a server without Journeys v1): FastAPI's own 404, not the route's `{"error": ...}`. */
    fun isRouteMissing(
        status: Int?,
        body: String?,
    ): Boolean {
        if (status != 404) return false
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return true
        return !o.has("journeys") && !o.has("error")
    }

    /** Retry delays for a failed load (ms): 2 s, 5 s, 15 s, 30 s, then 60 s. */
    fun backoffMs(attempt: Int): Long = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)[attempt.coerceIn(0, 4)]
}
