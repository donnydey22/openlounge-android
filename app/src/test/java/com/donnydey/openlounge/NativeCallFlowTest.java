package com.donnydey.openlounge;

import static org.junit.Assert.*;

import com.donnydey.openlounge.olcall.OlCallLifecycle;
import com.donnydey.openlounge.olcall.OlCallLifecycle.Reason;
import com.donnydey.openlounge.olcall.OlCallLifecycle.State;
import org.junit.Test;

/** The screen is a pure function of the server's seven-state snapshot (BaoTime vocabulary, InRoom shown as active). */
public class NativeCallFlowTest {
    private static OlCallLifecycle l(State s, Reason r) { return new OlCallLifecycle("c1", "caller", s, r, 1, "e"); }

    @Test public void mapsAllSevenServerStates() {
        assertNull(NativeCallFlow.screenFor(l(State.Idle, null)));
        assertEquals(NativeCallFlow.Screen.RINGING, NativeCallFlow.screenFor(l(State.Ringing, null)));
        assertEquals(NativeCallFlow.Screen.ANSWERING, NativeCallFlow.screenFor(l(State.Answering, null)));
        assertEquals(NativeCallFlow.Screen.JOINING, NativeCallFlow.screenFor(l(State.JoiningUrl, null)));
        assertEquals(NativeCallFlow.Screen.CONNECTING, NativeCallFlow.screenFor(l(State.Connecting, null)));
        assertEquals(NativeCallFlow.Screen.ACTIVE, NativeCallFlow.screenFor(l(State.InRoom, null)));
        assertEquals(NativeCallFlow.Screen.FAILED, NativeCallFlow.screenFor(l(State.Terminal, Reason.Failed)));
        for (Reason r : Reason.values()) if (r != Reason.Failed)
            assertEquals(r.name(), NativeCallFlow.Screen.ENDED, NativeCallFlow.screenFor(l(State.Terminal, r)));
    }

    @Test public void baoTimeWording() {
        assertEquals("Ringing…", NativeCallFlow.statusText(NativeCallFlow.Screen.RINGING, null));
        assertEquals("Connecting…", NativeCallFlow.statusText(NativeCallFlow.Screen.ANSWERING, null));
        assertEquals("Connecting…", NativeCallFlow.statusText(NativeCallFlow.Screen.CONNECTING, null));
        assertEquals("Joining…", NativeCallFlow.statusText(NativeCallFlow.Screen.JOINING, null));
        assertEquals("", NativeCallFlow.statusText(NativeCallFlow.Screen.ACTIVE, null));
        assertEquals("Declined", NativeCallFlow.endedText(Reason.Declined));
        assertEquals("No answer", NativeCallFlow.endedText(Reason.Missed));
        assertEquals("Link expired", NativeCallFlow.endedText(Reason.Expired));
        assertEquals("Call ended", NativeCallFlow.endedText(Reason.Completed));
        assertEquals("Call ended", NativeCallFlow.endedText(Reason.Disconnected));
        assertEquals("Call cancelled", NativeCallFlow.endedText(Reason.CancelledByConfig));
        assertEquals("0:07", NativeCallFlow.timer(7));
        assertEquals("12:05", NativeCallFlow.timer(725));
    }

    @Test public void webToneColours() {
        assertEquals(NativeCallFlow.Tone.INITIATING, NativeCallFlow.tone(NativeCallFlow.Screen.RINGING));
        assertEquals(NativeCallFlow.Tone.INITIATING, NativeCallFlow.tone(NativeCallFlow.Screen.CONNECTING));
        assertEquals(NativeCallFlow.Tone.CONNECTED, NativeCallFlow.tone(NativeCallFlow.Screen.ACTIVE));
        assertEquals(NativeCallFlow.Tone.FAILED, NativeCallFlow.tone(NativeCallFlow.Screen.FAILED));
        assertEquals(NativeCallFlow.Tone.ENDED, NativeCallFlow.tone(NativeCallFlow.Screen.ENDED));
        assertEquals(0xFF4338CA, NativeCallFlow.Tone.INITIATING.getFrom());
        assertEquals(1100L, NativeCallFlow.pulseMs(NativeCallFlow.Screen.CONNECTING));
        assertEquals(0L, NativeCallFlow.pulseMs(NativeCallFlow.Screen.ACTIVE));
    }

    @Test public void pushesSevenStatesToPage() {
        assertNull(NativeCallFlow.pageStateJson(l(State.Idle, null)));
        assertEquals(NativeCallFlow.PageState.Ringing, NativeCallFlow.pageState(l(State.Ringing, null)));
        assertEquals(NativeCallFlow.PageState.Answering, NativeCallFlow.pageState(l(State.Answering, null)));
        assertEquals(NativeCallFlow.PageState.JoiningUrl, NativeCallFlow.pageState(l(State.JoiningUrl, null)));
        assertEquals(NativeCallFlow.PageState.Connecting, NativeCallFlow.pageState(l(State.Connecting, null)));
        assertEquals(NativeCallFlow.PageState.Active, NativeCallFlow.pageState(l(State.InRoom, null)));
        assertEquals(NativeCallFlow.PageState.Failed, NativeCallFlow.pageState(l(State.Terminal, Reason.Failed)));
        for (Reason r : Reason.values()) if (r != Reason.Failed)
            assertEquals(NativeCallFlow.PageState.Terminal, NativeCallFlow.pageState(l(State.Terminal, r)));
        assertEquals("{\"type\":\"call:native-state\",\"callId\":\"c1\",\"state\":\"Active\",\"seq\":1}",
            NativeCallFlow.pageStateJson(l(State.InRoom, null)));
        assertEquals("{\"type\":\"call:native-state\",\"callId\":\"c1\",\"state\":\"Terminal\",\"reason\":\"declined\",\"seq\":1}",
            NativeCallFlow.pageStateJson(l(State.Terminal, Reason.Declined)));
        assertNull(NativeCallFlow.pageStateJson(new OlCallLifecycle("x\"});alert(1)//", "caller", State.Ringing, null, 1, "e")));
    }
}
