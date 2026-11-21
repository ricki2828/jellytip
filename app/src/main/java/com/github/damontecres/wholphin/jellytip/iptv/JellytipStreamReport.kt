package com.github.damontecres.wholphin.jellytip.iptv

import com.github.damontecres.wholphin.jellytip.JellytipApi
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder

/**
 * "This stream isn't working" (web f12e4580, IptvPlayer notWorking) for IPTV films and episodes: the person's own
 * report (`user_report`, always sent, even mid-play), then the next copy that hasn't failed lately. Episodes look for the
 * same season + episode on another copy of the show (at most [MAX_COPY_LOOKUPS] copies are opened).
 */
object IptvStreamReport {
    const val MAX_COPY_LOOKUPS = 3

    sealed interface Next {
        val message: String

        data class Vod(
            val id: String,
            val name: String,
        ) : Next {
            override val message get() = "Reported. Trying $name"
        }

        data class Episode(
            val seriesId: String,
            val episodeId: String,
            val name: String,
        ) : Next {
            override val message get() = "Reported. Trying $name"
        }

        data class None(
            override val message: String,
        ) : Next
    }

    /** The first copy in the server's order that isn't [current] and hasn't failed in the last 24 h; switched-on copies first. */
    fun nextCopy(
        current: String,
        copies: List<IptvCopy>,
        nowS: Long,
    ): IptvCopy? {
        val ok = copies.filter { it.id != current && !IptvStreamFail.failedLately(IptvStreamFail.failOf(it.id, it.lastFail), nowS) }
        return ok.firstOrNull { it.opted != false } ?: ok.firstOrNull()
    }

    /** Season + episode number of [episodeId] in [seasons]; null when it isn't there. */
    fun episodeAt(
        seasons: List<IptvSeason>,
        episodeId: String,
    ): Pair<Int, Int>? {
        for (s in seasons) s.episodes.firstOrNull { it.id == episodeId }?.let { return s.season to it.episode }
        return null
    }

    /** Same season + episode in [seasons] that hasn't failed lately. */
    fun sameEpisode(
        seasons: List<IptvSeason>,
        at: Pair<Int, Int>,
        nowS: Long,
    ): IptvEpisode? =
        seasons
            .filter { it.season == at.first }
            .flatMap { it.episodes }
            .firstOrNull { it.episode == at.second && !IptvStreamFail.failedLately(IptvStreamFail.failOf(it.id, it.lastFail), nowS) }

    private suspend fun detail(id: String): IptvVodDetail? {
        val api = JellytipApi.shared ?: return null
        val (code, body) = api.userCall("v1/iptv/vod/${URLEncoder.encode(id, "UTF-8")}") ?: return null
        if (code != 200) return null
        val base = api.companionBase().also { IptvVodParse.imageBase = it }
        return runCatching { IptvVodParse.detail(body, base) }.getOrNull()
    }

    /** Report [target] as not working and find what to try next. Live channels and recordings are not handled here. */
    suspend fun reportAndFindNext(
        target: IptvTarget,
        positionMs: Long,
    ): Next {
        IptvStreamFail.report(target, ok = false, reason = "user_report", posSec = positionMs / 1000)
        val now = System.currentTimeMillis() / 1000
        return try {
            val vodId = target.vodId
            val seriesId = target.seriesId
            val episodeId = target.episodeId
            when {
                vodId != null -> {
                    val d = detail(vodId) ?: return Next.None("Reported. Couldn't look up another copy.")
                    val n = nextCopy(vodId, d.copies, now) ?: return Next.None("Reported. No other copy of this film.")
                    Next.Vod(n.id, n.providerName ?: "another copy")
                }

                seriesId != null && episodeId != null -> {
                    val d = detail(seriesId) ?: return Next.None("Reported. Couldn't look up another copy.")
                    val at = episodeAt(d.seasons, episodeId) ?: return Next.None("Reported. No other copy of this episode.")
                    var looked = 0
                    for (c in d.copies) {
                        if (c.id == seriesId || IptvStreamFail.failedLately(IptvStreamFail.failOf(c.id, c.lastFail), now)) continue
                        if (looked++ >= MAX_COPY_LOOKUPS) break
                        val other = detail(c.id) ?: continue
                        sameEpisode(other.seasons, at, now)?.let { e -> return Next.Episode(c.id, e.id, c.providerName ?: "another copy") }
                    }
                    Next.None("Reported. No other copy of this episode.")
                }

                else -> Next.None("Reported.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Next.None("Reported. Couldn't look up another copy.")
        }
    }
}
