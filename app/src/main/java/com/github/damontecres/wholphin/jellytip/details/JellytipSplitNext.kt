package com.github.damontecres.wholphin.jellytip.details

import com.github.damontecres.wholphin.jellytip.iptv.IptvEpisode
import com.github.damontecres.wholphin.jellytip.iptv.IptvSeason
import java.util.UUID

/*
 * Next up over a SPLIT series (part library, part IPTV): one ordered episode list, so "next" can be an IPTV-only episode.
 * Jellyfin's NextUp only knows library episodes, so after library S2E2 it says E6 although IPTV has E3-E5 (Celebrity
 * Traitors, 22-Nov). Pure and unit tested; shared by the series page (button + "Next" marker) and the player's Up next (t5n).
 */

/** A library episode as the merge sees it. */
data class SplitLibEp(
    val id: UUID,
    val season: Int?,
    val episode: Int?,
    val played: Boolean,
    val positionTicks: Long = 0L,
    /** A "missing" placeholder (no file): never a slot of its own, its IPTV copy (if any) takes the slot. */
    val virtual: Boolean = false,
)

/**
 * One slot of the merged list: season and episode number, the library copy (wins when it has a file) and / or the IPTV copy;
 * [played] when EITHER source says so (watched on IPTV or in the library is watched), [inProgress] when the copy that plays
 * has a resume point.
 */
data class SplitEp(
    val season: Int,
    val episode: Int,
    val lib: SplitLibEp?,
    val iptv: IptvEpisode?,
    val played: Boolean,
    val inProgress: Boolean,
)

/**
 * Every real episode (season >= 1, known numbers) of both sources in (season, episode) order. Specials (season 0) and
 * unnumbered library episodes are left out: next up never lands on them.
 */
fun splitEpisodes(
    library: List<SplitLibEp>,
    iptv: List<IptvSeason>,
): List<SplitEp> {
    val lib = library.filter { !it.virtual && (it.season ?: 0) >= 1 && it.episode != null }.associateBy { it.season!! to it.episode!! }
    // First copy wins on duplicate season blocks, as IptvSeasons.episodesFor / onlySeasons do (same tile as the series page)
    val tv = LinkedHashMap<Pair<Int, Int>, IptvEpisode>()
    iptv.filter { it.season >= 1 }.forEach { s -> s.episodes.forEach { tv.putIfAbsent(s.season to it.episode, it) } }
    return (lib.keys + tv.keys)
        .sortedWith(compareBy({ it.first }, { it.second }))
        .map { k ->
            val l = lib[k]
            val v = tv[k]
            val vPlayed = v?.progress?.played == true
            SplitEp(
                season = k.first,
                episode = k.second,
                lib = l,
                iptv = v,
                played = (l?.played == true) || vPlayed,
                inProgress = if (l != null) l.positionTicks > 0L else v?.progress?.resumable == true,
            )
        }
}

/**
 * The episode the series' primary button plays: the first one not yet watched after the LAST watched one (in order, either
 * source); nothing watched = the first episode; everything after the last watched one watched = null (the caller keeps
 * its own answer). A part-watched episode after the last watched one is that first not-watched one (Resume).
 */
fun splitNextUp(eps: List<SplitEp>): SplitEp? {
    if (eps.isEmpty()) return null
    val last = eps.indexOfLast { it.played }
    return eps.drop(last + 1).firstOrNull { !it.played }
}

/** The episode after [season]x[episode] in the merged order (library or IPTV), for the player's Up next; null = the last one. */
fun nextAfter(
    eps: List<SplitEp>,
    season: Int?,
    episode: Int?,
): SplitEp? {
    if (season == null || episode == null) return null
    return eps.firstOrNull { it.season > season || (it.season == season && it.episode > episode) }
}

/**
 * The server's merged next for one series (GET /v1/me/next?seriesId=…, t5s srcnnext): it has finish TIMES from both sources,
 * which the TV doesn't, so its answer wins over [splitNextUp]. [source] null = the show is finished (no next).
 */
