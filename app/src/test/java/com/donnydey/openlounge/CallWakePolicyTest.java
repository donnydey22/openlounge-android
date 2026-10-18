package com.donnydey.openlounge;

import static org.junit.Assert.*;

import org.junit.Test;

public class CallWakePolicyTest {
    @Test public void proximityOnlyEarpieceLive() {
        assertTrue(CallWakePolicy.proximity(true, CallAudioRoute.Device.EARPIECE));
        assertFalse(CallWakePolicy.proximity(true, CallAudioRoute.Device.SPEAKER));
        assertFalse(CallWakePolicy.proximity(true, CallAudioRoute.Device.BLUETOOTH));
        assertFalse(CallWakePolicy.proximity(false, CallAudioRoute.Device.EARPIECE));
        assertFalse(CallWakePolicy.proximity(true, null));
    }
    @Test public void focus() {
        assertEquals(CallWakePolicy.FocusAction.NOTIFY_LOST, CallWakePolicy.onFocusChange(-1));
        assertEquals(CallWakePolicy.FocusAction.NONE, CallWakePolicy.onFocusChange(-2));
        assertEquals(CallWakePolicy.FocusAction.NONE, CallWakePolicy.onFocusChange(-3));
        assertEquals(CallWakePolicy.FocusAction.NOTIFY_REGAINED, CallWakePolicy.onFocusChange(1));
    }
    @Test public void timeoutCapped() { assertTrue(CallWakePolicy.PARTIAL_TIMEOUT_MS <= 4L * 3600 * 1000); }
}
