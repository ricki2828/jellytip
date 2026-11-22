package com.github.damontecres.wholphin.jellytip.iptv

import org.json.JSONObject

/** One chapter of an IPTV film / episode (seconds); [t] = its title, null when the file has none. */
data class IptvChapter(
    val s: Double,
    val e: Double,
    val t: String?,
)

/** A part the chapters name: intro | recap | credits (server `chapters.segments`). */
data class IptvSkipSeg(
    val kind: String,
    val s: Double,
    val e: Double,
)

/** GET v1/iptv/chapters: [chapters] null = the server hasn't probed the file yet (ask again later), empty = it has none. */
data class IptvChapters(
    val chapters: List<IptvChapter>?,
    val skip: List<IptvSkipSeg>,
)

/**
 * Chapters and Intro / Recap / Credits skips on the IPTV player (parity §5, 22-Nov: the library player's skip-segment button and
 * chapter marks; IPTV's only source is the file's own named chapters, read by the server from the first bytes it relays). Pure (tested).
 */
object IptvChapterSkip {
    /**
     * After open: ask at these delays while the server says "not probed yet". The first is a free cache read (a file played before
     * is known at once, so an intro at 0 s gets its button); a first play needs the server's probe of the first 12 MB (~20 s).
     */
    val POLL_DELAYS_MS = listOf(2_000L, 25_000L, 60_000L, 150_000L)

    /** A skip to the very end stops this far short of it (a remux reopened at end of file is untested ground). */
    const val END_MARGIN_MS = 1_500L

    /** The button shows for this long from when the part was first reached (the library player's 10 s). */
    const val SHOW_MS = 10_000L

    /** No button in the last seconds of a part: the skip would land almost where the player already is. */
    const val TAIL_MS = 3_000L

    fun parse(body: String): IptvChapters? =
        runCatching {
            val o = JSONObject(body)
            val chs =
                o.optJSONArray("chapters")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        a.optJSONObject(i)?.let { c ->
                            val s = c.optDouble("s", Double.NaN)
                            val e = c.optDouble("e", Double.NaN)
                            if (s.isNaN() || e.isNaN() || e <= s) null else IptvChapter(s, e, c.optString("t").takeIf { c.has("t") && !c.isNull("t") && it.isNotBlank() })
                        }
                    }
                }
            val skip =
                o.optJSONArray("skip")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        a.optJSONObject(i)?.let { c ->
                            val k = c.optString("kind")
                            val s = c.optDouble("s", Double.NaN)
                            val e = c.optDouble("e", Double.NaN)
                            if (k !in KINDS || s.isNaN() || e.isNaN() || e <= s) null else IptvSkipSeg(k, s, e)
                        }
                    }
                } ?: emptyList()
            IptvChapters(chs, skip)
        }.getOrNull()

    private val KINDS = setOf("intro", "recap", "credits")

    /** The part [posMs] is in (and not in its last [TAIL_MS]), or null. */
    fun active(
        skip: List<IptvSkipSeg>,
        posMs: Long,
    ): IptvSkipSeg? = skip.firstOrNull { posMs >= (it.s * 1000).toLong() && posMs < (it.e * 1000).toLong() - TAIL_MS }

    /** Shown: in the part, within [SHOW_MS] of when it was first reached ([firstSeenMs], the position then), not dismissed. */
    fun visible(
        seg: IptvSkipSeg?,
        posMs: Long,
        firstSeenMs: Long?,
        dismissed: Boolean,
    ): Boolean = seg != null && !dismissed && (firstSeenMs == null || posMs - firstSeenMs in 0 until SHOW_MS)

    fun label(kind: String): String =
        when (kind) {
            "intro" -> "Skip intro"
            "recap" -> "Skip recap"
            else -> "Skip credits"
        }

    sealed interface Action {
        data class Seek(
            val ms: Long,
        ) : Action

        data object Next : Action
    }

    /** Credits with a next episode: play it (the library player's behaviour); otherwise to the part's end, short of the file's end. */
    fun action(
        seg: IptvSkipSeg,
        hasNext: Boolean,
        durationMs: Long = 0,
    ): Action {
        if (seg.kind == "credits" && hasNext) return Action.Next
        val to = (seg.e * 1000).toLong()
        return Action.Seek(if (durationMs > END_MARGIN_MS) minOf(to, durationMs - END_MARGIN_MS) else to)
    }

    /** Chapter starts as fractions of the duration for the scrub bar (not 0, not past the end). */
    fun marks(
        chapters: List<IptvChapter>?,
        durationMs: Long,
    ): List<Float> {
        if (chapters.isNullOrEmpty() || durationMs <= 0) return emptyList()
        return chapters
            .map { (it.s * 1000 / durationMs).toFloat() }
            .filter { it > 0.005f && it < 0.995f }
            .distinct()
    }
}
