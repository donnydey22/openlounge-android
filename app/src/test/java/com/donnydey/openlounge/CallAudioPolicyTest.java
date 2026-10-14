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
}