data class SeriesNextClaim(
    val source: String?,
    val id: String?,
    val season: Int?,
    val episode: Int?,
    val inProgress: Boolean,
)

/** What the series page does with a claim: play that episode, or the show is finished. */
sealed interface ClaimedNext {
    data class Episode(
        val ep: SplitEp,
    ) : ClaimedNext

    data object Finished : ClaimedNext
}

object SeriesNextParse {
    /** Every series' claim in a `GET /v1/me/next` answer, by dashless lowercase series id; empty = none / malformed. */
    fun all(body: String): Map<String, SeriesNextClaim> =
        try {
            val all = org.json.JSONObject(body).optJSONObject("seriesNext") ?: return emptyMap()
            all.keys().asSequence().mapNotNull { k -> parse(body, k)?.let { k.replace("-", "").lowercase() to it } }.toMap()
        } catch (e: org.json.JSONException) {
            emptyMap()
        }

    /** `{seriesNext: {<series id>: {source, id, season, episode, inProgress}}}` -> this series' claim; null = no claim / malformed. */
    fun parse(
        body: String,
        seriesId: String,
    ): SeriesNextClaim? =
        try {
            val all = org.json.JSONObject(body).optJSONObject("seriesNext") ?: return null
            val want = seriesId.replace("-", "").lowercase()
            val key = all.keys().asSequence().firstOrNull { it.replace("-", "").lowercase() == want } ?: return null
            val o = all.optJSONObject(key) ?: return null
            // Only an explicit JSON null means "finished"; a claim without the key is malformed (no claim)
            if (!o.has("source")) return null
            val source = (o.opt("source") as? String)?.takeIf { it == "library" || it == "iptv" }
            if (o.opt("source") != null && o.opt("source") != org.json.JSONObject.NULL && source == null) return null
            SeriesNextClaim(
                source = source,
                id = (o.opt("id") as? String)?.takeIf { it.isNotBlank() },
                season = (o.opt("season") as? Number)?.toInt()?.takeIf { it > 0 },
                episode = (o.opt("episode") as? Number)?.toInt()?.takeIf { it > 0 },
                inProgress = o.opt("inProgress") == true,
            )
        } catch (e: org.json.JSONException) {
            null
        }
}

/**
 * The claim against the page's merged list: the slot with that library / IPTV id, else the same season and episode;
 * null = the page can't place it (use [splitNextUp]). A finished claim is [ClaimedNext.Finished].
 */
fun claimedNext(
    claim: SeriesNextClaim,
    eps: List<SplitEp>,
): ClaimedNext? {
    if (claim.source == null) return ClaimedNext.Finished
    val id = claim.id
    val byId =
        id?.let { want ->
            val lib = want.replace("-", "").lowercase()
            eps.firstOrNull { e -> e.iptv?.id == want || e.lib?.id?.toString()?.replace("-", "") == lib }
        }
    val hit = byId ?: eps.firstOrNull { it.season == claim.season && it.episode == claim.episode }
    return hit?.let { ClaimedNext.Episode(it) }
}

/**
 * Home Next Up / Tonight: a library next-up episode (Jellyfin's NextUp, which knows nothing of IPTV) stays only when the
 * server's merged next for its series agrees, or when nothing was claimed for it. A started episode (resume point) is a
 * Continue entry, never hidden here. Claim on IPTV, another library episode, or finished = hidden.
 */
object SplitNextFilter {
    fun keepLibraryNext(
        seriesId: java.util.UUID?,
        episodeId: java.util.UUID,
        positionTicks: Long,
        claims: Map<String, SeriesNextClaim>,
    ): Boolean {
        if (seriesId == null || positionTicks > 0L) return true
        val c = claims[seriesId.toString().replace("-", "").lowercase()] ?: return true
        return c.source == "library" && c.id?.replace("-", "")?.lowercase() == episodeId.toString().replace("-", "")
    }
}
