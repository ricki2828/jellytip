package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvResume
import com.github.damontecres.wholphin.jellytip.iptv.IptvResumeParse
import com.github.damontecres.wholphin.jellytip.iptv.IptvSource
import com.github.damontecres.wholphin.jellytip.cards.JellytipSourceMerge.LibRef
import com.github.damontecres.wholphin.jellytip.tonight.Tonight
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Rule E: Continue Watching / Next Up / Tonight merge by library id (films: libraryId, episodes: seriesLibraryId). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JellytipResumeLibraryIdTest {
    private val filmId = UUID.fromString("0a1b2c3d-4e5f-6789-abcd-ef0123456789")
    private val seriesId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val dashless = "0a1b2c3d4e5f6789abcdef0123456789"

    private fun resume(
        id: String,
        kind: String,
        libraryId: String? = null,
        seriesLibraryId: String? = null,
        sources: List<IptvSource> = emptyList(),
        seriesSources: List<IptvSource> = emptyList(),
        tmdb: Int? = null,
        seriesTmdb: Int? = null,
    ) = IptvResume(
        kind, id, if (kind == "episode") "s:1" else null, "T", null, null, null, 10.0, 100.0, 1L, null, null, emptyList(),
        tmdbId = tmdb, seriesTmdbId = seriesTmdb, libraryId = libraryId, sources = sources, seriesLibraryId = seriesLibraryId, seriesSources = seriesSources,
    )

    private val libFilm = LibRef(filmId.toString(), "movie", null)
    private val libEp = LibRef("99999999-0000-0000-0000-000000000000", "episode", null, null, seriesId.toString())

    private fun kept(
        lib: List<LibRef>,
        vararg r: IptvResume,
    ) = JellytipSourceMerge.filterResume(lib, r.toList()).map { it.id }

    @Test
    fun filmByLibraryIdAnyFormatting() {
        assertEquals(emptyList<String>(), kept(listOf(libFilm), resume("a", "movie", libraryId = dashless)))
        assertEquals(emptyList<String>(), kept(listOf(libFilm), resume("a", "movie", libraryId = filmId.toString().uppercase())))
        assertEquals(emptyList<String>(), kept(listOf(libFilm), resume("a", "movie", sources = listOf(IptvSource("library", dashless), IptvSource("iptv", "a")))))
    }

    @Test
    fun episodeBySeriesLibraryIdAnyFormatting() {
        assertEquals(emptyList<String>(), kept(listOf(libEp), resume("e", "episode", seriesLibraryId = "11111111222233334444555555555555")))
        assertEquals(emptyList<String>(), kept(listOf(libEp), resume("e", "episode", seriesLibraryId = seriesId.toString().uppercase())))
        assertEquals(emptyList<String>(), kept(listOf(libEp), resume("e", "episode", seriesSources = listOf(IptvSource("library", seriesId.toString())))))
    }

    @Test
    fun unknownOrAbsentKeepsBoth() {
        assertEquals(listOf("a", "e"), kept(listOf(libFilm, libEp), resume("a", "movie"), resume("e", "episode")))
        // a different library id, or a library title that is not in this row
        assertEquals(listOf("a"), kept(listOf(libFilm), resume("a", "movie", libraryId = "ffffffffffffffffffffffffffffffff")))
        assertEquals(listOf("e"), kept(listOf(libEp), resume("e", "episode", seriesLibraryId = dashless)))
        // film id does not match an episode's series id and vice versa
        assertEquals(listOf("e"), kept(listOf(libFilm), resume("e", "episode", seriesLibraryId = dashless)))
        assertEquals(listOf("a"), kept(listOf(libEp), resume("a", "movie", libraryId = seriesId.toString())))
    }

    @Test
    fun tmdbRuleIsStillTheFallback() {
        val f = LibRef(filmId.toString(), "movie", 949)
        assertEquals(emptyList<String>(), kept(listOf(f), resume("a", "movie", tmdb = 949)))
        val e = LibRef("x", "episode", null, 555, seriesId.toString())
        assertEquals(emptyList<String>(), kept(listOf(e), resume("e", "episode", seriesTmdb = 555)))
        assertEquals(listOf("r"), kept(listOf(libFilm), resume("r", "recording")))
    }

    @Test
    fun parsesTheNewFields() {
        val json =
            """{"items":[
              {"kind":"episode","id":"p:e:1","seriesId":"p:s:1","title":"E","seriesLibraryId":"$dashless","seriesSources":[{"type":"library","id":"$dashless"},{"type":"iptv","id":"p:s:1"}]},
              {"kind":"movie","id":"p:m:2","title":"F","libraryId":"$dashless","sources":[{"type":"library","id":"$dashless"}]},
              {"kind":"movie","id":"p:m:3","title":"G"}]}"""
        val r = IptvResumeParse.items(json, "")
        assertEquals(dashless, r[0].seriesLibraryId)
        assertEquals(2, r[0].seriesSources.size)
        assertEquals(dashless, r[1].libraryId)
        assertEquals(listOf("library"), r[1].sources.map { it.type })
        assertEquals(null, r[2].libraryId)
        assertEquals(emptyList<IptvSource>(), r[2].sources)
    }

    private fun libItem(
        id: UUID,
        type: BaseItemKind,
        series: UUID? = null,
    ) = BaseItem(BaseItemDto(id = id, type = type, seriesId = series))

    private fun cand(
        key: String,
        item: BaseItem? = null,
        iptv: IptvResume? = null,
    ) = Tonight.Cand(key, 0, "t", null, "r", "Resume", 30, 0, item = item, iptv = iptv)

    @Test
    fun tonightDropsIptvTwinsOnly() {
        val film = cand(dashless, item = libItem(filmId, BaseItemKind.MOVIE))
        val ep = cand("ep", item = libItem(UUID.randomUUID(), BaseItemKind.EPISODE, seriesId))
        val twinFilm = cand("iptv:movie:a", iptv = resume("a", "movie", libraryId = filmId.toString().uppercase()))
        val twinEp = cand("iptv:episode:e", iptv = resume("e", "episode", seriesLibraryId = "11111111222233334444555555555555"))
        val unknown = cand("iptv:movie:u", iptv = resume("u", "movie"))
        val other = cand("iptv:movie:o", iptv = resume("o", "movie", libraryId = "ffffffffffffffffffffffffffffffff"))
        // the IPTV candidate may come first: the library one still wins
        val out = Tonight.dedupe(listOf(twinFilm, twinEp, film, ep, unknown, other)).map { it.key }
        assertEquals(listOf(dashless, "ep", "iptv:movie:u", "iptv:movie:o"), out)
        // nothing known: unchanged
        assertEquals(listOf("iptv:movie:u"), Tonight.dedupe(listOf(unknown)).map { it.key })
    }
}

/** The IPTV-copy registry is per signed-in person. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JellytipTitleSourcesUserTest {
    @Test
    fun profilesDoNotShareCopies() {
        val id = "0a1b2c3d4e5f6789abcdef0123456789"
        var user = "A"
        JellytipTitleSources.userKey = { user }
        try {
            JellytipTitleSources.clear()
            JellytipTitleSources.put(id, listOf(IptvSource("iptv", "v1")))
            assertEquals(listOf("v1"), JellytipTitleSources.iptvFor(id).map { it.id })
            user = "B"
            assertEquals(emptyList<IptvSource>(), JellytipTitleSources.iptvFor(id))
            user = "A"
            assertEquals(listOf("v1"), JellytipTitleSources.iptvFor(id).map { it.id })
        } finally {
            JellytipTitleSources.clear()
            JellytipTitleSources.userKey = { "" }
        }
    }
}
