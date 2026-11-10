package com.donnydey.openlounge;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/** ol-call-wake: push-woken ring rules (server confirm before ring, dedupe by call id, server-time stale check). */
public class PushWakePolicyTest {
    private static final String ID = "c0ffee-1234";

    @Test public void framesSpeakTheServerProtocol() throws Exception {
        JSONObject r = new JSONObject(PushWakePolicy.resumeFrame("tok"));
        assertEquals("req", r.getString("t"));
        assertEquals("session:resume", r.getString("ev"));
        assertEquals("tok", r.getJSONObject("data").getString("token"));
        JSONObject a = new JSONObject(PushWakePolicy.ackFrame(ID));
        assertEquals("call:ack", a.getString("ev"));
        assertEquals(ID, a.getJSONObject("data").getString("callId"));
        assertNotEquals(r.getInt("id"), a.getInt("id"));
    }

    private static String ackRes(boolean ok, String id, String phase) throws Exception {
        JSONObject d = new JSONObject().put("ok", ok);
        if (id != null) d.put("call", new JSONObject().put("id", id).put("phase", phase));
        return new JSONObject().put("t", "res").put("id", PushWakePolicy.ACK_ID).put("data", d).toString();
    }

    @Test public void ringsOnlyWhenTheServerConfirms() throws Exception {
        assertEquals(PushWakePolicy.Verdict.RING, PushWakePolicy.onFrame(ackRes(true, ID, "calling"), ID));
        assertEquals(PushWakePolicy.Verdict.RING, PushWakePolicy.onFrame(ackRes(true, ID, "ringing"), ID));
        assertEquals(PushWakePolicy.Verdict.DROP, PushWakePolicy.onFrame(ackRes(true, ID, "ended"), ID));
        assertEquals(PushWakePolicy.Verdict.DROP, PushWakePolicy.onFrame(ackRes(true, ID, "active"), ID));
        assertEquals(PushWakePolicy.Verdict.DROP, PushWakePolicy.onFrame(ackRes(false, null, null), ID));
    }

    @Test public void refusedSessionMeansRingWithoutWaiting() throws Exception {
        String bad = new JSONObject().put("t", "res").put("id", PushWakePolicy.RESUME_ID).put("data", new JSONObject().put("ok", false)).toString();
        String good = new JSONObject().put("t", "res").put("id", PushWakePolicy.RESUME_ID).put("data", new JSONObject().put("ok", true)).toString();
        assertEquals(PushWakePolicy.Verdict.NO_SOCKET, PushWakePolicy.onFrame(bad, ID));
        assertNull(PushWakePolicy.onFrame(good, ID));
    }

    @Test public void endedStateEventDropsResentOfferIsIgnored() throws Exception {
        String ended = new JSONObject().put("t", "evt").put("ev", "call:state").put("data", new JSONObject().put("call", new JSONObject().put("id", ID).put("phase", "ended"))).toString();
        String ringing = new JSONObject().put("t", "evt").put("ev", "call:state").put("data", new JSONObject().put("call", new JSONObject().put("id", ID).put("phase", "ringing"))).toString();
        String other = new JSONObject().put("t", "evt").put("ev", "call:state").put("data", new JSONObject().put("call", new JSONObject().put("id", "other").put("phase", "ended"))).toString();
        String resent = new JSONObject().put("t", "evt").put("ev", "call:incoming").put("data", new JSONObject().put("call", new JSONObject().put("id", ID).put("phase", "calling"))).toString();
        assertEquals(PushWakePolicy.Verdict.DROP, PushWakePolicy.onFrame(ended, ID));
        assertNull(PushWakePolicy.onFrame(ringing, ID));
        assertNull(PushWakePolicy.onFrame(other, ID));
        assertNull(PushWakePolicy.onFrame(resent, ID));
        assertNull(PushWakePolicy.onFrame("not json", ID));
    }

    @Test public void staleCheckUsesServerTimeNotThePhoneClock() throws Exception {
        // server: sent at 1000, ring window until 111000 (110 s). Phone clock is 1 h off: the window still holds.
        long phoneNow = 1000 + 3_600_000;
        assertEquals(phoneNow + 110_000, PushWakePolicy.localDeadline(phoneNow, 111_000, 1000));
        // no sentAt (old server): absolute expiresAt as before
        assertEquals(111_000, PushWakePolicy.localDeadline(phoneNow, 111_000, 0));
    }

    @Test public void dedupeByCallId() throws Exception {
        PushWakePolicy.SeenCalls s = new PushWakePolicy.SeenCalls(60_000, 4);
        assertTrue(s.first(ID, 0));
        assertFalse("the ~5 s repush for the same call is ignored", s.first(ID, 5_000));
        assertTrue(s.first("other", 5_000));
        assertTrue("forgotten after the keep window", s.first(ID, 70_000));
    }

    @Test public void fallbackAndHoldBounds() throws Exception {
        assertEquals(2_000L, PushWake.FALLBACK_MS);
        assertTrue(PushWake.HOLD_MS <= 15_000L);
    }
}
