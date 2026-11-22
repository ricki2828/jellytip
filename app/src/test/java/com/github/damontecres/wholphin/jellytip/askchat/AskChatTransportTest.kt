package com.github.damontecres.wholphin.jellytip.askchat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The transport rules of the web's ChatSender: timeout = error 0, supersede = aborted, wrong rev = error, status mapping, warm. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class AskChatTransportTest {
    private val live: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("jellytip/askchat-live-20261122.json")!!.bufferedReader().readText())
    }
    private val headline: String get() = live.getJSONObject("headline").getJSONObject("resp").toString()

    private fun body(rev: Int = 1) = ChatBody(rev = rev, text = "x")

    private fun answering(
        status: Int,
        data: String,
    ) = ChatPost { _, _, _ -> status to data }

    private fun respWithRev(rev: Int) = JSONObject(headline).put("rev", rev).toString()

    @Test
    fun aCallThatNeverAnswersTimesOutAsError0AndPendingCanClear() =
        runTest {
            var timeoutSeen = 0L
            var pathSeen = ""
            val sender =
                ChatSender(
                    ChatPost { path, _, t ->
                        pathSeen = path
                        timeoutSeen = t
                        awaitCancellation()
                    },
                    timeoutMs = 30_000,
                )
            val issued = AskChat.submitText(ChatState(), "x")!!
            val out = sender.send(issued.body)
            assertEquals(Sent.Error(0), out)
            assertTrue("it waited about the timeout in virtual time", currentTime in 30_000L..31_000L)
            assertEquals("/v1/ask/chat", pathSeen)
            assertEquals(30_000L, timeoutSeen)
            val s = AskChat.applyError(issued.state, 1, (out as Sent.Error).status).state
            assertNull(s.pending)
            assertEquals(ChatError.Unreachable, s.error)
            assertNotNull(AskChat.retry(s))
        }

    @Test
    fun theDefaultTimeoutIs12Seconds() =
        runTest {
            val sender = ChatSender(ChatPost { _, _, _ -> awaitCancellation() })
            assertEquals(Sent.Error(0), sender.send(body()))
            assertTrue(currentTime in 12_000L..13_000L)
            assertEquals(12_000L, CHAT_SEND_TIMEOUT_MS)
        }

    @Test
    fun aSupersededRequestIsAbortedNeverAnError() =
        runTest {
            val gate1 = CompletableDeferred<Pair<Int, String>?>()
            val gate2 = CompletableDeferred<Pair<Int, String>?>()
            var calls = 0
            val sender =
                ChatSender(
                    ChatPost { _, b, _ ->
                        calls++
                        if (JSONObject(b).getInt("rev") == 1) gate1.await() else gate2.await()
                    },
                )
            val p1 = async { sender.send(body(1)) }
            runCurrent()
            val p2 = async { sender.send(body(2)) }
            runCurrent()
            gate1.complete(200 to respWithRev(1))
            gate2.complete(200 to respWithRev(2))
            assertEquals(Sent.Aborted, p1.await())
            val r2 = p2.await()
            assertTrue(r2 is Sent.Ok && r2.resp.rev == 2)
            assertEquals(2, calls)
        }

    @Test
    fun aSupersededRequestThatLaterTimesOutIsStillAborted() =
        runTest {
            val sender = ChatSender(ChatPost { _, _, _ -> awaitCancellation() }, timeoutMs = 40_000)
            val p1 = async { sender.send(body(1)) }
            runCurrent()
            val p2 = async { sender.send(body(2)) }
            runCurrent()
            assertEquals(Sent.Aborted, p1.await())
            assertEquals(Sent.Error(0), p2.await())
        }

    @Test
    fun statusMappingBadBodiesAndThrowingCalls() =
        runTest {
            for (st in listOf(404, 401, 403, 400, 413, 429, 500, 502, 503)) {
                assertEquals(Sent.Error(st), ChatSender(answering(st, "{}")).send(body()))
            }
            assertEquals(Sent.Error(0), ChatSender(ChatPost { _, _, _ -> null }).send(body()))
            assertEquals("garbage 200", Sent.Error(500), ChatSender(answering(200, "not json")).send(body()))
            assertEquals("200 that is not a chat answer", Sent.Error(500), ChatSender(answering(200, """{"nope":1}""")).send(body()))
            assertEquals("a call that throws", Sent.Error(0), ChatSender(ChatPost { _, _, _ -> throw java.io.IOException("net") }).send(body()))
            val ok = ChatSender(answering(200, respWithRev(1))).send(body(1))
            assertTrue(ok is Sent.Ok && ok.resp.mode == "llm")
        }

    @Test
    fun aWrongRevIsAnErrorNotASilentDrop() =
        runTest {
            val out = ChatSender(answering(200, respWithRev(99))).send(body(1))
            assertEquals(Sent.Error(500), out)
            val issued = AskChat.submitText(ChatState(), "x")!!
            val s = AskChat.applyError(issued.state, 1, 500).state
            assertNull(s.pending)
            assertEquals(ChatError.Unreachable, s.error)
        }

    @Test
    fun warmIsFireAndForgetAndNeverThrows() =
        runTest {
            val paths = mutableListOf<String>()
            val bodies = mutableListOf<String>()
            val sender = ChatSender(ChatPost { p, b, _ -> paths.add(p); bodies.add(b); throw IllegalStateException("boom") })
            sender.warm(this)
            advanceUntilIdle()
            assertEquals(listOf("/v1/ask/chat/warm"), paths)
            assertEquals(listOf("{}"), bodies)
        }

    @Test
    fun theRequestBodyIsTheExactJson() =
        runTest {
            var sent = ""
            val sender = ChatSender(ChatPost { _, b, _ -> sent = b; 200 to respWithRev(1) })
            val issued = AskChat.submitText(ChatState(), "  funny  ")!!
            sender.send(issued.body)
            val j = JSONObject(sent)
            assertEquals(1, j.getInt("v"))
            assertEquals(1, j.getInt("rev"))
            assertEquals("funny", j.getString("text"))
            assertEquals(0, j.getJSONArray("turns").length())
            assertEquals(0, j.getJSONArray("chips").length())
            assertEquals(0, j.getJSONArray("removed").length())
            assertTrue(!j.has("op"))
        }

    @Test
    fun everyTransportOutcomeLeavesPendingNullThroughTheScreensLoop() =
        runTest {
            val base = AskChat.applyResponse(AskChat.submitText(ChatState(), "first")!!.state, AskChat.parseResponse(respWithRev(1))!!)
            val classes: List<Pair<String, ChatPost>> =
                listOf(
                    "200 ok" to ChatPost { _, _, _ -> 200 to respWithRev(2) },
                    "200 wrong rev" to ChatPost { _, _, _ -> 200 to respWithRev(7) },
                    "200 garbage" to ChatPost { _, _, _ -> 200 to """{"nope":1}""" },
                    "never answers" to ChatPost { _, _, _ -> awaitCancellation() },
                    "throws" to ChatPost { _, _, _ -> throw java.io.IOException("x") },
                    "unreachable" to ChatPost { _, _, _ -> null },
                ) + listOf(0, 400, 401, 403, 404, 413, 429, 500, 502, 503, 504).map { st -> "status $st" to ChatPost { _, _, _ -> st to "{}" } }
            for ((name, post) in classes) {
                var issued = AskChat.submitText(base, "second")!!
                var end: ChatState? = null
                for (i in 0..2) {
                    val out = ChatSender(post, timeoutMs = 50).send(issued.body)
                    if (out is Sent.Ok) {
                        end = AskChat.applyResponse(issued.state, out.resp)
                        break
                    }
                    val e = AskChat.applyError(issued.state, issued.body.rev, (out as Sent.Error).status)
                    if (e.resend == null) {
                        end = e.state
                        break
                    }
                    issued = Issued(e.state, e.resend)
                }
                assertNotNull(name, end)
                assertNull(name, end!!.pending)
            }
        }
}
