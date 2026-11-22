package com.github.damontecres.wholphin.jellytip.cards

import com.github.damontecres.wholphin.jellytip.iptv.IptvProgress
import com.github.damontecres.wholphin.jellytip.iptv.IptvResumeParse
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodItem
import com.github.damontecres.wholphin.jellytip.iptv.IptvVodParse
import com.github.damontecres.wholphin.jellytip.iptv.tileArt
import com.github.damontecres.wholphin.jellytip.iptv.tileCornerText
import com.github.damontecres.wholphin.jellytip.iptv.tilePercent
import com.github.damontecres.wholphin.jellytip.iptv.tilePlayed
import com.github.damontecres.wholphin.jellytip.iptv.tileTech
import com.github.damontecres.wholphin.jellytip.iptv.toTileModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** IptvVodItem -> JellytipTileModel (the source-neutral tile) and the optional new server fields (JSON via Robolectric's org.json). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class JellytipTileModelTest {
    private fun item(
        kind: String = "movie",
        quality: List<String> = emptyList(),
        tech: List<String> = emptyList(),
        audio: String? = null,
        progress: IptvProgress? = null,
        played: Boolean = false,
        favourite: Boolean = false,
        poster: String? = "https://h/p",
        backdrop: String? = "https://h/b",
        logo: String? = null,
        textlessBackdrop: String? = null,
        backdropTextless: Boolean = false,
        unplayedCount: Int? = null,
        episodeNumber: Int? = null,
    ) = IptvVodItem(
        id = "p:m:1",
        kind = kind,
        tmdbId = 1,
        title = "Heat",
        year = 1995,
        quality = quality,
        poster = poster,
        backdrop = backdrop,
        overview = "",
        rating = null,
        providerName = null,
        progress = progress,
        tech = tech,
        favourite = favourite,
        played = played,
        audio = audio,
        logo = logo,
        textlessBackdrop = textlessBackdrop,
        backdropTextless = backdropTextless,
        unplayedCount = unplayedCount,
        episodeNumber = episodeNumber,
    )

    // ---- played / progress ----

    @Test
    fun playedComesFromTheServerFlagOrTheProgressBlock() {
        assertFalse(item().tilePlayed)
        assertTrue(item(played = true).tilePlayed)
        assertTrue(item(progress = IptvProgress(7700.0, 7800.0, true)).tilePlayed)
    }

    @Test
    fun barShowsForAnyStartedUnplayedItemWithALength() {
        // 40 %
        assertEquals(40.0, item(progress = IptvProgress(3120.0, 7800.0, false)).tilePercent, 0.001)
        // under 30 s still draws (the old IPTV-only "> 30 s" rule is gone for drawing)
        assertEquals(10.0, item(progress = IptvProgress(10.0, 100.0, false)).tilePercent, 0.001)
        // past 90 % still draws until it is 100 %
        assertEquals(95.0, item(progress = IptvProgress(95.0, 100.0, false)).tilePercent, 0.001)
    }

    @Test
    fun noBarWhenPlayedUnstartedUnknownLengthOrFinished() {
        assertEquals(0.0, item(progress = IptvProgress(3120.0, 7800.0, true)).tilePercent, 0.0)
        assertEquals(0.0, item(played = true, progress = IptvProgress(3120.0, 7800.0, false)).tilePercent, 0.0)
        assertEquals(0.0, item(progress = IptvProgress(0.0, 7800.0, false)).tilePercent, 0.0)
        assertEquals(0.0, item(progress = IptvProgress(500.0, 0.0, false)).tilePercent, 0.0)
        assertEquals(0.0, item(progress = IptvProgress(7800.0, 7800.0, false)).tilePercent, 0.0)
        assertEquals(0.0, item().tilePercent, 0.0)
    }

    @Test
    fun favouriteIsPassedIn() {
        assertTrue(item(favourite = true).toTileModel(poster = false).favorite)
        assertFalse(item(favourite = true).toTileModel(poster = false, favorite = false).favorite)
        assertTrue(item().toTileModel(poster = false, favorite = true).favorite)
    }

    // ---- quality tokens -> the library's tech ids ----

    @Test
    fun freeTokensMapOntoTheLibraryVocabulary() {
        assertEquals(listOf("4K"), item(quality = listOf("4k")).tileTech())
        assertEquals(listOf("4K HDR"), item(quality = listOf("4k", "hdr")).tileTech())
        assertEquals(listOf("4K DV", "ATMOS"), item(tech = listOf("4K", "DV", "HDR"), audio = "atmos").tileTech())
        assertEquals(listOf("HDR10+"), item(tech = listOf("HDR10+")).tileTech())
        assertEquals(listOf("DTS:X"), item(tech = listOf("DTSX")).tileTech())
    }

    @Test
    fun unknownTokensAreDropped() {
        assertEquals(emptyList<String>(), item(quality = listOf("1080p", "web-dl"), audio = "5.1").tileTech())
        assertEquals(listOf("4K"), item(quality = listOf("1080p", "4k", "x265"), audio = "stereo").tileTech())
    }

    @Test
    fun posterTilesCarryNoPills() {
        assertEquals(emptyList<String>(), item(quality = listOf("4k")).toTileModel(poster = true).tech)
        assertEquals(listOf("4K"), item(quality = listOf("4k")).toTileModel(poster = false).tech)
    }

    // ---- corner pill ----

    @Test
    fun episodePillIsTheLibrarysE5() {
        assertEquals("E5", item(kind = "series", episodeNumber = 5).tileCornerText(poster = false))
    }

    @Test
    fun seriesPillIsTheUnplayedCount() {
        assertEquals("4", item(kind = "series", unplayedCount = 4).tileCornerText(poster = false))
        assertEquals("1.2K", item(kind = "series", unplayedCount = 1200).tileCornerText(poster = false))
        assertNull(item(kind = "series", unplayedCount = 0).tileCornerText(poster = false))
        assertNull(item(kind = "series", unplayedCount = null).tileCornerText(poster = false))
        // a film never shows an unplayed count
        assertNull(item(kind = "movie", unplayedCount = 3).tileCornerText(poster = false))
    }

    @Test
    fun postersHaveNoPill() {
        assertNull(item(kind = "series", unplayedCount = 4).tileCornerText(poster = true))
        assertNull(item(kind = "series", episodeNumber = 5).tileCornerText(poster = true))
    }

    // ---- art choice ----

    @Test
    fun logoAndTextlessBackdropGivesTheTextlessImageWithTheLogo() {
        val art = item(logo = "https://h/logo", textlessBackdrop = "https://h/textless").tileArt(poster = false)
        assertEquals(JellytipCardArt("https://h/textless", false, "https://h/logo"), art)
    }

    @Test
    fun logoAndTextlessFlagGivesTheOwnBackdropWithTheLogo() {
        val art = item(logo = "https://h/logo", backdropTextless = true).tileArt(poster = false)
        assertEquals(JellytipCardArt("https://h/b", false, "https://h/logo"), art)
    }

    @Test
    fun textlessBackdropWinsOverTheFlag() {
        val art = item(logo = "https://h/logo", textlessBackdrop = "https://h/textless", backdropTextless = true).tileArt(poster = false)
        assertEquals("https://h/textless", art.baseUrl)
    }

    @Test
    fun logoWithoutKnownTextlessArtIsNotDrawnTheTitleIs() {
        // the backdrop may have text baked in: no logo over it; the title is set in the art (logoUrl null, titledAlready false)
        assertEquals(JellytipCardArt("https://h/b", false, null), item(logo = "https://h/logo").tileArt(poster = false))
    }

    @Test
    fun textlessWithoutLogoDrawsTheTitleOverTheCleanImage() {
        assertEquals(JellytipCardArt("https://h/textless", false, null), item(textlessBackdrop = "https://h/textless").tileArt(poster = false))
        assertEquals(JellytipCardArt("https://h/b", false, null), item(backdropTextless = true).tileArt(poster = false))
    }

    @Test
    fun noExtraFieldsIsToday() {
        assertEquals(JellytipCardArt("https://h/b", false, null), item().tileArt(poster = false))
        assertEquals(JellytipCardArt("https://h/p", false, null), item(backdrop = null).tileArt(poster = false))
    }

    @Test
    fun posterArtIsThePosterOrTheBackdrop() {
        assertEquals("https://h/p", item().tileArt(poster = true).baseUrl)
        assertEquals("https://h/b", item(poster = null).tileArt(poster = true).baseUrl)
        assertNull(item(poster = null, backdrop = null).tileArt(poster = true).baseUrl)
        assertTrue(item().toTileModel(poster = true).poster)
    }

    // ---- JSON: the new optional fields ----

    private fun parse(json: String) = IptvVodParse.item(JSONObject(json), "https://base")

    @Test
    fun newFieldsAreParsed() {
        val v =
            parse(
                """{"id":"p:s:9","kind":"series","title":"Slow Horses","logo":"https://t/logo.png","textlessBackdrop":"/img/tl.jpg",
                   "backdropTextless":true,"unplayedCount":4,"nextUp":{"season":2,"episode":5,"id":"p:e:77"},"libraryId":"abc123",
                   "sources":[{"type":"library","id":"abc123","quality":"4k","tech":["DV"],"copies":1},
                              {"type":"iptv","id":"p:s:9","quality":["1080p","4k"],"copies":3}]}""",
            )!!
        assertEquals("https://t/logo.png", v.logo)
        assertEquals("https://base/img/tl.jpg", v.textlessBackdrop)
        assertTrue(v.backdropTextless)
        assertEquals(4, v.unplayedCount)
        assertEquals(2, v.nextUp?.season)
        assertEquals(5, v.nextUp?.episode)
        assertEquals("p:e:77", v.nextUp?.id)
        assertEquals("abc123", v.libraryId)
        assertEquals(listOf("library", "iptv"), v.sources.map { it.type })
        assertEquals(listOf("4k"), v.sources[0].quality)
        assertEquals(listOf("DV"), v.sources[0].tech)
        assertEquals(1, v.sources[0].copies)
        assertEquals(listOf("1080p", "4k"), v.sources[1].quality)
        assertEquals(3, v.sources[1].copies)
    }

    @Test
    fun absentFieldsAreToday() {
        val v = parse("""{"id":"p:m:1","title":"Heat"}""")!!
        assertNull(v.logo)
        assertNull(v.textlessBackdrop)
        assertFalse(v.backdropTextless)
        assertNull(v.unplayedCount)
        assertNull(v.nextUp)
        assertNull(v.libraryId)
        assertEquals(emptyList<Any>(), v.sources)
        assertNull(v.episodeNumber)
    }

    @Test
    fun malformedFieldsAreDroppedNotThrown() {
        val v =
            parse(
                """{"id":"p:m:1","title":"Heat","logo":42,"textlessBackdrop":"javascript:x","backdropTextless":"yes","unplayedCount":"4",
                   "nextUp":{"season":"2","episode":5},"libraryId":null,
                   "sources":"nope"}""",
            )!!
        assertNull(v.textlessBackdrop)
        assertFalse(v.backdropTextless)
        assertNull(v.unplayedCount)
        assertNull(v.nextUp)
        assertNull(v.libraryId)
        assertEquals(emptyList<Any>(), v.sources)
        val w = parse("""{"id":"p:m:1","title":"Heat","unplayedCount":-3,"nextUp":{"season":1,"episode":2,"id":""},"sources":[1,{"type":"plex","id":"x"},{"type":"iptv"},{"type":"iptv","id":"ok","copies":0,"quality":5}]}""")!!
        assertNull(w.unplayedCount)
        assertNull(w.nextUp)
        assertEquals(listOf("ok"), w.sources.map { it.id })
        assertNull(w.sources[0].copies)
        assertEquals(emptyList<String>(), w.sources[0].quality)
    }

    // ---- Continue / Next entries ----

    private val resumeJson =
        """{"items":[
            {"kind":"episode","id":"p:e:77","seriesId":"p:s:9","title":"Failure's Contagious","seriesTitle":"Slow Horses","season":2,"episode":5,
             "pos":1152,"dur":2880,"at":1800000000,"backdrop":"/b.jpg","logo":"https://t/logo.png","backdropTextless":true,"seriesTmdbId":95480},
            {"kind":"movie","id":"p:m:1","title":"Heat","pos":3120,"dur":7800,"at":1800000001,"textlessBackdrop":"/tl.jpg","tmdbId":949},
            {"kind":"movie","id":"p:m:2","title":"Ronin","pos":10,"dur":100,"at":5,"logo":7,"tmdbId":"x"}]}"""

    @Test
    fun resumeEntriesParseTheNewFieldsAndStayOptional() {
        val r = IptvResumeParse.items(resumeJson, "https://base")
        assertEquals(3, r.size)
        assertEquals("https://t/logo.png", r[0].logo)
        assertTrue(r[0].backdropTextless)
        assertEquals(95480, r[0].seriesTmdbId)
        assertNull(r[0].tmdbId)
        assertEquals("https://base/tl.jpg", r[1].textlessBackdrop)
        assertEquals(949, r[1].tmdbId)
        assertNull(r[2].tmdbId)
        assertNull(r[2].logo)
    }

    @Test
    fun continueEpisodeTileIsTheSeriesWithTheLibrarysEpisodePill() {
        val ep = IptvResumeParse.items(resumeJson, "https://base")[0].asTileItem()
        assertEquals("Slow Horses", ep.title)
        assertEquals(5, ep.episodeNumber)
        assertEquals(95480, ep.tmdbId)
        val m = ep.toTileModel(poster = false)
        assertEquals("E5", m.cornerText)
        assertEquals(40.0, m.playPercent, 0.001)
        assertFalse(m.played)
        assertEquals(JellytipCardArt("https://base/b.jpg", false, "https://t/logo.png"), m.art)
    }

    @Test
    fun continueFilmTileHasNoPill() {
        val film = IptvResumeParse.items(resumeJson, "https://base")[1].asTileItem()
        assertEquals("Heat", film.title)
        assertNull(film.episodeNumber)
        assertNull(film.toTileModel(poster = false).cornerText)
        assertEquals(40.0, film.toTileModel(poster = false).playPercent, 0.001)
    }
}
