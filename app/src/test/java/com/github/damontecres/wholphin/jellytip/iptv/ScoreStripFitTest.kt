package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreStripFitTest {
    // 10 dp a character
    private val w: (String) -> Float = { it.length * 10f }

    @Test
    fun wideStripKeepsNamesAndCrests() {
        assertEquals(ScoreStripFit.Fit("Panthers", "Warriors", true), ScoreStripFit.fit("Panthers", "Penrith Panthers", "Warriors", null, w, 200f))
    }

    @Test
    fun crestsGoBeforeTheNames() {
        // 80 dp names: no room with a crest (80 + 34 > 100), room without
        assertEquals(ScoreStripFit.Fit("Panthers", "Warriors", false), ScoreStripFit.fit("Panthers", null, "Warriors", null, w, 100f))
    }

    @Test
    fun tightStripFallsBackToCodes() {
        // "Panthers" (80) doesn't fit 64; "PAN" (30) does, and with a crest (30 + 34 = 64)
        assertEquals(ScoreStripFit.Fit("PAN", "WAR", true), ScoreStripFit.fit("Panthers", null, "Warriors", null, w, 64f))
        // Tighter still: codes without crests
        assertEquals(ScoreStripFit.Fit("PAN", "WAR", false), ScoreStripFit.fit("Panthers", null, "Warriors", null, w, 40f))
        // One side fits whole, the other falls back; crests only when both fit with them
        assertEquals(ScoreStripFit.Fit("Eels", "WAR", false), ScoreStripFit.fit("Eels", null, "Warriors", null, w, 60f))
    }

    @Test
    fun codeFromShortElseName() {
        assertEquals("PAN", ScoreStripFit.code("Panthers", "Penrith Panthers"))
        assertEquals("NEW", ScoreStripFit.code(null, "New Zealand Warriors"))
        assertEquals("STG", ScoreStripFit.code("St. George", null))
        assertEquals("", ScoreStripFit.code(null, null))
    }

    @Test
    fun scoreComesOffTheRoomFirst() {
        // 240 dp strip: 120 a side, less 26 fixed, less a 40 dp score and its 6 dp gap
        assertEquals(48f, ScoreStripFit.nameRoom(240f, 40f), 0.001f)
        // No score yet: only the fixed part
        assertEquals(94f, ScoreStripFit.nameRoom(240f, 0f), 0.001f)
    }
}
