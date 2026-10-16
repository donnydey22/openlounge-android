package com.donnydey.openlounge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CallAudioPolicyTest {
    @Test public void outgoingRingKeepsMediaModeSoRingbackPlays() {
        assertFalse(CallAudioPolicy.enterCommunicationModeOn("call:outgoing"));
        assertFalse(CallAudioPolicy.enterCommunicationModeOn("call:incoming"));
    }
    @Test public void connectedEntersCommunicationMode() {
        assertTrue(CallAudioPolicy.enterCommunicationModeOn("call:connected"));
    }
    @Test public void nullSafe() { assertFalse(CallAudioPolicy.enterCommunicationModeOn(null)); }

    @org.junit.Test
    public void nativeRingbackStartsOnOutgoingStopsOnConnectOrEnd() {
        assertTrue(CallAudioPolicy.startRingbackOn("call:outgoing"));
        assertFalse(CallAudioPolicy.startRingbackOn("call:connected"));
        assertTrue(CallAudioPolicy.stopRingbackOn("call:connected"));
        assertTrue(CallAudioPolicy.stopRingbackOn("call:ended"));
        assertFalse(CallAudioPolicy.stopRingbackOn("call:outgoing"));
    }

    @Test public void ringtonePrepareOnlyWhenIdle() {
        assertTrue(CallAudioPolicy.prepareRingtoneOn("call:ringtonePrepare", false));
        assertFalse(CallAudioPolicy.prepareRingtoneOn("call:ringtonePrepare", true));
        assertFalse(CallAudioPolicy.prepareRingtoneOn("call:incoming", false));
        assertFalse(CallAudioPolicy.prepareRingtoneOn(null, false));
    }
}
