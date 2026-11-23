package com.github.damontecres.wholphin.jellytip.askchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ricki 23-Nov "why is chat in basic mode?": the label only when the AI was unavailable for a typed turn. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class AskChatBasicLabelTest {
    private fun resp(
        mode: String,
        basic: Boolean? = null,
    ) = AskChat.parseResponse(
        """{"v":1,"rev":1,"mode":"$mode","reply":"Comedy",${basic?.let { "\"basic\":$it," } ?: ""}"chips":[],"cards":[],"notes":[],"sources":["library"],"matched":0,"partial":false}""",
    )!!

    private fun afterText(r: ChatResponse) = AskChat.applyResponse(AskChat.submitText(ChatState(), "funny")!!.state, r)

    private fun afterTap(r: ChatResponse): ChatState {
        val s = AskChat.applyResponse(AskChat.submitText(ChatState(), "funny")!!.state, resp("llm"))
        val chip = s.chips.firstOrNull()
        val issued = AskChat.tapChip(s, if (chip != null) ChatOp.Remove(chip.id) else ChatOp.Remove("genre:Comedy"))!!
        return AskChat.applyResponse(issued.state, r.copy(rev = issued.state.rev))
    }

    @Test
    fun serverFlagWins() {
        assertTrue(afterText(resp("fallback", basic = true)).basic)
        assertFalse(afterText(resp("fallback", basic = false)).basic) // an empty AI answer: plain reading, not "basic"
    }

    @Test
    fun olderServerTypedFallbackIsBasic() {
        assertTrue(afterText(resp("fallback")).basic)
        assertFalse(afterText(resp("llm")).basic)
    }

    @Test
    fun chipTapIsNeverBasic() {
        assertFalse(afterTap(resp("fallback")).basic)
    }

    @Test
    fun parsesBasic() {
        assertTrue(resp("fallback", true).basic == true)
        assertTrue(resp("llm").basic == null)
    }
}
