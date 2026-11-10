package com.donnydey.openlounge.olcall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** State transitions + log line format (same as iOS): olcall state <from> -> <to> reason=<r> t=<ms> call=<id> */
class OlCallStatesTest {
    private var clock = 100L
    private val out = ArrayList<String>()
    private fun machine() = OlCallStates(nowMs = { clock }, sink = { out.add(it) })

    @Test fun happyPathOutgoing() {
        val m = machine()
        assertEquals(OlCallStates.State.idle, m.state)
        m.setState(OlCallStates.State.joining, "join-sent")
        clock += 120; m.callId = "c1"; m.setState(OlCallStates.State.offering, "joined")
        m.blobSent(); m.blobReceived()
        clock += 1000; m.setState(OlCallStates.State.connected, "tg-established")
        assertEquals(listOf(
            "olcall state idle -> joining reason=join-sent t=0 call=-",
            "olcall state joining -> offering reason=joined t=120 call=c1",
            "olcall state offering -> connected reason=tg-established t=1120 call=c1",
        ), out)
        assertEquals(out, m.lines)
    }

    @Test fun incomingIsAnswering() {
        val m = machine()
        m.setState(OlCallStates.State.joining, "join-sent"); m.callId = "c2"
        m.setState(OlCallStates.State.answering, "joined")
        assertEquals("olcall state joining -> answering reason=joined t=0 call=c2", out.last())
    }

    @Test fun sameStateIgnoredAndFailedIsTerminal() {
        val m = machine()
        m.setState(OlCallStates.State.joining, "a")
        assertNull(m.setState(OlCallStates.State.joining, "again"))
        m.fail("ws-failure")
        assertNull(m.setState(OlCallStates.State.connected, "late"))
        assertNull(m.fail("again"))
        assertEquals(2, out.size)
        assertEquals("olcall state joining -> failed reason=join-never-acked:ws-failure t=0 call=-", out.last())
    }

    @Test fun diagnosisOfferNeverSent() {
        val m = machine()
        m.setState(OlCallStates.State.joining, "join-sent"); m.callId = "c"; m.setState(OlCallStates.State.offering, "joined")
        clock += 30_000; m.fail("connect-timeout")
        assertEquals("olcall state offering -> failed reason=offer-never-sent:connect-timeout t=30000 call=c", out.last())
    }

    @Test fun diagnosisAnswerNeverArrived() {
        val m = machine()
        m.setState(OlCallStates.State.joining, "join-sent"); m.setState(OlCallStates.State.offering, "joined")
        m.blobSent(); m.blobSent()
        assertEquals("answer-never-arrived:tg-failed", m.diagnose("tg-failed"))
    }

    @Test fun diagnosisIceStalled() {
        val m = machine()
        m.setState(OlCallStates.State.joining, "join-sent"); m.setState(OlCallStates.State.answering, "joined")
        m.blobReceived(); m.blobSent()
        assertEquals("ice-stalled:connect-timeout", m.diagnose("connect-timeout"))
    }

    @Test fun diagnosisAnsweringSide() {
        val m = machine()
        m.setState(OlCallStates.State.joining, "join-sent"); m.setState(OlCallStates.State.answering, "joined")
        assertEquals("offer-never-arrived:x", m.diagnose("x"))
        m.blobReceived()
        assertEquals("answer-never-sent:x", m.diagnose("x"))
    }

    @Test fun lineNeverCarriesSecrets() {
        // Reasons/ids are the only free text; the machine never sees tokens, keys, blobs or TURN creds.
        val l = OlCallStates.line(OlCallStates.State.idle, OlCallStates.State.joining, "join-sent", 5, "")
        assertEquals("olcall state idle -> joining reason=join-sent t=5 call=-", l)
        assertTrue(!l.contains("token") && !l.contains("credential") && !l.contains("SECRET"))
    }
}
