package com.github.damontecres.wholphin.jellytip.kids

import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** org.json is a stub in plain JVM tests, so the parser runs under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class KidsContentTest {
    private val base = "https://c.example"
    private val resolver = KidsImageResolver { id, kind, wide -> "https://img.example/$id/$kind/${if (wide) "wide" else "tile"}" }
    private val id1 = "11111111-1111-1111-1111-111111111111"

    private fun item(
        id: String = id1,
        type: String = "Movie",
        name: String = "Otter Rocket",
        extra: String = "",
    ) = """{"id":"$id","source":"library","type":"$type","name":"$name","image":{"itemId":"$id","kind":"Thumb"}$extra}"""

    private fun bubble(
        i: Int,
        kind: String = "series",
        target: String = "series",
    ) = """{"id":"b$i","kind":"$kind","name":"Friend $i","image":{"itemId":"$id1","kind":"Primary"},"target":{"type":"$target","id":"$id1"}}"""

    @Test
    fun parsesHeroAndRowsInOrder() {
        val json =
            """{"kidsMode":true,
            "hero":{"kind":"resume","item":${item(type = "Episode", extra = ",\"seriesName\":\"Rocket Otters\",\"seriesId\":\"$id1\",\"progress\":0.62")}},
            "rows":[
              {"id":"watch_again","title":"Watch again","style":"tiles","items":[${item(extra = ",\"playCount\":5")},${item(id = "22222222-2222-2222-2222-222222222222", type = "Series")}]},
              {"id":"friends","title":"Your friends","style":"bubbles","items":[${bubble(1)},${bubble(2, "collection", "collection")}]}
            ]}"""
        val d = KidsHomeParse.parse(json, base, resolver)!!
        val hero = d.hero!!
        assertTrue(hero.resume)
        assertEquals(KidsKind.EPISODE, hero.tile.kind)
        assertEquals(0.62f, hero.tile.progress, 0.001f)
        assertEquals("https://img.example/$id1/Thumb/wide", hero.imageUrl)
        assertEquals("https://img.example/$id1/Thumb/tile", hero.tile.imageUrl)
        assertEquals(listOf("watch_again", "friends"), d.rows.map { it.id })
        assertEquals(5, d.rows[0].items[0].playCount)
        assertTrue(d.rows[1].bubbles)
        assertEquals(KidsKind.COLLECTION, d.rows[1].items[1].kind)
    }

    @Test
    fun notAKidsAnswerIsNull() {
        assertNull(KidsHomeParse.parse("""{"kidsMode":false,"hero":null,"rows":[]}""", base, resolver))
        assertNull(KidsHomeParse.parse("""{"error":"not kids"}""", base, resolver))
        assertNull(KidsHomeParse.parse("not json", base, resolver))
    }

    @Test
    fun thinStateIsHeroOnly() {
        val d = KidsHomeParse.parse("""{"kidsMode":true,"hero":{"kind":"favourite","item":${item()}},"rows":[]}""", base, resolver)!!
        assertNotNull(d.hero)
        assertEquals(false, d.hero!!.resume)
        assertTrue(d.rows.isEmpty())
    }

    @Test
    fun unknownThingsAreDroppedNotGuessed() {
        val json =
            """{"kidsMode":true,"hero":null,"rows":[
              {"id":"watch_again","title":"Watch again","style":"tiles","items":[${item(type = "Audio")},{"id":"x","type":"Movie"}]},
              {"id":"friends","title":"Your friends","style":"bubbles","items":[${bubble(1, "series", "collection")}]},
              {"id":"movie_night","title":"Movies for movie night","style":"tiles","items":[${item()}]}
            ]}"""
        val d = KidsHomeParse.parse(json, base, resolver)!!
        // the rows left with nothing are omitted; a bubble whose target type disagrees with its kind is dropped
        assertEquals(listOf("movie_night"), d.rows.map { it.id })
    }

    @Test
    fun iptvItemKeepsItsUrlAndSource() {
        val json = """{"kidsMode":true,"hero":null,"rows":[{"id":"movie_night","title":"M","style":"tiles","items":[
            {"id":"p:m:1","source":"iptv","type":"Movie","name":"Cartoon","image":{"url":"/img/abc.jpg"}}]}]}"""
        val t = KidsHomeParse.parse(json, base, resolver)!!.rows[0].items[0]
        assertEquals("iptv", t.source)
        assertEquals("https://c.example/img/abc.jpg", t.imageUrl)
    }

    @Test
    fun iptvSeriesAndEpisodesAreDroppedNoDeadTiles() {
        val json = """{"kidsMode":true,"hero":{"kind":"favourite","item":{"id":"p:s:1","source":"iptv","type":"Series","name":"Cartoon Show","image":{"url":"/i.jpg"}}},"rows":[{"id":"movie_night","title":"M","style":"tiles","items":[
            {"id":"p:s:1","source":"iptv","type":"Series","name":"Cartoon Show"},
            {"id":"p:e:1","source":"iptv","type":"Episode","name":"Ep"},
            {"id":"p:m:1","source":"iptv","type":"Movie","name":"Cartoon Film"}]}]}"""
        val d = KidsHomeParse.parse(json, base, resolver)!!
        assertNull(d.hero)
        assertEquals(listOf("p:m:1"), d.rows[0].items.map { it.id })
    }

    @Test
    fun progressOutsideZeroToOneIsNoBar() {
        val d = KidsHomeParse.parse("""{"kidsMode":true,"hero":{"kind":"resume","item":${item(extra = ",\"progress\":1.0")}},"rows":[]}""", base, resolver)!!
        assertEquals(0f, d.hero!!.tile.progress, 0f)
    }

    @Test
    fun rowsAreCappedAtThree() {
        val rows = (1..5).joinToString(",") { """{"id":"r$it","title":"R$it","style":"tiles","items":[${item()}]}""" }
        assertEquals(3, KidsHomeParse.parse("""{"kidsMode":true,"hero":null,"rows":[$rows]}""", base, resolver)!!.rows.size)
    }

    @Test
    fun tilesOpenTheRightPage() {
        val movie = KidsTile(id1, "library", KidsKind.MOVIE, "M")
        assertEquals(Destination.MediaItem(UUID.fromString(id1), BaseItemKind.MOVIE), KidsOpen.destination(movie))
        val series = KidsTile(id1, "library", KidsKind.SERIES, "S")
        assertEquals(Destination.MediaItem(UUID.fromString(id1), BaseItemKind.SERIES), KidsOpen.destination(series))
        // an episode opens its series' page
        val ep = KidsTile("33333333-3333-3333-3333-333333333333", "library", KidsKind.EPISODE, "E", seriesId = id1)
        assertEquals(Destination.MediaItem(UUID.fromString(id1), BaseItemKind.SERIES), KidsOpen.destination(ep))
        // dashless Jellyfin ids work
        val dashless = KidsTile(id1.replace("-", ""), "library", KidsKind.MOVIE, "M")
        assertEquals(Destination.MediaItem(UUID.fromString(id1), BaseItemKind.MOVIE), KidsOpen.destination(dashless))
        assertNull(KidsOpen.destination(KidsTile("not-an-id", "library", KidsKind.MOVIE, "M")))
        // a collection opens the kids collection grid (a BoxSet destination the shell routes there); not a UUID = nothing
        assertEquals(Destination.MediaItem(UUID.fromString(id1), BaseItemKind.BOX_SET), KidsOpen.destination(KidsTile(id1, "library", KidsKind.COLLECTION, "Pals")))
        assertNull(KidsOpen.destination(KidsTile("c1", "library", KidsKind.COLLECTION, "Pals")))
        // no kids page for an IPTV series: nothing opens (never the adult page); an IPTV film plays
        assertNull(KidsOpen.destination(KidsTile("p:s:1", "iptv", KidsKind.SERIES, "Cartoon Show")))
        assertNull(KidsOpen.destination(KidsTile("p:e:1", "iptv", KidsKind.EPISODE, "Ep", seriesId = "p:s:1")))
        assertEquals(Destination.JellytipIptvPlay(title = "Cartoon", vodId = "p:m:1"), KidsOpen.destination(KidsTile("p:m:1", "iptv", KidsKind.MOVIE, "Cartoon")))
    }

    private fun ep(
        season: Int,
        n: Int,
        played: Boolean = false,
        resume: Long = 0,
    ) = KidsPlayPick.Ep("s${season}e$n", season, n, played, resume)

    @Test
    fun seriesPlayPick() {
        assertNull(KidsPlayPick.pick(emptyList()))
        val fresh = listOf(ep(1, 1), ep(1, 2))
        assertEquals("s1e1", KidsPlayPick.pick(fresh)!!.episode.id)
        // next after the last watched, whatever order the server sent them in
        val mid = listOf(ep(1, 3), ep(1, 1, true), ep(1, 2, true))
        assertEquals("s1e3", KidsPlayPick.pick(mid)!!.episode.id)
        // a part-watched episode wins and resumes
        val partial = listOf(ep(1, 1, true), ep(1, 2, resume = 5000), ep(1, 3))
        val p = KidsPlayPick.pick(partial)!!
        assertEquals("s1e2", p.episode.id)
        assertTrue(p.resume)
        // everything watched: from the top; specials are not "first" while there are real seasons
        val done = listOf(ep(0, 1, true), ep(1, 1, true), ep(1, 2, true))
        assertEquals("s1e1", KidsPlayPick.pick(done)!!.episode.id)
        assertEquals("s0e1", KidsPlayPick.pick(listOf(ep(0, 1)))!!.episode.id)
    }

    @Test
    fun titles() {
        assertEquals("Rocket Otters", kidsTileTitle(KidsTile("1", "library", KidsKind.EPISODE, "Splashdown!", seriesName = "Rocket Otters")))
        assertEquals("Splashdown!", kidsTileTitle(KidsTile("1", "library", KidsKind.EPISODE, "Splashdown!")))
        assertEquals("Movie", kidsTileTitle(KidsTile("1", "library", KidsKind.MOVIE, "Movie")))
    }

    @Test
    fun heroLine() {
        val tile = KidsTile("1", "library", KidsKind.EPISODE, "Splashdown!", progress = 0.8f)
        assertEquals("Splashdown! · nearly finished!", kidsHeroSub(KidsHero(true, tile, null)))
        assertEquals("Keep watching!", kidsHeroSub(KidsHero(true, tile.copy(kind = KidsKind.MOVIE, progress = 0.3f), null)))
        assertEquals("Today's pick for you", kidsHeroSub(KidsHero(false, tile.copy(kind = KidsKind.MOVIE), null)))
    }

    @Test
    fun runtimes() {
        assertEquals("1h 32m", kidsRuntime(92))
        assertEquals("11 min", kidsRuntime(11))
        assertNull(kidsRuntime(0))
    }
}
