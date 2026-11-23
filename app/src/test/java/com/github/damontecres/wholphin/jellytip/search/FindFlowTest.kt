package com.github.damontecres.wholphin.jellytip.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindFlowTest {
    private val sa = SearchParse.answer("""{"q":"the matrix","groups":[],"results":[]}""")

    private fun d(
        mode: FindMode,
        id: String = "f_1",
        ask: String? = "x",
    ) = FindDecision(id, mode, 0.5, "classifier", if (mode == FindMode.CHAT) null else sa, if (mode == FindMode.SEARCH) null else ask)

    @Test
    fun parseDecision() {
        val j = """{"v":1,"id":"f_ab","mode":"both","p":0.5,"why":"classifier","search":{"q":"kids","groups":[],"results":[]},"ask":{"text":"kids"}}"""
        val x = FindParse.decision(j) { SearchParse.answer(it) }!!
        assertEquals(FindMode.BOTH, x.mode)
        assertEquals("f_ab", x.id)
        assertEquals("kids", x.askText)
        assertEquals("kids", x.search!!.q)
        assertNull(FindParse.decision("""{"id":"f","mode":"maybe"}""") { null })
        assertNull(FindParse.decision("""{"mode":"chat"}""") { null })
        assertNull(FindParse.decision("not json") { null })
        val chat = FindParse.decision("""{"id":"f","mode":"chat","search":null,"ask":{"text":"funny"}}""") { SearchParse.answer(it) }!!
        assertNull(chat.search)
        assertEquals("funny", chat.askText)
        assertNull(FindParse.decision("""{"id":"f","mode":"search","search":{"q":"a","groups":[]},"ask":{"text":"a"}}""") { SearchParse.answer(it) }!!.askText)
    }

    @Test
    fun submitAsksFindAndClearsTheChat() {
        val (s, e) = FindFlow.submit(FindUi(), "  something funny ", 0)
        assertTrue(s.deciding)
        assertEquals("something funny", e.find)
        assertTrue(e.resetChat)
        assertNull(e.chat)
        assertEquals(1, s.seq)
        assertEquals(FindUi() to FindEffects(), FindFlow.submit(FindUi(), "   ", 0))
    }

    @Test
    fun decisionsRouteTheTextToTheChat() {
        val (s0, _) = FindFlow.submit(FindUi(), "funny", 0)
        val (chat, e) = FindFlow.decided(s0, s0.seq, "funny", d(FindMode.CHAT, ask = "funny"), 10)
        assertEquals(FindMode.CHAT, chat.view)
        assertEquals("funny", e.chat)
        assertFalse(chat.deciding)
        val (both, e2) = FindFlow.decided(s0, s0.seq, "kids", d(FindMode.BOTH, ask = "kids"), 10)
        assertEquals(FindMode.BOTH, both.view)
        assertEquals("kids", e2.chat)
        val (search, e3) = FindFlow.decided(s0, s0.seq, "the matrix", d(FindMode.SEARCH), 10)
        assertEquals(FindMode.SEARCH, search.view)
        assertNull(e3.chat)
    }

    @Test
    fun staleAnswerDropped() {
        val (s1, _) = FindFlow.submit(FindUi(), "one", 0)
        val (s2, _) = FindFlow.submit(s1, "two", 1)
        val (after, e) = FindFlow.decided(s2, s1.seq, "one", d(FindMode.CHAT), 2)
        assertEquals(s2, after)
        assertEquals(FindEffects(), e)
    }

    @Test
    fun noRouteOrFailureSearchesPlainly() {
        val (s1, _) = FindFlow.submit(FindUi(), "the matrix", 0)
        val (s, e) = FindFlow.decided(s1, s1.seq, "the matrix", null, 5)
        assertEquals(FindMode.SEARCH, s.view)
        assertFalse(s.deciding)
        assertEquals("the matrix", e.search)
    }

    @Test
    fun followUpsInTheChatSkipFind() {
        val s = FindUi(view = FindMode.CHAT, decisionId = "f_1", decidedQ = "funny", decidedAtMs = 0)
        val (same, e) = FindFlow.submit(s, "only movies", 5_000)
        assertEquals(s, same)
        assertEquals("only movies", e.chat)
        assertNull(e.find)
        assertNull(e.feedback)
    }

    @Test
    fun retypedWithinAMinuteIsReportedOnce() {
        val s = FindUi(view = FindMode.SEARCH, decisionId = "f_1", decidedQ = "kids", decidedAtMs = 1_000)
        val (s2, e) = FindFlow.submit(s, "kids films", 30_000)
        assertEquals("f_1" to FindSwitch.RETYPED, e.feedback)
        assertTrue(s2.feedbackSent)
        val (_, again) = FindFlow.submit(s2, "kids comedies", 31_000)
        assertNull(again.feedback)
        assertNull(FindFlow.submit(s, "KIDS", 30_000).second.feedback) // same words
        assertNull(FindFlow.submit(s, "kids films", 1_000 + FindFlow.RETYPE_WINDOW_MS + 1).second.feedback) // too late
    }

    @Test
    fun askChipMovesIntoTheChat() {
        val both = FindUi(view = FindMode.BOTH, decisionId = "f_2", decidedQ = "kids")
        val (s, e) = FindFlow.askChip(both, "kids")
        assertEquals(FindMode.CHAT, s.view)
        assertNull(e.chat) // BOTH already sent it
        assertEquals("f_2" to FindSwitch.ASK_CHIP, e.feedback)
        val search = FindUi(view = FindMode.SEARCH, decisionId = "f_3", decidedQ = "heat")
        val (s2, e2) = FindFlow.askChip(search, "heat")
        assertEquals("heat", e2.chat)
        assertEquals(FindMode.CHAT, s2.view)
        assertNull(FindFlow.askChip(s2, "heat").second.feedback) // once per decision
    }

    @Test
    fun searchChipLeavesTheChat() {
        val chat = FindUi(view = FindMode.CHAT, decisionId = "f_4", decidedQ = "heat")
        val (s, e) = FindFlow.searchChip(chat, "heat")
        assertEquals(FindMode.SEARCH, s.view)
        assertEquals("heat", e.search)
        assertTrue(e.resetChat)
        assertEquals("f_4" to FindSwitch.SEARCH_CHIP, e.feedback)
    }

    @Test
    fun playedConfirmsOnce() {
        val s = FindUi(decisionId = "f_5")
        val (s2, e) = FindFlow.played(s)
        assertEquals("f_5" to FindSwitch.PLAYED, e.feedback)
        assertNull(FindFlow.played(s2).second.feedback)
        assertNull(FindFlow.played(FindUi()).second.feedback)
    }

    @Test
    fun startOverClearsEverything() {
        val s = FindUi(view = FindMode.CHAT, decisionId = "f_6", decidedQ = "x", seq = 3)
        val (n, e) = FindFlow.startOver(s)
        assertEquals(FindUi(seq = 4), n)
        assertTrue(e.resetChat)
    }

    @Test
    fun bothChipWords() {
        assertEquals("Working out what you mean…", FindText.bothChip(true, false, null, 0))
        assertEquals("Asking Chat about this…", FindText.bothChip(false, true, null, 0))
        assertEquals("Chat: Funny · 1990s · 24 picks  ›", FindText.bothChip(false, false, "Funny · 1990s", 24))
        assertEquals("Chat: Funny · 1 pick  ›", FindText.bothChip(false, false, "Funny", 1))
        assertEquals("Ask Chat about this  ›", FindText.bothChip(false, false, "Funny", 0))
    }
}
