package com.github.damontecres.wholphin.jellytip.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class IptvDirectTest {
    private val body =
        """{"providers":[
          {"id":"p1","name":"Main","owner":true,"direct":false,"directSuggested":true},
          {"id":"aa","name":"Shared one","owner":false,"direct":true},
          {"id":"bb","name":null,"owner":true,"direct":true},
          {"id":"","name":"Bad","owner":true}
        ]}"""

    @Test
    fun ownProvidersOnly() {
        val l = IptvDirectLogic.parse(body)
        assertEquals(listOf("p1", "bb"), l.map { it.id })
        assertEquals(IptvDirectPref("p1", "Main", false, true), l[0])
        assertEquals(IptvDirectPref("bb", "Provider", true, false), l[1])
        assertEquals(emptyList<IptvDirectPref>(), IptvDirectLogic.parse("""{"providers":[]}"""))
        assertEquals(emptyList<IptvDirectPref>(), IptvDirectLogic.parse("{}"))
    }

    @Test
    fun labels() {
        val p = IptvDirectPref("x", "Main", false, true)
        assertEquals("Direct from provider", IptvDirectLogic.label(p, false))
        assertEquals("Direct from provider · Main", IptvDirectLogic.label(p, true))
        assertTrue(IptvDirectLogic.value(p).startsWith("Off · suggested"))
        assertEquals("On", IptvDirectLogic.value(p.copy(direct = true)))
        assertEquals("Off", IptvDirectLogic.value(p.copy(suggested = false)))
    }
}
