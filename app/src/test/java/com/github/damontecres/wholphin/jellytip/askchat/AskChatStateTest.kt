package com.github.damontecres.wholphin.jellytip.askchat

import com.github.damontecres.wholphin.jellytip.ask.AskItem
import com.github.damontecres.wholphin.jellytip.ask.AskParse
import com.github.damontecres.wholphin.jellytip.ask.ChipData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV chat state machine must behave exactly as the web's (web/test/askChat.test.ts, jellytip-web-askchat-iptv f3db77c2). These are
 * those tests, ported, plus the five live server responses (resources/jellytip/askchat-live-20261122.json) run through applyResponse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class AskChatStateTest {
    private val live: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("jellytip/askchat-live-20261122.json")!!.bufferedReader().readText())
    }
    private val year = 2026

    // ---------- helpers ----------

    private fun chip(
        d: ChipData,
        removable: Boolean = true,
        protectedChip: Boolean = false,
    ): ChatChip {
        val c = AskParse.chipFor(d)
        val o = JSONObject().put("id", c.id).put("label", c.label).put("data", AskChat.chipDataToJson(d)).put("removable", removable)
        if (protectedChip) o.put("protected", true)
        return AskChat.parseChip(o)!!
    }

    private fun serverChip(
        id: String,
        label: String,
        data: String,
        applied: Boolean? = null,
    ): ChatChip {
        val o = JSONObject().put("id", id).put("label", label).put("server", true).put("data", JSONObject(data)).put("removable", true)
        if (applied != null) o.put("applied", applied)
        return AskChat.parseChip(o)!!
    }

    private val comedy get() = chip(ChipData.Genre(listOf("Comedy")))
    private val kids get() = chip(ChipData.Kids(listOf("G", "PG"), listOf("Family")), protectedChip = true)

    private fun lib(id: String) = ChatTitle.Library("film", id, id, null, null, null)

    private fun tv(
        iptvId: String,
        kind: String = "film",
    ) = ChatTitle.Iptv(kind, iptvId, "TV $iptvId", 2001, 1, 7.1, "https://image.tmdb.org/t/p/w342/p.jpg", null, listOf("1080p"), listOf("ATMOS"), false, "PG", 98, "atmos")

    private fun resp(
        rev: Int = 1,
        reply: String = "r",
        chips: List<ChatChip> = emptyList(),
        cards: List<ChatTitle> = emptyList(),
        notes: List<ChatNote> = emptyList(),
        sources: List<String> = listOf("library"),
        mode: String = "fallback",
        matched: Int = 0,
    ) = ChatResponse(rev, mode, reply, chips, cards, notes, sources, matched, false)

    private fun send(
        s: ChatState,
        text: String,
    ) = AskChat.submitText(s, text)!!

    private fun tap(
        s: ChatState,
        op: ChatOp,
    ) = AskChat.tapChip(s, op)!!

    private fun ids(cs: List<ChatChip>) = cs.map { it.id }

    private fun keys(cs: List<ChatTitle>) = cs.map { AskChat.cardKey(it) }

    private fun settled(
        text: String,
        r: ChatResponse,
    ): ChatState = AskChat.applyResponse(send(ChatState(), text).state, r)

    // ---------- turns ----------

    @Test
    fun submitTextBasics() {
        val s0 = ChatState()
        assertNull(AskChat.submitText(s0, "   "))
        val r = send(s0, "  funny  films  ")
        assertEquals(1, r.body.rev)
        assertEquals("funny films", r.body.text)
        assertEquals(1, r.state.rev)
        assertNotNull(r.state.pending)
        assertEquals(CHAT_MAX_TEXT, send(s0, "x".repeat(500)).body.text!!.length)
        assertTrue(r.body.turns.isEmpty() && r.body.chips.isEmpty() && r.body.removed.isEmpty())
    }

    @Test
    fun pendingShowsTheLocalReadingAsProvisionalChips() {
        var s = settled("x", resp(chips = listOf(comedy)))
        s = send(s, "under 90 minutes").state
        val shown = AskChat.displayChips(s, year)
        assertEquals(listOf("genre:Comedy", "maxrt:90"), shown.map { it.chip.id })
        assertEquals(listOf(false, true), shown.map { it.provisional })
        assertTrue(AskChat.displayChips(AskChat.applyResponse(s, resp(rev = 2, chips = emptyList())), year).isEmpty())
    }

    @Test
    fun pendingOpAddShowsTheChipProvisionalAndRemoveDropsItAtOnce() {
        val base = settled("x", resp(chips = listOf(comedy, chip(ChipData.Kind("Movie")))))
        val add = tap(base, ChatOp.Add(ChipData.Unplayed)).state
        assertEquals(listOf("genre:Comedy", "kind:Movie", "unplayed"), AskChat.displayChips(add, year).map { it.chip.id })
        val rem = tap(base, ChatOp.Remove("kind:Movie")).state
        assertEquals(listOf("genre:Comedy"), AskChat.displayChips(rem, year).map { it.chip.id })
        assertEquals(listOf("genre:Comedy", "kind:Movie"), ids(base.chips))
    }

    @Test
    fun revRuleOlderResponseDroppedLatestApplied() {
        val a = send(ChatState(), "funny")
        val first = AskChat.applyResponse(a.state, resp(rev = 1, reply = "one", cards = listOf(lib("one"))))
        val b = send(first, "scary")
        assertEquals(2, b.body.rev)
        assertSame("rev 1 after rev 2 was issued: dropped", b.state, AskChat.applyResponse(b.state, resp(rev = 1, reply = "OLD", cards = listOf(lib("old")))))
        val ok = AskChat.applyResponse(b.state, resp(rev = 2, reply = "NEW", cards = listOf(lib("new"))))
        assertEquals("NEW", ok.reply)
        assertEquals(listOf("new"), keys(ok.cards))
        assertNull(ok.pending)
        assertSame("a duplicate answer is ignored (nothing pending)", ok, AskChat.applyResponse(ok, resp(rev = 2, reply = "AGAIN")))
        assertSame("a rev from the future is not applied either", a.state, AskChat.applyResponse(a.state, resp(rev = 9)))
    }

    @Test
    fun aSecondSendOrTapWhilePendingIsRefusedAndAfterTheAnswerBuildsFromTheNewState() {
        val first = send(ChatState(), "here with my kids")
        assertNull(AskChat.submitText(first.state, "something funny"))
        assertNull(AskChat.tapChip(first.state, ChatOp.Remove("kids")))
        assertNull(AskChat.tapChip(first.state, ChatOp.Add(ChipData.Unplayed)))
        assertEquals(1, first.state.rev)
        val answered = AskChat.applyResponse(first.state, resp(rev = 1, chips = listOf(kids), reply = "Family picks"))
        val second = send(answered, "something funny")
        assertEquals(2, second.body.rev)
        assertEquals(listOf("kids"), ids(second.body.chips))
        assertEquals(listOf(ChatTurn("here with my kids", "Family picks")), second.body.turns)
        assertNotNull(AskChat.tapChip(answered, ChatOp.Remove("kids")))
    }

    @Test
    fun applyResponseReplacesWholesaleAndRecordsTheTurn() {
        val s1 = settled("funny", resp(reply = "Comedy", mode = "llm", chips = listOf(comedy), cards = listOf(lib("a")), notes = listOf(ChatNote("n", "c"))))
        assertEquals(listOf(ChatTurn("funny", "Comedy")), s1.turns)
        val s2 =
            AskChat.applyResponse(
                send(s1, "shorter").state,
                resp(rev = 2, reply = "Comedy · short", mode = "fallback", chips = listOf(chip(ChipData.MaxRuntime(90))), cards = listOf(lib("b"), lib("c")), sources = listOf("library", "iptv")),
            )
        assertEquals(listOf("maxrt:90"), ids(s2.chips))
        assertEquals(listOf("b", "c"), keys(s2.cards))
        assertTrue(s2.notes.isEmpty())
        assertEquals("fallback", s2.mode)
        assertEquals(listOf("library", "iptv"), s2.sources)
        assertEquals("Comedy · short", s2.reply)
        assertEquals(2, s2.turns.size)
    }

    @Test
    fun turnsAtMostSixAreKeptAndSent() {
        var s = ChatState()
        for (i in 1..9) {
            val r = send(s, "t$i")
            assertTrue(r.body.turns.size <= CHAT_MAX_TURNS)
            s = AskChat.applyResponse(r.state, resp(rev = i, reply = "r$i"))
        }
        assertEquals(CHAT_MAX_TURNS, s.turns.size)
        assertEquals(listOf("t4", "t5", "t6", "t7", "t8", "t9"), s.turns.map { it.user })
        assertEquals(CHAT_MAX_TURNS, send(s, "next").body.turns.size)
    }

    @Test
    fun anOpTurnRestatesTheLastReplyAndAddsNoTurn() {
        var s = settled("kids film", resp(reply = "Family", chips = listOf(chip(ChipData.Kind("Movie")))))
        val r = tap(s, ChatOp.Remove("kind:Movie"))
        assertEquals(ChatOp.Remove("kind:Movie"), r.body.op)
        s = AskChat.applyResponse(r.state, resp(rev = 2, reply = "Family (all kinds)", chips = emptyList()))
        assertEquals(listOf(ChatTurn("kids film", "Family (all kinds)")), s.turns)
        assertEquals(listOf("kind:Movie"), s.removed)
        assertEquals(listOf("kind:Movie"), send(s, "x").body.removed)
        s = AskChat.applyResponse(tap(s, ChatOp.Add(ChipData.Kind("Movie"))).state, resp(rev = 3, chips = listOf(chip(ChipData.Kind("Movie")))))
        assertTrue("adding it back clears it from removed", s.removed.isEmpty())
    }

    @Test
    fun serverAndNativeChipsRoundTripByteIdentical() {
        val ns = serverChip("noscary", "nothing scary", """{"t":"noscary"}""")
        val mood = serverChip("mood:heist", "heist", """{"t":"mood","key":"heist"}""", applied = false)
        val k = kids
        val s = settled("x", resp(chips = listOf(ns, k, mood)))
        val body = send(s, "more").body
        val sent = JSONObject(body.toJson()).getJSONArray("chips")
        assertEquals(JSONArray(listOf(ns.raw, k.raw, mood.raw)).toString(), sent.toString())
        assertTrue(body.chips[0].server && !body.chips[1].server)
        assertEquals(1, JSONObject(body.toJson()).getInt("v"))
    }

    // ---------- errors ----------

    @Test
    fun errorsClassesMapAsOnTheWeb() {
        val good = settled("funny", resp(reply = "good", cards = listOf(lib("a")), chips = listOf(comedy)))
        val p = send(good, "scary")
        assertEquals(ChatError.Off, AskChat.applyError(p.state, 2, 404).state.error)
        assertEquals(ChatError.SignedOut, AskChat.applyError(p.state, 2, 401).state.error)
        assertEquals(ChatError.SignedOut, AskChat.applyError(p.state, 2, 403).state.error)
        for (st in listOf(0, 500, 502, 503, 504, 429)) {
            val e = AskChat.applyError(p.state, 2, st)
            assertEquals(st.toString(), ChatError.Unreachable, e.state.error)
            assertNull(e.resend)
            assertNull(e.state.pending)
            assertEquals("good", e.state.reply)
            assertEquals(listOf("genre:Comedy"), ids(e.state.chips))
            assertEquals("the failed turn is not recorded", 1, e.state.turns.size)
        }
        assertSame("an error for an older rev is ignored", p.state, AskChat.applyError(p.state, 1, 503).state)
        val e = AskChat.applyError(p.state, 2, 503).state
        val r = AskChat.retry(e)!!
        assertEquals("retry re-sends the same body (same rev)", p.body, r.body)
        assertTrue(r.state.pending != null && r.state.error == null)
        assertNull(AskChat.retry(good))
        assertNull(AskChat.retry(AskChat.applyError(p.state, 2, 404).state))
        val ok = AskChat.applyResponse(r.state, resp(rev = 2, reply = "recovered", cards = listOf(lib("z"))))
        assertEquals("recovered", ok.reply)
        assertEquals(listOf("z"), keys(ok.cards))
    }

    @Test
    fun unreachableKeepsThePreviousCardsHiddenUntilANewerResponse() {
        val good = settled("kids film", resp(reply = "good", cards = listOf(lib("a"), lib("b")), notes = listOf(ChatNote("n", "c")), matched = 9))
        val p = send(good, "something funny")
        val e = AskChat.applyError(p.state, 2, 503).state
        assertTrue(e.cards.isEmpty() && e.notes.isEmpty() && e.matched == 0)
        assertEquals(ChatError.Unreachable, e.error)
        val again = AskChat.applyError(AskChat.retry(e)!!.state, 2, 0).state
        assertTrue(again.cards.isEmpty())
        val ok = AskChat.applyResponse(AskChat.retry(e)!!.state, resp(rev = 2, cards = listOf(lib("c"))))
        assertEquals(listOf("c"), keys(ok.cards))
    }

    @Test
    fun status400And413TrimTheOldestTurnsResendOnceThenAPlainMessage() {
        var s = ChatState()
        for (i in 1..6) s = AskChat.applyResponse(send(s, "t$i").state, resp(rev = i, reply = "r$i"))
        val p = send(s, "seventh")
        assertEquals(6, p.body.turns.size)
        for (st in listOf(400, 413)) {
            val e = AskChat.applyError(p.state, 7, st)
            val resend = e.resend!!
            assertEquals("same rev", 7, resend.rev)
            assertEquals(listOf("t4", "t5", "t6"), resend.turns.map { it.user })
            assertEquals("seventh", resend.text)
            assertTrue(e.state.pending!!.trimmed && e.state.error == null)
            val second = AskChat.applyError(e.state, 7, st)
            assertNull("only one re-send", second.resend)
            assertEquals(ChatError.TooLong, second.state.error)
            assertNull(second.state.pending)
            assertTrue(second.state.cards.isEmpty() && second.state.notes.isEmpty() && second.state.matched == 0)
            assertEquals(e.state.reply, second.state.reply)
            assertNull("no Retry of the same body", AskChat.retry(second.state))
        }
        // one turn: dropping it is fine, and the re-send still carries the current text, chips and rev
        val oneTurn = AskChat.applyResponse(send(ChatState(), "first").state, resp(rev = 1, reply = "r1", chips = listOf(comedy)))
        val q = send(oneTurn, "second")
        val rs = AskChat.applyError(q.state, 2, 413).resend!!
        assertTrue(rs.turns.isEmpty())
        assertEquals("second", rs.text)
        assertEquals(listOf("genre:Comedy"), ids(rs.chips))
        assertEquals(2, rs.rev)
        // nothing to trim: straight to the message
        val bare = send(ChatState(), "x")
        assertEquals(ChatError.TooLong, AskChat.applyError(bare.state, 1, 413).state.error)
        assertNull(AskChat.applyError(bare.state, 1, 413).resend)
        assertTrue(CHAT_TOO_LONG_TEXT.contains("shorter message"))
        // a trimmed re-send that succeeds is applied normally
        val ok = AskChat.applyResponse(AskChat.applyError(p.state, 7, 400).state, resp(rev = 7, reply = "fine"))
        assertEquals("fine", ok.reply)
    }

    @Test
    fun everyTerminalPathLeavesPendingNull() {
        val base = settled("first", resp(reply = "r", chips = listOf(comedy), cards = listOf(lib("a"))))
        val statuses = listOf(0, 400, 401, 403, 404, 413, 429, 500, 502, 503, 504)
        for (st in statuses) {
            var state = send(base, "second").state
            var body = state.pending!!.body
            for (i in 0..2) {
                val e = AskChat.applyError(state, body.rev, st)
                state = e.state
                if (e.resend == null) break
                body = e.resend
            }
            assertNull("status $st", state.pending)
        }
        val ok = AskChat.applyResponse(send(base, "second").state, resp(rev = 2))
        assertNull(ok.pending)
    }

    // ---------- parsing ----------

    @Test
    fun parseResponseRejectsNonAnswersAndDropsMalformedParts() {
        assertNull(AskChat.parseResponse(null))
        assertNull(AskChat.parseResponse("not json"))
        assertNull(AskChat.parseResponse("""{"v":2,"rev":1,"mode":"llm","reply":""}"""))
        assertNull(AskChat.parseResponse("""{"v":1,"rev":1,"mode":"weird","reply":""}"""))
        val r =
            AskChat.parseResponse(
                """{"v":1,"rev":3,"mode":"llm","reply":"r","chips":[{"id":"a"},null,{"id":"b","label":"B","data":{"t":"x"}}],
                "cards":[{"id":"c","source":"library","kind":"film"},{"id":5},{"id":"d","source":"iptv","kind":"film"}],
                "notes":[{"text":1},{"text":"ok","code":"c"}]}""",
            )!!
        assertEquals(listOf("b"), ids(r.chips))
        assertEquals(listOf("c"), keys(r.cards))
        assertEquals(1, r.notes.size)
        assertTrue(r.sources.isEmpty())
    }

    @Test
    fun notesWithActionsParseUpToThreeValidOpsAndAnActionIsATap() {
        val base = live.getJSONObject("paul_rudd").getJSONObject("resp")
        val j = JSONObject(base.toString())
        val acts =
            JSONArray(
                """[{"label":"Drop “with Paul Rudd”","op":{"remove":"person:3e909596adb405578ff2191e296d5826"}},{"label":"Drop the kids filter","op":{"remove":"kids"}},
                {"label":"bad","op":{"remove":""}},{"label":"bad2"},{"label":"x","op":{"add":{"t":"unplayed"}}},{"label":"y","op":{"remove":"z"}},{"label":"z","op":{"remove":"w"}},
                {"label":"mystery","op":{"add":{"t":"mystery"}}}]""",
            )
        j.put("notes", JSONArray().put(JSONObject().put("text", "Nothing fits all of that. Try dropping one:").put("code", "no_results").put("actions", acts)))
        val p = AskChat.parseResponse(j.toString())!!
        val a = p.notes[0].actions
        assertEquals(3, a.size)
        assertEquals(listOf("Drop “with Paul Rudd”", "Drop the kids filter", "x"), a.map { it.label })
        val t = tap(AskChat.applyResponse(send(ChatState(), "x").state, p.copy(rev = 1)), a[1].op)
        assertEquals(ChatOp.Remove("kids"), t.body.op)
        assertEquals(ChatOp.Add(ChipData.Unplayed), a[2].op)
    }

    @Test
    fun chipDataParsingIsValidated() {
        assertNull(AskChat.parseChipData(JSONObject("""{"t":"genre","genres":[]}""")))
        assertNull(AskChat.parseChipData(JSONObject("""{"t":"mystery"}""")))
        assertNull(AskChat.parseChipData(JSONObject("""{"t":"years","from":2000,"to":1990}""")))
        assertNull(AskChat.parseChipData(JSONObject("""{"t":"maxRuntime","min":"90"}""")))
        assertNull(AskChat.parseChipData(JSONObject("""{"t":"minRating","value":11}""")))
        assertNull(AskChat.parseChipData(JSONObject("""{"t":"maxSeasons","n":0}""")))
        assertNull(AskChat.parseChipData(JSONObject("""{"t":"person","id":"a","name":"X","role":"Writer"}""")))
        assertEquals(ChipData.Years(1990, 1999), AskChat.parseChipData(JSONObject("""{"t":"years","from":1990,"to":1999}""")))
        assertEquals(ChipData.MaxRuntime(90), AskChat.parseChipData(JSONObject("""{"t":"maxRuntime","min":90}""")))
        assertEquals(ChipData.Person("a".repeat(32), "X Y", "Actor"), AskChat.parseChipData(JSONObject("""{"t":"person","id":"${"a".repeat(32)}","name":"X Y","role":"Actor"}""")))
        // every ChipData survives a trip through JSON
        for (d in listOf(ChipData.Genre(listOf("A", "B")), ChipData.NotGenre(listOf("Animation")), ChipData.Kind("Series"), ChipData.MinRuntime(60), ChipData.Years(1990, 1999), ChipData.Unplayed, ChipData.Played,
            ChipData.Kids(listOf("G"), listOf("Family")), ChipData.MinRating(7.5), ChipData.MaxSeasons(2), ChipData.Person("b".repeat(32), "N", "Director"))) {
            assertEquals(d, AskChat.parseChipData(AskChat.chipDataToJson(d)))
        }
    }

    // ---------- live fixtures ----------

    @Test
    fun everyLiveResponseAppliesExactlyAsGiven() {
        val names = live.keys().asSequence().toList()
        assertEquals(setOf("headline", "paul_rudd", "remove_kids", "op_add_person", "fallback_no_llm"), names.toSet())
        for (name in names) {
            val f = live.getJSONObject(name)
            val body = f.getJSONObject("body")
            val rawResp = f.getJSONObject("resp")
            val r = AskChat.parseResponse(rawResp.toString())
            assertNotNull(name, r)
            val s0 = ChatState(rev = body.getInt("rev") - 1)
            val sub =
                if (body.has("text")) {
                    AskChat.submitText(s0, body.getString("text"))!!
                } else {
                    val op = body.getJSONObject("op")
                    val chatOp = if (op.has("remove")) ChatOp.Remove(op.getString("remove")) else ChatOp.Add(AskChat.parseChipData(op.getJSONObject("add"))!!)
                    AskChat.tapChip(s0, chatOp)!!
                }
            val out = AskChat.applyResponse(sub.state, r!!)
            assertEquals(name, body.getInt("rev"), out.rev)
            assertEquals("$name chips exactly as given", (0 until rawResp.getJSONArray("chips").length()).map { rawResp.getJSONArray("chips").getJSONObject(it).toString() }, out.chips.map { it.raw.toString() })
            assertEquals("$name card ids in server order", (0 until rawResp.getJSONArray("cards").length()).map { rawResp.getJSONArray("cards").getJSONObject(it).getString("id") }, keys(out.cards))
            assertEquals("$name notes", rawResp.getJSONArray("notes").length(), out.notes.size)
            assertEquals("$name mode", rawResp.getString("mode"), out.mode)
            assertEquals("$name sources", (0 until rawResp.getJSONArray("sources").length()).map { rawResp.getJSONArray("sources").getString(it) }, out.sources)
            assertEquals("$name reply", rawResp.getString("reply"), out.reply)
            assertTrue("$name every card is a library card", out.cards.all { it is ChatTitle.Library })
        }
    }

    @Test
    fun theHeadlineKidsAnswer() {
        val f = live.getJSONObject("headline")
        val out = AskChat.applyResponse(AskChat.submitText(ChatState(), f.getJSONObject("body").getString("text"))!!.state, AskChat.parseResponse(f.getJSONObject("resp").toString())!!)
        assertEquals("llm", out.mode)
        assertEquals("Family picks · PG or under · no Animation · nothing scary", out.reply)
        assertEquals(listOf("notgenre:Animation", "kids", "noscary"), ids(out.chips))
        val k = out.chips.first { it.id == "kids" }
        assertTrue(k.isProtected && !k.server && k.native != null)
        val ns = out.chips.first { it.id == "noscary" }
        assertTrue(ns.server && ns.native == null)
        assertEquals(24, out.cards.size)
        assertEquals(54, out.matched)
    }

    @Test
    fun thePaulRuddNoResultsCaseThenTheEchoedStateGoesBackVerbatim() {
        val head = live.getJSONObject("headline")
        val headState = AskChat.applyResponse(AskChat.submitText(ChatState(), head.getJSONObject("body").getString("text"))!!.state, AskChat.parseResponse(head.getJSONObject("resp").toString())!!)
        val r2 = AskChat.submitText(headState, "something with Paul Rudd")!!
        assertEquals(listOf(ChatTurn(head.getJSONObject("body").getString("text"), "Family picks · PG or under · no Animation · nothing scary")), r2.body.turns)
        val sent = JSONObject(r2.body.toJson()).getJSONArray("chips")
        assertEquals(head.getJSONObject("resp").getJSONArray("chips").toString(), sent.toString())
        val out = AskChat.applyResponse(r2.state, AskChat.parseResponse(live.getJSONObject("paul_rudd").getJSONObject("resp").toString())!!)
        assertTrue(out.cards.isEmpty())
        assertEquals("no_results", out.notes[0].code)
    }

    // ---------- cards ----------

    @Test
    fun aFetchThatDropsAnIdLeavesAGapNeverASubstituteAndServerOrderWins() {
        val cards = listOf("a", "b", "c", "d").map { lib(it) }
        val got = AskChat.alignCards(cards, listOf("D", "A", "X", "C")) { it }
        assertEquals(listOf("a", "c", "d"), got.map { AskChat.cardKey(it.card) })
        assertTrue(AskChat.alignCards(cards, emptyList<String>()) { it }.isEmpty())
        assertEquals(1, AskChat.alignCards(listOf(lib("0a1b2c3d4e5f60718293a4b5c6d7e8f9")), listOf("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9")) { it }.size)
    }

    @Test
    fun refineUsesNativeChipsOnly() {
        data class It(val id: String, val genres: List<String>, val year: Int)
        val items = listOf(It("1", listOf("Comedy", "Family"), 1991), It("2", listOf("Comedy", "Family"), 1992), It("3", listOf("Comedy", "Drama"), 1993), It("4", listOf("Comedy"), 1994), It("5", listOf("Drama"), 1995))
        val view: (It) -> AskItem = { AskItem("Movie", 100L * 600_000_000L, null, it.genres, productionYear = it.year, played = false) }
        val ns = serverChip("noscary", "nothing scary", """{"t":"noscary"}""")
        val out = AskChat.refineChat(items, emptyList(), listOf(ns, chip(ChipData.Kind("Movie"))), view)
        assertTrue(out.isNotEmpty())
        assertTrue(out.none { it.id == "kind:Movie" })
        assertTrue(out.any { it.id == "genre:Comedy" })
    }

    // ---------- TV cards ----------

    private fun mixed() = listOf(lib("a"), tv("p1:m:1"), lib("b"), tv("p1:s:2", "series"), lib("c"))

    @Test
    fun mixedResponseKeepsTheServersOrder() {
        val raw =
            JSONObject(live.getJSONObject("headline").getJSONObject("resp").toString())
                .put("rev", 1)
                .put("sources", JSONArray(listOf("library", "iptv")))
                .put(
                    "cards",
                    JSONArray(
                        listOf(
                            JSONObject("""{"kind":"film","source":"library","id":"a"}"""),
                            JSONObject("""{"kind":"film","source":"iptv","iptvId":"p1:m:1","title":"T","year":2001,"tmdbId":1,"rating":7.1,"poster":"https://x/p.jpg","quality":["1080p"],"tech":["ATMOS"],"catalog":false,"certification":"PG","runtime":98,"audio":"atmos"}"""),
                            JSONObject("""{"kind":"film","source":"library","id":"b"}"""),
                            JSONObject("""{"kind":"series","source":"iptv","iptvId":"p1:s:2"}"""),
                        ),
                    ),
                )
        val r = AskChat.parseResponse(raw.toString())!!
        assertEquals(listOf("a", "iptv:p1:m:1", "b", "iptv:p1:s:2"), keys(r.cards))
        assertEquals(listOf("library", "iptv"), r.sources)
        val tvCard = r.cards[1] as ChatTitle.Iptv
        assertEquals(listOf("1080p"), tvCard.quality)
        assertEquals("atmos", tvCard.audio)
        assertEquals("PG", tvCard.certification)
        val s = AskChat.applyResponse(AskChat.submitText(ChatState(), "x")!!.state, r)
        assertEquals(keys(r.cards), keys(s.cards))
    }

    @Test
    fun tvCardsNeedAnIptvIdAndLibraryCardsNeedAnId() {
        val r =
            AskChat.parseResponse(
                """{"v":1,"rev":1,"mode":"llm","reply":"r","cards":[
                {"kind":"film","source":"iptv","iptvId":"p1:m:1"},{"kind":"film","source":"iptv"},{"kind":"film","source":"iptv","iptvId":""},
                {"source":"iptv","kind":"film"},{"kind":"film","source":"library"},{"kind":"show","source":"iptv","iptvId":"z"},{"kind":"film","source":"library","id":"a"}]}""",
            )!!
        assertEquals(listOf("iptv:p1:m:1", "a"), keys(r.cards))
    }

    @Test
    fun alignCardsKeepsTvCardsAtTheirPlacesAndOnlyLibraryIdsAreFetched() {
        val got = AskChat.alignCards(mixed(), listOf("c", "a")) { it }
        assertEquals(listOf("a", "iptv:p1:m:1", "iptv:p1:s:2", "c"), got.map { AskChat.cardKey(it.card) })
        assertEquals(listOf(true, false, false, true), got.map { it.item != null })
        assertEquals(listOf("iptv:p1:m:1", "iptv:p1:s:2"), AskChat.alignCards(mixed(), emptyList<String>()) { it }.map { AskChat.cardKey(it.card) })
        assertEquals(listOf("a", "b", "c"), AskChat.libraryIds(mixed()))
        assertTrue(AskChat.libraryIds(listOf(tv("p1:m:1"))).isEmpty())
    }

    @Test
    fun scopeHintFromTheSourcesActuallySearched() {
        assertEquals("Library", AskChat.scopeHint(listOf("library")))
        assertEquals("Library + TV", AskChat.scopeHint(listOf("library", "iptv")))
        assertEquals("TV", AskChat.scopeHint(listOf("iptv")))
        assertEquals("", AskChat.scopeHint(emptyList()))
        assertEquals("Library + TV", AskChat.scopeHint(listOf("iptv", "library")))
    }

    // ---------- the Claude notice ----------

    @Test
    fun theNoticeIsShownOncePerDevice() {
        val mem = object : NoticeStore {
            var v: Boolean? = null
            override fun get() = v
            override fun set(v: Boolean) {
                this.v = v
            }
        }
        assertFalse(AskChat.noticeSeen(mem))
        AskChat.dismissNotice(mem)
        assertTrue(AskChat.noticeSeen(mem))
        val blocked = object : NoticeStore {
            override fun get(): Boolean? = throw IllegalStateException("blocked")
            override fun set(v: Boolean) = throw IllegalStateException("full")
        }
        assertFalse(AskChat.noticeSeen(blocked))
        AskChat.dismissNotice(blocked) // never throws
        assertEquals("What you type is sent to Claude (Anthropic) to understand it.", CHAT_NOTICE_TEXT)
    }
}
