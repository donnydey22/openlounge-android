package com.donnydey.openlounge.olcall

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OlCallLogTest {
    private fun dir(): File = Files.createTempDirectory("calllog").toFile()

    /** Same table as server/calls/callLog.ts (tests/calls/callLog.test.ts) and iOS OLCallLogTests. */
    @Test fun everyDiagnosedReasonMapsToATag() {
        val cases = mapOf(
            "offer-never-sent:ws-failure" to "offer_never_sent", "offer-never-arrived:tg-failed" to "offer_never_sent",
            "answer-never-arrived:ws-closed:1006" to "answer_timeout", "answer-never-sent:tg-failed" to "answer_timeout",
            "ice-stalled:tg-failed" to "ice_failure", "ice-stalled:ws-failure" to "connection_stalled",
            "join-never-acked:ws-failure" to "connection_stalled", "after-connect:ws-closed:1001" to "connection_stalled",
            "audio-session-activation-failed" to "audio_session_handoff_failure", "adm:open-report:ok=0" to "audio_session_handoff_failure",
            "turn-alloc-failed:401" to "turn_relay_failure", "no_turn" to "turn_relay_failure", "ice-Failed" to "ice_failure",
            "join-timeout" to "answer_timeout", "ws-closed-before-media" to "connection_stalled", "something new" to "connection_stalled",
            "permission-denied" to "permission_denied", "olcall-unavailable" to "engine_unavailable",
            "join-never-acked:server-error:unauthorized" to "join_rejected",
        )
        for ((raw, tag) in cases) assertEquals(raw, tag, OlCallLogTags.tag(raw))
        for (t in OlCallLogTags.FAILURE) assertEquals(t, OlCallLogTags.tag(t))
        assertEquals("turn_relay_failure", OlCallLogTags.tag("ice-stalled:tg-failed", turnServers = 0))
    }

    private fun meta(tok: String? = "tok.sig") = OlCallLog.Meta("c1", "caller", "video", "Google Pixel 8", "Android 15 (API 35)", "0.3.37 (54)", "libtgcalls-android/olcall", tok)

    @Test fun fullStateMachineIsQueuedWithTimestampsAndTagsAndSurvivesRestart() {
        val d = dir(); val f = File(d, "queue.jsonl")
        var now = 1_000L
        val states = OlCallStates(nowMs = { now }, sink = {})
        OlCallLog(OlCallLogQueue(f), null, meta(), nowMs = { 5_000L + now }).apply { turnServers = 3 }.attach(states)
        states.setState(OlCallStates.State.joining, "join-sent"); now = 1_120
        states.setState(OlCallStates.State.offering, "joined"); now = 1_900
        states.fail("ws-failure")
        // "restart": a brand-new queue object on the same file
        val ev = OlCallLogQueue(f).all()
        assertEquals(listOf("joining", "offering", "failed"), ev.map { it.getString("to") })
        assertEquals(listOf("idle", "joining", "offering"), ev.map { it.getString("from") })
        assertEquals(listOf(0L, 120L, 900L), ev.map { it.getLong("t") })
        assertEquals("offer_never_sent", ev[2].getString("reason"))
        assertEquals("offer-never-sent:ws-failure", ev[2].getString("detail"))
        for (e in ev) {
            assertEquals("android", e.getString("source")); assertEquals("video", e.getString("media")); assertEquals("caller", e.getString("role"))
            assertEquals("Google Pixel 8", e.getString("device")); assertEquals("0.3.37 (54)", e.getString("appVersion")); assertTrue(e.getLong("ts") > 5_000)
            assertEquals(3, e.getJSONObject("extra").getInt("turnServers"))
        }
    }

    @Test fun audioSessionFailureTagsTheFailedTransition() {
        val f = File(dir(), "q.jsonl"); val q = OlCallLogQueue(f)
        val states = OlCallStates(nowMs = { 0 }, sink = {})
        val log = OlCallLog(q, null, meta()); log.attach(states)
        states.setState(OlCallStates.State.joining, "join-sent"); states.setState(OlCallStates.State.answering, "joined")
        log.event("audio_session", "audio_session_handoff_failure", "adm-open-report:{ok:0}")
        states.fail("tg-failed")
        assertEquals("audio_session_handoff_failure", q.all().last().getString("reason"))
    }

    @Test fun uploaderRemovesOnlyAcceptedEventsStripsTokenAndKeepsOnNetworkError() {
        val f = File(dir(), "q.jsonl"); val q = OlCallLogQueue(f)
        val log = OlCallLog(q, null, meta("tokA.sig"))
        log.failedBeforeStart("permission-denied"); log.event("ended", "normal", "local-hangup")
        val sent = ArrayList<Triple<String, String?, JSONObject>>()
        var code = -1
        val up = OlCallLogUploader(q, { "https://openlounge.app/api/call-log" }, { null }) { u, b, body -> sent.add(Triple(u, b, JSONObject(body))); code }
        assertEquals(0, up.flushNow()); assertEquals(2, q.size()) // offline: kept
        code = 200
        assertEquals(2, up.flushNow()); assertEquals(0, q.size())
        val last = sent.last().third
        assertEquals("tokA.sig", last.getString("olcallToken"))
        assertTrue((0 until last.getJSONArray("events").length()).none { last.getJSONArray("events").getJSONObject(it).has("_tok") })
        assertEquals("permission_denied", last.getJSONArray("events").getJSONObject(0).getString("reason"))
        // with a session bearer: one batch, no token in the body
        log.event("x"); val up2 = OlCallLogUploader(q, { "u" }, { "sess" }) { _, b, body -> sent.add(Triple("u", b, JSONObject(body))); 200 }
        assertEquals(1, up2.flushNow()); assertEquals("sess", sent.last().second); assertTrue(!sent.last().third.has("olcallToken"))
    }

    @Test fun signedOutWithoutTokenStaysQueued() {
        val q = OlCallLogQueue(File(dir(), "q.jsonl"))
        OlCallLog(q, null, meta(null)).event("x")
        val up = OlCallLogUploader(q, { "u" }, { null }) { _, _, _ -> error("must not send") }
        assertEquals(0, up.flushNow()); assertEquals(1, q.size())
    }

    @Test fun queueIsBounded() {
        val q = OlCallLogQueue(File(dir(), "q.jsonl"), maxEvents = 10)
        val log = OlCallLog(q, null, meta())
        repeat(25) { log.event("e$it") }
        assertEquals(10, q.size()); assertEquals("e24", q.all().last().getString("event"))
    }

    @Test fun claimsFromTokenPayload() {
        val p1 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"callId":"abc-1","userId":"u","role":"callee","exp":1}""".toByteArray())
        assertEquals("abc-1" to "callee", OlCallLog.claims("$p1.sig"))
        assertNull(OlCallLog.claims("garbage")); assertNull(OlCallLog.claims(null))
    }
}
